# Contributing to CamCraft

Thank you for contributing to **CamCraft**! All contributions—from bug reports and documentation improvements to Rust and Android features—are welcome.

## Development Setup

### Prerequisites
- **Linux Desktop (Fedora recommended):**
  - Rust toolchain (`1.85+` / Rust 2024 edition) via [rustup.rs](https://rustup.rs/)
  - `just` (`sudo dnf install just`)
  - Fedora multimedia & desktop dev libraries: run `./scripts/setup-fedora-dev.sh`
- **Android Client:**
  - JDK 21 (or JDK 17+)
  - Android SDK (API 35) via Android Studio or `sdkmanager`

### Quick Verification Commands
Run all quality checks before opening a Pull Request:

```bash
# Format, lint, and test Rust workspace
just rust-fmt
just rust-check
just rust-lint
just rust-test

# Test and lint Android app
just android-test
just android-lint

# Run all workspace checks together
just check-all
```

## Coding Standards

1. **Cross-Language Wire Protocol (`CCFT v1`):**
   - `desktop/crates/camcraft-protocol` (Rust) and `android/app/src/main/java/org/artcraft/camcraft/protocol` (Kotlin) must remain byte-for-byte compatible.
   - Any protocol change must update `docs/PROTOCOL.md` and pass the shared hex-vector tests on both sides.
2. **Rust (`desktop/`):**
   - Zero `unwrap()`, `expect()`, or `panic!()` in production code paths; use `thiserror` in library crates and `anyhow` in binaries.
   - Avoid per-frame heap allocations inside 60 FPS video/audio hot loops.
   - Any `unsafe` block requires a `// SAFETY: ...` comment explaining upheld invariants.
   - Must pass `cargo fmt --all -- --check` and `cargo clippy --workspace --all-targets -- -D warnings`.
3. **Android Kotlin (`android/`):**
   - Keep Compose UI screens stateless (`CamCraftScreen`, `OledSaverOverlay`); hoist actions and run socket/CameraX/MediaCodec/HTTP operations off `Dispatchers.Main`.
   - Always release `MediaCodec`, `AudioRecord`, CameraX use cases, and sockets cleanly on stop/disconnect.
4. **Changelog & Pull Requests:**
   - Use clear Conventional Commit titles (e.g., `feat(gui): ...`, `fix(decoder): ...`, `docs: ...`).
   - Add user-facing changes under `## [Unreleased]` in `CHANGELOG.md`.
   - Automated CI checks and the AI PR Review Agent run automatically when you open or update a PR.
