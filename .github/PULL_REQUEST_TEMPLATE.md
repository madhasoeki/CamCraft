## Summary
<!-- Briefly describe what this PR changes and why. -->

## Related Issue
<!-- Fixes #(issue) -->

## Scope
- [ ] Desktop Rust (`desktop/`)
- [ ] Android Kotlin (`android/`)
- [ ] Wire Protocol (`docs/PROTOCOL.md` / `camcraft-protocol` / Android `protocol/`)
- [ ] CI/CD or Documentation

## Verification Checklist
- [ ] `just rust-fmt` passes
- [ ] `just rust-lint` (`cargo clippy --workspace --all-targets -- -D warnings`) passes
- [ ] `just rust-test` passes
- [ ] `just android-test` and `just android-lint` pass
- [ ] `CHANGELOG.md` updated under `## [Unreleased]` (if user-facing)
