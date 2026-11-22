#!/usr/bin/env python3
"""Lightweight AI PR Review Agent for CamCraft.

Configured purely via GitHub Repository Secrets:
  - AI_API_ENDPOINT (OpenAI-compatible base URL or full /chat/completions URL)
  - AI_API_KEY      (API key / Bearer token)
  - AI_MODEL        (Model identifier string)
"""

from __future__ import annotations

import http.client
import json
import os
import sys
import time
import urllib.error
import urllib.request
from typing import Any, Callable

COMMENT_MARKER = "<!-- camcraft-pr-agent -->"
MAX_DIFF_CHARS = 60_000
MAX_FILE_PATCH_CHARS = 12_000

SKIP_EXACT_NAMES = {
    "Cargo.lock",
    "desktop/Cargo.lock",
    "skills-lock.json",
    "package-lock.json",
    "yarn.lock",
    "pnpm-lock.yaml",
    "android/gradle/wrapper/gradle-wrapper.jar",
}

SKIP_EXTENSIONS = (
    ".png",
    ".jpg",
    ".jpeg",
    ".webp",
    ".gif",
    ".ico",
    ".svg",
    ".jar",
    ".apk",
    ".aab",
    ".so",
    ".keystore",
    ".jks",
    ".pdf",
    ".zip",
    ".tar.gz",
    ".gz",
    ".woff",
    ".woff2",
    ".ttf",
)

SYSTEM_PROMPT = """You are a senior Rust, Android Kotlin, and Linux multimedia systems engineer reviewing a Pull Request for CamCraft.

CamCraft Architecture & Standards:
1. Monorepo with Linux desktop receiver (`desktop/`: Rust 2024, tokio, ffmpeg-next, v4l2loopback, PipeWire, eframe/egui) and Android sender (`android/`: Kotlin, Jetpack Compose, CameraX, MediaCodec H.264 surface encoding, AudioRecord PCM, NsdManager mDNS).
2. Wire protocol (`CCFT v1`): 20-byte big-endian binary header + JSON control messages shared 1:1 between `camcraft-protocol` (Rust) and `org.artcraft.camcraft.protocol` (Kotlin). Watch for wire compatibility breaks.
3. Rust rules: ZERO `unwrap()` / `expect()` / `panic!()` in production paths; zero per-frame allocations in 60fps hot loops; explicit `// SAFETY: ...` comments on any `unsafe` blocks.
4. Android rules: MVVM / stateless Compose UI; zero blocking network/socket/MediaCodec work on `Dispatchers.Main`; clean `release()` / `close()` lifecycle cleanup.
5. Security & reliability: safe path/archive handling, checksum verification, no leaked secrets.

Produce a concise, high-signal code review in Markdown with sections:
### Summary
### Potential Bugs & Risks
### Protocol / Architecture / Performance Notes
### Suggested Improvements
### Verification Checklist

Only reference files, symbols, and behavior visible in the provided PR metadata and diff. If a section has no issues, keep it brief or state "None found."."""


def normalize_chat_endpoint(endpoint: str) -> str:
    clean = endpoint.strip().rstrip("/")
    if not clean:
        raise ValueError("AI_API_ENDPOINT must not be empty")
    if clean.endswith("/chat/completions"):
        return clean
    return f"{clean}/chat/completions"


def should_skip_file(filename: str) -> bool:
    if filename in SKIP_EXACT_NAMES or filename.rsplit("/", 1)[-1] in {"Cargo.lock", "package-lock.json", "yarn.lock", "pnpm-lock.yaml"}:
        return True
    lower = filename.lower()
    return lower.endswith(SKIP_EXTENSIONS)


def build_diff_context(
    files: list[dict[str, Any]],
    max_chars: int = MAX_DIFF_CHARS,
    max_file_chars: int = MAX_FILE_PATCH_CHARS,
) -> tuple[str, list[str]]:
    chunks: list[str] = []
    omitted: list[str] = []
    used = 0

    for entry in files:
        filename = str(entry.get("filename", "<unknown>"))
        status = str(entry.get("status", "modified"))
        additions = int(entry.get("additions", 0))
        deletions = int(entry.get("deletions", 0))

        if should_skip_file(filename):
            omitted.append(f"{filename} (skipped lockfile/binary)")
            continue

        patch = entry.get("patch")
        if patch is None:
            section = f"## File: `{filename}` ({status}, +{additions}/-{deletions})\n[Diff omitted by GitHub API (binary or too large)]\n"
        else:
            patch_str = str(patch)
            if len(patch_str) > max_file_chars:
                patch_str = patch_str[:max_file_chars] + "\n... [file diff truncated]"
            section = (
                f"## File: `{filename}` ({status}, +{additions}/-{deletions})\n"
                f"```diff\n{patch_str}\n```\n"
            )

        if used + len(section) > max_chars:
            omitted.append(f"{filename} (budget exceeded)")
            continue

        chunks.append(section)
        used += len(section)

    return "\n".join(chunks), omitted


def extract_assistant_content(payload: dict[str, Any]) -> str:
    choices = payload.get("choices")
    if not isinstance(choices, list) or not choices:
        raise ValueError("AI response missing non-empty 'choices' array")

    message = choices[0].get("message")
    if not isinstance(message, dict):
        raise ValueError("AI response choice missing 'message' object")

    content = message.get("content")
    if isinstance(content, str):
        text = content.strip()
        if text:
            return text
        raise ValueError("AI response returned empty text content")

    if isinstance(content, list):
        parts: list[str] = []
        for item in content:
            if isinstance(item, dict) and isinstance(item.get("text"), str):
                parts.append(item["text"])
        joined = "\n".join(parts).strip()
        if joined:
            return joined

    raise ValueError("AI response content could not be extracted")


def _extract_chunk_text(msg: dict[str, Any]) -> str:
    content = msg.get("content")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts: list[str] = []
        for item in content:
            if isinstance(item, dict) and isinstance(item.get("text"), str):
                parts.append(item["text"])
        return "".join(parts)
    return ""


def parse_ai_response_body(raw: str) -> dict[str, Any]:
    stripped = raw.strip()
    if not stripped:
        raise ValueError("Empty response body")

    if stripped.startswith(("{", "[")):
        try:
            parsed = json.loads(stripped)
            if isinstance(parsed, dict):
                return parsed
        except json.JSONDecodeError:
            pass

    # Strip leading SSE keep-alive comment lines (e.g. `: OPENROUTER PROCESSING`) before JSON.
    non_comment = "\n".join(
        line
        for line in stripped.splitlines()
        if line.strip() and not line.lstrip().startswith(":")
    ).strip()
    if non_comment.startswith("{"):
        try:
            parsed = json.loads(non_comment)
            if isinstance(parsed, dict):
                return parsed
        except json.JSONDecodeError:
            pass

    # Assemble Server-Sent Events (`data: {...}`) streams when gateways return SSE.
    sse_parts: list[str] = []
    event_data_lines: list[str] = []
    saw_data = False
    last_error: str | None = None

    def flush_event() -> None:
        nonlocal saw_data, last_error
        if not event_data_lines:
            return
        saw_data = True
        payload_str = "\n".join(event_data_lines).strip()
        event_data_lines.clear()
        if not payload_str or payload_str == "[DONE]":
            return
        try:
            chunk = json.loads(payload_str)
        except json.JSONDecodeError:
            return
        if not isinstance(chunk, dict):
            return
        if chunk.get("error"):
            last_error = json.dumps(chunk["error"])
        choices = chunk.get("choices")
        if isinstance(choices, list) and choices and isinstance(choices[0], dict):
            first = choices[0]
            for key in ("delta", "message"):
                msg = first.get(key)
                if isinstance(msg, dict):
                    text = _extract_chunk_text(msg)
                    if text:
                        sse_parts.append(text)

    for raw_line in stripped.splitlines():
        line_clean = raw_line.rstrip("\r\n")
        if not line_clean.strip():
            flush_event()
            continue
        stripped_left = line_clean.lstrip()
        if stripped_left.startswith(":"):
            continue
        if stripped_left.startswith("data:"):
            val = stripped_left[5:]
            if val.startswith(" "):
                val = val[1:]
            event_data_lines.append(val)

    flush_event()

    if last_error:
        raise ValueError(f"AI stream returned error: {last_error[:200]}")
    if sse_parts:
        return {"choices": [{"message": {"content": "".join(sse_parts)}}]}
    if saw_data:
        raise ValueError("AI SSE stream returned no text content")

    preview = " ".join(stripped.split())[:200]
    raise ValueError(f"Unrecognized non-JSON response: {preview}")


def _set_response_socket_timeout(resp: Any, sock_timeout: float) -> None:
    cur: Any = resp
    visited: set[int] = set()
    for _ in range(4):
        if cur is None or id(cur) in visited:
            break
        visited.add(id(cur))
        raw_cur = getattr(cur, "raw", None)
        for owner in (cur, raw_cur):
            sock = getattr(owner, "_sock", None)
            if sock is not None:
                try:
                    sock.settimeout(sock_timeout)
                except (AttributeError, OSError):
                    pass
                return
        cur = getattr(cur, "fp", None)


def _get_header(headers: Any, name: str) -> str | None:
    if headers is None:
        return None
    target = name.lower()
    if hasattr(headers, "items"):
        values = [str(v) for k, v in headers.items() if str(k).lower() == target]
        if values:
            return ", ".join(values)
    getter = getattr(headers, "get", None)
    if callable(getter):
        val = getter(name)
        return None if val is None else str(val)
    return None


def _parse_content_length(resp: Any) -> int | None:
    status = getattr(resp, "status", None)
    if isinstance(status, int) and (status in (204, 304) or 100 <= status < 200):
        return None
    if getattr(resp, "chunked", False):
        return None
    headers = getattr(resp, "headers", None)
    transfer_encoding = _get_header(headers, "Transfer-Encoding")
    if transfer_encoding and "chunked" in transfer_encoding.lower():
        return None
    raw_len = _get_header(headers, "Content-Length")
    if raw_len is None:
        return None
    parts = {part.strip() for part in raw_len.split(",") if part.strip()}
    if len(parts) != 1:
        return None
    try:
        parsed = int(next(iter(parts)))
    except ValueError:
        return None
    return parsed if parsed >= 0 else None


def _read_response_bytes(
    resp: Any,
    *,
    timeout: float,
    deadline: float | None,
    clean_url: str,
    max_bytes: int | None = None,
    allow_partial_on_deadline: bool = False,
) -> bytes:
    chunks: list[bytes] = []
    total_read = 0
    read_fn = getattr(resp, "read1", resp.read)
    declared_len = None if allow_partial_on_deadline else _parse_content_length(resp)

    while True:
        if max_bytes is not None:
            to_read = min(65536, max_bytes - total_read)
            if to_read <= 0:
                break
        else:
            to_read = 65536

        if deadline is not None:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                if allow_partial_on_deadline:
                    break
                raise RuntimeError(f"AI stage total timeout exceeded reading from {clean_url}")
            _set_response_socket_timeout(resp, min(float(timeout), remaining))

        try:
            block = read_fn(to_read)
        except http.client.IncompleteRead as inc_err:
            if allow_partial_on_deadline:
                if inc_err.partial:
                    remaining_cap = None if max_bytes is None else max(0, max_bytes - total_read)
                    chunks.append(
                        inc_err.partial if remaining_cap is None else inc_err.partial[:remaining_cap]
                    )
                break
            raise RuntimeError(
                f"Incomplete response body from {clean_url}: "
                f"read {total_read + len(inc_err.partial)} bytes before premature EOF"
            ) from None
        except (TimeoutError, OSError, http.client.HTTPException):
            if allow_partial_on_deadline:
                break
            raise

        if not block:
            if not allow_partial_on_deadline:
                if declared_len is not None and total_read < declared_len:
                    raise RuntimeError(
                        f"Incomplete response body from {clean_url}: "
                        f"read {total_read} of {declared_len} bytes"
                    )
                if declared_len is None and not getattr(resp, "chunked", False):
                    unread_len = getattr(resp, "length", None)
                    if isinstance(unread_len, int) and unread_len > 0:
                        raise RuntimeError(
                            f"Incomplete response body from {clean_url}: "
                            f"{unread_len} unread bytes remaining"
                        )
            break

        chunks.append(block)
        total_read += len(block)

    return b"".join(chunks)


def http_json(
    url: str,
    method: str = "GET",
    headers: dict[str, str] | None = None,
    body: dict[str, Any] | None = None,
    timeout: float = 90.0,
    deadline: float | None = None,
) -> tuple[int, Any]:
    clean_url = url.split("?")[0]
    eff_timeout = float(timeout)
    if deadline is not None:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise RuntimeError(f"AI stage total timeout exceeded before calling {clean_url}")
        eff_timeout = min(eff_timeout, remaining)

    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method)
    req_headers = dict(headers or {})
    if not any(k.lower() == "accept" for k in req_headers):
        req_headers["Accept"] = "application/json"
    for k, v in req_headers.items():
        req.add_header(k, v)
    if data is not None:
        req.add_header("Content-Type", "application/json")

    try:
        with urllib.request.urlopen(req, timeout=eff_timeout) as resp:
            raw = _read_response_bytes(
                resp,
                timeout=timeout,
                deadline=deadline,
                clean_url=clean_url,
            ).decode("utf-8", errors="replace")

            content_type = resp.headers.get("Content-Type", "unknown") if resp.headers else "unknown"
            if not raw.strip():
                return resp.status, {}
            try:
                parsed = json.loads(raw)
            except json.JSONDecodeError:
                try:
                    parsed = parse_ai_response_body(raw)
                except ValueError as parse_err:
                    err_text = str(parse_err)
                    detail = err_text if err_text.startswith("AI stream returned error:") else " ".join(raw.split())[:200]
                    # Never surface request headers (e.g. API keys) in exceptions.
                    raise RuntimeError(
                        f"Non-JSON response (HTTP {resp.status}, Content-Type: {content_type}) "
                        f"from {clean_url}: {detail}"
                    ) from None
            return resp.status, parsed
    except urllib.error.HTTPError as err:
        try:
            raw_err = _read_response_bytes(
                err,
                timeout=timeout,
                deadline=deadline,
                clean_url=clean_url,
                max_bytes=65536,
                allow_partial_on_deadline=True,
            ).decode("utf-8", errors="replace")
        finally:
            try:
                err.close()
            except OSError:
                pass
        # Never surface request headers (e.g. API keys) in exceptions.
        snippet = raw_err.strip()[:400]
        raise RuntimeError(f"HTTP {err.code} from {clean_url}: {snippet}") from None
    except urllib.error.URLError as err:
        raise RuntimeError(f"Network error calling {clean_url}: {err.reason}") from None
    except TimeoutError as err:
        raise RuntimeError(f"Request timed out calling {clean_url}: {err}") from None
    except http.client.HTTPException as err:
        raise RuntimeError(f"HTTP protocol error calling {clean_url}: {err}") from None


def _is_permanent_http_error(err: Exception) -> bool:
    msg = str(err)
    return msg.startswith(("HTTP 400", "HTTP 401", "HTTP 403", "HTTP 404", "HTTP 422"))


def call_ai_chat_completion(
    chat_url: str,
    api_key: str,
    payload: dict[str, Any],
    timeout: float = 90.0,
    total_timeout: float = 180.0,
    requester: Callable[..., tuple[int, Any]] = http_json,
) -> dict[str, Any]:
    deadline = time.monotonic() + total_timeout

    def _invoke(req_headers: dict[str, str], req_body: dict[str, Any]) -> dict[str, Any]:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise RuntimeError(f"AI stage total timeout ({total_timeout:.0f}s) exceeded")
        step_timeout = min(float(timeout), remaining)
        kwargs: dict[str, Any] = {
            "method": "POST",
            "headers": req_headers,
            "body": req_body,
            "timeout": step_timeout,
        }
        if requester is http_json:
            kwargs["deadline"] = deadline
        _, resp = requester(chat_url, **kwargs)
        return resp

    non_stream_headers = {
        "Authorization": f"Bearer {api_key}",
        "Accept": "application/json",
        "User-Agent": "CamCraft-PR-Agent/1.0",
    }
    non_stream_payload = {**payload, "stream": False}
    try:
        return _invoke(non_stream_headers, non_stream_payload)
    except RuntimeError as first_err:
        if _is_permanent_http_error(first_err):
            raise
        remaining_after_first = deadline - time.monotonic()
        if remaining_after_first <= 1.0:
            raise RuntimeError(
                f"AI completion exceeded total timeout ({total_timeout:.0f}s) after failure: {first_err}"
            ) from None
        print(
            f"Non-streaming completion attempt failed ({first_err}); retrying with SSE stream=true...",
            flush=True,
        )
        stream_headers = {
            "Authorization": f"Bearer {api_key}",
            "Accept": "text/event-stream, application/json",
            "User-Agent": "CamCraft-PR-Agent/1.0",
        }
        stream_payload = {**payload, "stream": True}
        return _invoke(stream_headers, stream_payload)


def github_headers(token: str) -> dict[str, str]:
    return {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "CamCraft-PR-Agent/1.0",
    }


def fetch_pr_files(api_base: str, repo: str, pr_number: str, token: str) -> list[dict[str, Any]]:
    all_files: list[dict[str, Any]] = []
    page = 1
    while True:
        url = f"{api_base}/repos/{repo}/pulls/{pr_number}/files?per_page=100&page={page}"
        _, batch = http_json(url, headers=github_headers(token), timeout=20)
        if not isinstance(batch, list) or not batch:
            break
        all_files.extend(batch)
        if len(batch) < 100 or page >= 5:
            break
        page += 1
    return all_files


def upsert_pr_comment(
    api_base: str,
    repo: str,
    pr_number: str,
    token: str,
    body_markdown: str,
) -> str:
    existing_id: int | None = None
    page = 1
    while True:
        url = f"{api_base}/repos/{repo}/issues/{pr_number}/comments?per_page=100&page={page}"
        _, comments = http_json(url, headers=github_headers(token), timeout=20)
        if not isinstance(comments, list) or not comments:
            break
        for comment in comments:
            if COMMENT_MARKER in str(comment.get("body", "")):
                existing_id = int(comment["id"])
                break
        if existing_id is not None or len(comments) < 100 or page >= 5:
            break
        page += 1

    if existing_id is not None:
        patch_url = f"{api_base}/repos/{repo}/issues/comments/{existing_id}"
        http_json(
            patch_url,
            method="PATCH",
            headers=github_headers(token),
            body={"body": body_markdown},
            timeout=20,
        )
        return f"updated comment {existing_id}"

    post_url = f"{api_base}/repos/{repo}/issues/{pr_number}/comments"
    _, created = http_json(
        post_url,
        method="POST",
        headers=github_headers(token),
        body={"body": body_markdown},
        timeout=20,
    )
    return f"created comment {created.get('id', '?')}"


def run_review(env: dict[str, str] | None = None) -> int:
    source = os.environ if env is None else env

    endpoint = source.get("AI_API_ENDPOINT", "").strip()
    api_key = source.get("AI_API_KEY", "").strip()
    model = source.get("AI_MODEL", "").strip()

    if not endpoint or not api_key or not model:
        missing = [
            name
            for name, val in (
                ("AI_API_ENDPOINT", endpoint),
                ("AI_API_KEY", api_key),
                ("AI_MODEL", model),
            )
            if not val
        ]
        print(
            f"PR Agent skipped: missing secret(s): {', '.join(missing)}. "
            "Configure AI_API_ENDPOINT, AI_API_KEY, and AI_MODEL in GitHub Repository Secrets.",
            file=sys.stderr,
        )
        return 0

    gh_token = source.get("GITHUB_TOKEN", "").strip()
    repo = source.get("GITHUB_REPOSITORY", "").strip()
    pr_number = source.get("PR_NUMBER", "").strip()
    gh_api = source.get("GITHUB_API_URL", "https://api.github.com").strip().rstrip("/")

    if not gh_token or not repo or not pr_number:
        print(
            "Error: GITHUB_TOKEN, GITHUB_REPOSITORY, and PR_NUMBER must be set.",
            file=sys.stderr,
        )
        return 1

    comment_body = source.get("ISSUE_COMMENT_BODY", "")
    if source.get("GITHUB_EVENT_NAME") == "issue_comment" and comment_body.strip() != "/review":
        print("Ignoring issue comment because trimmed body is not '/review'.")
        return 0

    pr_url = f"{gh_api}/repos/{repo}/pulls/{pr_number}"
    _, pr_data = http_json(pr_url, headers=github_headers(gh_token), timeout=20)
    pr_title = str(pr_data.get("title", "")).strip()
    pr_body = str(pr_data.get("body") or "").strip()
    base_ref = str(pr_data.get("base", {}).get("ref", "main"))
    head_ref = str(pr_data.get("head", {}).get("ref", ""))
    head_sha = str(pr_data.get("head", {}).get("sha", ""))
    short_sha = head_sha[:7] if head_sha else "unknown"

    files = fetch_pr_files(gh_api, repo, pr_number, gh_token)
    diff_text, omitted = build_diff_context(files)
    if not diff_text.strip():
        diff_text = "[All changed files were lockfiles, binaries, or lacked textual patches.]"

    omitted_note = ""
    if omitted:
        omitted_note = "\nOmitted files:\n" + "\n".join(f"- {item}" for item in omitted[:40])

    user_prompt = (
        f"Pull Request #{pr_number}: {pr_title}\n"
        f"Branches: `{head_ref}` -> `{base_ref}` (commit `{short_sha}`)\n\n"
        f"PR Description:\n{pr_body or '(no description provided)'}\n\n"
        f"Changed Files ({len(files)} total):{omitted_note}\n\n"
        f"{diff_text}"
    )

    chat_url = normalize_chat_endpoint(endpoint)
    ai_payload = {
        "model": model,
        "temperature": 0.2,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user_prompt},
        ],
    }

    print(f"Sending PR #{pr_number} diff ({len(files)} files) to model `{model}`...", flush=True)
    ai_resp = call_ai_chat_completion(chat_url, api_key, ai_payload, timeout=90, total_timeout=180.0)
    review_text = extract_assistant_content(ai_resp)

    final_comment = (
        f"{COMMENT_MARKER}\n"
        f"## CamCraft AI PR Review\n\n"
        f"{review_text}\n\n"
        f"---\n"
        f"*Reviewed by **CamCraft PR Agent** (`{model}`) at commit `{short_sha}`. "
        f"Maintainers can comment `/review` to re-run this review.*"
    )

    action = upsert_pr_comment(gh_api, repo, pr_number, gh_token, final_comment)
    print(f"PR #{pr_number} review posted ({action}).", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(run_review())
