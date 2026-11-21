import unittest

from pr_review_agent import (
    build_diff_context,
    extract_assistant_content,
    normalize_chat_endpoint,
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


if __name__ == "__main__":
    unittest.main()
