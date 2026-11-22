import io
import time
import unittest
import urllib.error
from unittest import mock

import pr_review_agent
from pr_review_agent import (
    _is_permanent_http_error,
    build_diff_context,
    call_ai_chat_completion,
    extract_assistant_content,
    http_json,
    normalize_chat_endpoint,
    parse_ai_response_body,
    run_review,
    should_skip_file,
)


class PrReviewAgentTests(unittest.TestCase):
    def test_normalize_chat_endpoint(self) -> None:
        self.assertEqual(
            normalize_chat_endpoint("https://api.openai.com/v1"),
            "https://api.openai.com/v1/chat/completions",
        )
        self.assertEqual(
            normalize_chat_endpoint("https://openrouter.ai/api/v1/"),
            "https://openrouter.ai/api/v1/chat/completions",
        )
        self.assertEqual(
            normalize_chat_endpoint("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"),
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
        )
        with self.assertRaises(ValueError):
            normalize_chat_endpoint("   ")

    def test_should_skip_file(self) -> None:
        self.assertTrue(should_skip_file("desktop/Cargo.lock"))
        self.assertTrue(should_skip_file("skills-lock.json"))
        self.assertTrue(should_skip_file("android/gradle/wrapper/gradle-wrapper.jar"))
        self.assertTrue(should_skip_file("docs/screenshot.png"))
        self.assertFalse(should_skip_file("desktop/crates/camcraft-core/src/updater.rs"))
        self.assertFalse(should_skip_file("android/app/src/main/java/org/artcraft/camcraft/MainActivity.kt"))

    def test_build_diff_context_truncates_and_tracks_omitted(self) -> None:
        files = [
            {"filename": "desktop/Cargo.lock", "status": "modified", "additions": 10, "deletions": 2, "patch": "lock"},
            {"filename": "src/a.rs", "status": "modified", "additions": 2, "deletions": 1, "patch": "+let x = 1;"},
            {"filename": "src/b.rs", "status": "modified", "additions": 500, "deletions": 0, "patch": "+" + ("a" * 300)},
        ]
        diff, omitted = build_diff_context(files, max_chars=120, max_file_chars=200)
        self.assertIn("src/a.rs", diff)
        self.assertNotIn("src/b.rs", diff)
        self.assertTrue(any("Cargo.lock" in item for item in omitted))
        self.assertTrue(any("src/b.rs" in item for item in omitted))

    def test_extract_assistant_content_handles_string_and_parts(self) -> None:
        self.assertEqual(
            extract_assistant_content({"choices": [{"message": {"content": "  LGTM  "}}]}),
            "LGTM",
        )
        self.assertEqual(
            extract_assistant_content(
                {"choices": [{"message": {"content": [{"type": "text", "text": "Part 1"}, {"type": "text", "text": "Part 2"}]}}]}
            ),
            "Part 1\nPart 2",
        )
        with self.assertRaises(ValueError):
            extract_assistant_content({"choices": []})

    def test_run_review_skips_gracefully_without_secrets(self) -> None:
        rc = run_review(
            {
                "AI_API_ENDPOINT": "",
                "AI_API_KEY": "",
                "AI_MODEL": "",
                "GITHUB_TOKEN": "dummy",
                "GITHUB_REPOSITORY": "madhasoeki/CamCraft",
                "PR_NUMBER": "1",
            }
        )
        self.assertEqual(rc, 0)

    def test_parse_ai_response_body_handles_json_keepalive_sse_and_html(self) -> None:
        plain = '{"choices": [{"message": {"content": "LGTM"}}]}'
        self.assertEqual(extract_assistant_content(parse_ai_response_body(plain)), "LGTM")

        with_keepalive = ': OPENROUTER PROCESSING\n\n: OPENROUTER PROCESSING\n\n{"choices": [{"message": {"content": "Reviewed cleanly"}}]}'
        self.assertEqual(
            extract_assistant_content(parse_ai_response_body(with_keepalive)),
            "Reviewed cleanly",
        )

        sse = (
            ": OPENROUTER PROCESSING\n"
            'data: {"choices": [{"delta": {"role": "assistant"}}]}\n\n'
            'data: {"choices": [{"delta": {"content": "### Summary\\n"}}]}\n\n'
            'data: {"choices": [{"delta": {"content": "All good."}}]}\n\n'
            "data: [DONE]\n"
        )
        self.assertEqual(
            extract_assistant_content(parse_ai_response_body(sse)),
            "### Summary\nAll good.",
        )

        with self.assertRaises(ValueError):
            parse_ai_response_body("<html><body>504 Gateway Time-out</body></html>")

    def test_parse_ai_response_body_propagates_error_after_partial_content_and_handles_multiline_sse(
        self,
    ) -> None:
        multiline_sse = (
            ": keepalive before\n"
            "data: {\"choices\": [\n"
            "data: {\"delta\": {\"content\": \"Multi-line \"}}\n"
            "data: ]}\n\n"
            ": keepalive middle\r\n"
            'data: {"choices": [{"delta": {"content": "OK"}}]}\r\n\r\n'
            "data: [DONE]\n"
        )
        self.assertEqual(
            extract_assistant_content(parse_ai_response_body(multiline_sse)),
            "Multi-line OK",
        )

        partial_then_error_sse = (
            'data: {"choices": [{"delta": {"content": "Partial look..."}}]}\n\n'
            'data: {"error": {"message": "upstream overloaded", "code": 503}}\n\n'
            "data: [DONE]\n"
        )
        with self.assertRaises(ValueError) as ctx:
            parse_ai_response_body(partial_then_error_sse)
        self.assertIn("upstream overloaded", str(ctx.exception))

    def test_call_ai_chat_completion_falls_back_to_stream_on_transient_error(self) -> None:
        calls: list[dict[str, object]] = []

        def fake_requester(url, method="GET", headers=None, body=None, timeout=120):
            calls.append({"url": url, "headers": headers, "body": body, "timeout": timeout})
            if len(calls) == 1:
                raise RuntimeError("Non-JSON response (HTTP 504, Content-Type: text/html) from ...")
            return 200, {"choices": [{"message": {"content": "Streamed OK"}}]}

        resp = call_ai_chat_completion(
            "https://example.invalid/v1/chat/completions",
            "secret-token",
            {"model": "test-model", "messages": []},
            requester=fake_requester,
        )
        self.assertEqual(extract_assistant_content(resp), "Streamed OK")
        self.assertEqual(len(calls), 2)
        self.assertEqual(calls[0]["body"]["stream"], False)
        self.assertEqual(calls[1]["body"]["stream"], True)
        self.assertIn("text/event-stream", calls[1]["headers"]["Accept"])

    def test_call_ai_chat_completion_enforces_total_timeout_budget(self) -> None:
        calls = 0

        def slow_requester(url, method="GET", headers=None, body=None, timeout=120):
            nonlocal calls
            calls += 1
            raise RuntimeError("Request timed out calling https://example.invalid")

        with self.assertRaises(RuntimeError) as ctx:
            call_ai_chat_completion(
                "https://example.invalid/v1/chat/completions",
                "secret-token",
                {"model": "test-model", "messages": []},
                timeout=0.5,
                total_timeout=0.5,
                requester=slow_requester,
            )
        self.assertEqual(calls, 1)
        self.assertIn("total timeout", str(ctx.exception))

    def test_call_ai_chat_completion_does_not_retry_permanent_http_errors(self) -> None:
        calls = 0

        def unauthorized_requester(*args, **kwargs):
            nonlocal calls
            calls += 1
            raise RuntimeError("HTTP 401 from https://example.invalid: Unauthorized")

        with self.assertRaises(RuntimeError):
            call_ai_chat_completion(
                "https://example.invalid/v1/chat/completions",
                "bad-token",
                {"model": "test-model", "messages": []},
                requester=unauthorized_requester,
            )
        self.assertEqual(calls, 1)

    def test_http_json_rejects_premature_eof_with_content_length_and_retries(self) -> None:
        class FakeResp:
            def __init__(self, status: int, headers: dict[str, str], chunks: list[bytes]) -> None:
                self.status = status
                self.headers = headers
                self._chunks = list(chunks)

            def read1(self, _n: int = -1) -> bytes:
                return self._chunks.pop(0) if self._chunks else b""

            def read(self, n: int = -1) -> bytes:
                return self.read1(n)

            def __enter__(self) -> "FakeResp":
                return self

            def __exit__(self, *args: object) -> None:
                return None

        truncated_resp = FakeResp(
            200,
            {"Content-Length": "100", "Content-Type": "application/json"},
            [b'{"choices":', b""],
        )
        with mock.patch.object(pr_review_agent.urllib.request, "urlopen", return_value=truncated_resp):
            with self.assertRaises(RuntimeError) as ctx:
                http_json(
                    "https://example.invalid/v1/chat/completions",
                    deadline=time.monotonic() + 30.0,
                )
        self.assertIn("Incomplete response body", str(ctx.exception))
        self.assertFalse(_is_permanent_http_error(ctx.exception))

        stream_body = b'data: {"choices": [{"delta": {"content": "Retried OK"}}]}\n\ndata: [DONE]\n'
        attempt1 = FakeResp(
            200,
            {"Content-Length": "100", "Content-Type": "application/json"},
            [b'{"choices":', b""],
        )
        attempt2 = FakeResp(
            200,
            {"Transfer-Encoding": "chunked", "Content-Type": "text/event-stream"},
            [stream_body[:24], stream_body[24:], b""],
        )
        with mock.patch.object(pr_review_agent.urllib.request, "urlopen", side_effect=[attempt1, attempt2]):
            resp = call_ai_chat_completion(
                "https://example.invalid/v1/chat/completions",
                "secret-token",
                {"model": "test-model", "messages": []},
            )
        self.assertEqual(extract_assistant_content(resp), "Retried OK")

    def test_http_json_accepts_complete_chunked_read1_with_content_length(self) -> None:
        body = b'{"choices": [{"message": {"content": "Full OK"}}]}'

        class FakeResp:
            status = 200
            headers = {"Content-Length": str(len(body)), "Content-Type": "application/json"}

            def __init__(self) -> None:
                self._chunks = [body[:15], body[15:], b""]

            def read1(self, _n: int = -1) -> bytes:
                return self._chunks.pop(0) if self._chunks else b""

            def read(self, n: int = -1) -> bytes:
                return self.read1(n)

            def __enter__(self) -> "FakeResp":
                return self

            def __exit__(self, *args: object) -> None:
                return None

        with mock.patch.object(pr_review_agent.urllib.request, "urlopen", return_value=FakeResp()):
            status, parsed = http_json(
                "https://example.invalid/v1/chat/completions",
                deadline=time.monotonic() + 30.0,
            )
        self.assertEqual(status, 200)
        self.assertEqual(extract_assistant_content(parsed), "Full OK")

    def test_http_json_bounds_http_error_body_reads_by_deadline_and_preserves_status(self) -> None:
        http_401 = urllib.error.HTTPError(
            "https://example.invalid/v1/chat/completions",
            401,
            "Unauthorized",
            {},
            io.BytesIO(b"Invalid API key"),
        )
        with mock.patch.object(pr_review_agent.urllib.request, "urlopen", side_effect=http_401):
            with self.assertRaises(RuntimeError) as ctx_401:
                http_json("https://example.invalid/v1/chat/completions", deadline=time.monotonic() + 30.0)
        self.assertTrue(str(ctx_401.exception).startswith("HTTP 401 from "))
        self.assertIn("Invalid API key", str(ctx_401.exception))
        self.assertTrue(_is_permanent_http_error(ctx_401.exception))

        class FakeSock:
            def __init__(self) -> None:
                self.timeouts: list[float] = []

            def settimeout(self, t: float) -> None:
                self.timeouts.append(t)

        class FakeRaw:
            def __init__(self, sock: FakeSock) -> None:
                self._sock = sock

        class FakeBuffered:
            def __init__(self, sock: FakeSock) -> None:
                self.raw = FakeRaw(sock)

        class FakeErrorResp:
            def __init__(self, sock: FakeSock) -> None:
                self.fp = FakeBuffered(sock)
                self._chunks = [b"partial gateway error", b"should not be read"]

            def read1(self, _n: int = -1) -> bytes:
                return self._chunks.pop(0) if self._chunks else b""

            def read(self, _n: int = -1) -> bytes:
                raise AssertionError("unbounded read() must not be called")

            def close(self) -> None:
                return None

        sock = FakeSock()
        http_502 = urllib.error.HTTPError(
            "https://example.invalid/v1/chat/completions",
            502,
            "Bad Gateway",
            {"Content-Type": "text/plain"},
            FakeErrorResp(sock),
        )
        monotonic_times = iter([90.0, 90.0, 100.5])
        with (
            mock.patch.object(pr_review_agent.time, "monotonic", side_effect=lambda: next(monotonic_times)),
            mock.patch.object(pr_review_agent.urllib.request, "urlopen", side_effect=http_502),
        ):
            with self.assertRaises(RuntimeError) as ctx_502:
                http_json("https://example.invalid/v1/chat/completions", timeout=90.0, deadline=100.0)
        self.assertTrue(str(ctx_502.exception).startswith("HTTP 502 from "))
        self.assertIn("partial gateway error", str(ctx_502.exception))
        self.assertNotIn("should not be read", str(ctx_502.exception))
        self.assertEqual(sock.timeouts, [10.0])
        self.assertFalse(_is_permanent_http_error(ctx_502.exception))

    def test_call_ai_chat_completion_retries_on_connection_reset_during_read1(self) -> None:
        class ResetResp:
            status = 200
            headers = {"Content-Type": "application/json"}

            def read1(self, _n: int = -1) -> bytes:
                raise ConnectionResetError("Connection reset by peer")

            def read(self, n: int = -1) -> bytes:
                return self.read1(n)

            def __enter__(self) -> "ResetResp":
                return self

            def __exit__(self, *args: object) -> None:
                return None

        class StreamOkResp:
            status = 200
            headers = {"Transfer-Encoding": "chunked", "Content-Type": "text/event-stream"}

            def __init__(self) -> None:
                body = b'data: {"choices": [{"delta": {"content": "Recovered after reset"}}]}\n\ndata: [DONE]\n'
                self._chunks = [body[:32], body[32:], b""]

            def read1(self, _n: int = -1) -> bytes:
                return self._chunks.pop(0) if self._chunks else b""

            def read(self, n: int = -1) -> bytes:
                return self.read1(n)

            def __enter__(self) -> "StreamOkResp":
                return self

            def __exit__(self, *args: object) -> None:
                return None

        with mock.patch.object(pr_review_agent.urllib.request, "urlopen", return_value=ResetResp()):
            with self.assertRaises(RuntimeError) as direct_ctx:
                http_json(
                    "https://example.invalid/v1/chat/completions",
                    deadline=time.monotonic() + 30.0,
                )
        self.assertIn("Transport error", str(direct_ctx.exception))
        self.assertFalse(_is_permanent_http_error(direct_ctx.exception))

        with mock.patch.object(
            pr_review_agent.urllib.request,
            "urlopen",
            side_effect=[ResetResp(), StreamOkResp()],
        ) as mock_urlopen:
            resp = call_ai_chat_completion(
                "https://example.invalid/v1/chat/completions",
                "secret-token",
                {"model": "test-model", "messages": []},
            )
        self.assertEqual(mock_urlopen.call_count, 2)
        self.assertEqual(extract_assistant_content(resp), "Recovered after reset")

        http_401 = urllib.error.HTTPError(
            "https://example.invalid/v1/chat/completions",
            401,
            "Unauthorized",
            {},
            io.BytesIO(b"Bad API key"),
        )
        with mock.patch.object(
            pr_review_agent.urllib.request,
            "urlopen",
            side_effect=http_401,
        ) as mock_urlopen_401:
            with self.assertRaises(RuntimeError) as err_401:
                call_ai_chat_completion(
                    "https://example.invalid/v1/chat/completions",
                    "bad-token",
                    {"model": "test-model", "messages": []},
                )
        self.assertEqual(mock_urlopen_401.call_count, 1)
        self.assertTrue(str(err_401.exception).startswith("HTTP 401 from "))


if __name__ == "__main__":
    unittest.main()
