#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

RAW_VERSION="${1:-${VERSION:-}}"
if [[ -z "${RAW_VERSION}" ]]; then
    RAW_VERSION="$(awk -F'"' '/^version = / {print $2; exit}' "${REPO_ROOT}/desktop/Cargo.toml")"
fi
RAW_VERSION="${RAW_VERSION:-0.0.1}"
RAW_VERSION="${RAW_VERSION#v}"
RAW_VERSION="${RAW_VERSION#V}"

# Debian policy treats '-' as upstream-debian revision separator; map prerelease '-' to '~'.
DEB_VERSION="${RAW_VERSION//-/\~}"
BIN_DIR="${2:-${BIN_DIR:-${REPO_ROOT}/desktop/target/release}}"
OUT_DIR="${3:-${OUT_DIR:-${REPO_ROOT}/dist}}"
PRIVLIB_DIR="${4:-${PRIVLIB_DIR:-}}"

for bin in camcraft-gui camcraft-cli; do
    if [[ ! -x "${BIN_DIR}/${bin}" ]]; then
        echo "error: missing release binary '${BIN_DIR}/${bin}'" >&2
        exit 1
    fi
done

if ! command -v dpkg-deb >/dev/null 2>&1; then
    echo "error: dpkg-deb is required to build .deb packages" >&2
    exit 1
fi

STAGE_DIR="$(mktemp -d)"
trap 'rm -rf "${STAGE_DIR}"' EXIT
PKG_ROOT="${STAGE_DIR}/camcraft_${RAW_VERSION}_amd64"

mkdir -p "${PKG_ROOT}/DEBIAN" "${OUT_DIR}"

install -Dm755 "${BIN_DIR}/camcraft-gui" "${PKG_ROOT}/usr/bin/camcraft-gui"
install -Dm755 "${BIN_DIR}/camcraft-cli" "${PKG_ROOT}/usr/bin/camcraft-cli"

if [[ -n "${PRIVLIB_DIR}" ]]; then
    if [[ ! -d "${PRIVLIB_DIR}" ]]; then
        echo "error: missing private FFmpeg library directory '${PRIVLIB_DIR}'" >&2
        exit 1
    fi
    install -d "${PKG_ROOT}/usr/lib/camcraft"
    shopt -s nullglob
    privlibs=("${PRIVLIB_DIR}"/lib*.so*)
    shopt -u nullglob
    if [[ "${#privlibs[@]}" -eq 0 ]]; then
        echo "error: no shared libraries found in '${PRIVLIB_DIR}'" >&2
        exit 1
    fi
    for lib_file in "${privlibs[@]}"; do
        install -Dm755 "${lib_file}" "${PKG_ROOT}/usr/lib/camcraft/$(basename "${lib_file}")"
    done
    DEB_DEPENDS="libc6 (>= 2.35), pulseaudio-utils | pipewire-pulse"
else
    DEB_DEPENDS="libc6, libavcodec58 | libavcodec59 | libavcodec60 | libavcodec61 | libavcodec62, libavutil56 | libavutil57 | libavutil58 | libavutil59 | libavutil60, libswscale5 | libswscale6 | libswscale7 | libswscale8 | libswscale9, pulseaudio-utils | pipewire-pulse"
fi

install -Dm644 "${REPO_ROOT}/assets/branding/linux/org.artcraft.camcraft.desktop" \
    "${PKG_ROOT}/usr/share/applications/org.artcraft.camcraft.desktop"
install -Dm644 "${REPO_ROOT}/assets/branding/svg/camcraft-symbol-app-icon.svg" \
    "${PKG_ROOT}/usr/share/icons/hicolor/scalable/apps/org.artcraft.camcraft.svg"
install -Dm644 "${REPO_ROOT}/assets/branding/png/camcraft-app-icon-256.png" \
    "${PKG_ROOT}/usr/share/icons/hicolor/256x256/apps/org.artcraft.camcraft.png"
install -Dm644 "${REPO_ROOT}/packaging/linux/70-camcraft-aoa.rules" \
    "${PKG_ROOT}/lib/udev/rules.d/70-camcraft-aoa.rules"

install -Dm644 "${REPO_ROOT}/README.md" "${PKG_ROOT}/usr/share/doc/camcraft/README.md"
{
    echo "Format: https://www.debian.org/doc/packaging-manuals/copyright-format/1.0/"
    echo "Upstream-Name: CamCraft"
    echo "Source: https://github.com/madhasoeki/CamCraft"
    echo ""
    echo "Files: *"
    echo "Copyright: 2026 ArtCraft / CamCraft Contributors"
    echo "License: MIT or Apache-2.0"
    echo ""
    cat "${REPO_ROOT}/LICENSE-MIT"
    echo ""
    cat "${REPO_ROOT}/LICENSE-APACHE"
} > "${PKG_ROOT}/usr/share/doc/camcraft/copyright"
chmod 0644 "${PKG_ROOT}/usr/share/doc/camcraft/copyright"

INSTALLED_SIZE="$(du -sk "${PKG_ROOT}/usr" "${PKG_ROOT}/lib" | awk '{sum += $1} END {print sum}')"

cat > "${PKG_ROOT}/DEBIAN/control" <<EOF
Package: camcraft
Version: ${DEB_VERSION}
Section: video
Priority: optional
Architecture: amd64
Maintainer: ArtCraft <maintainers@artcraft.org>
Homepage: https://github.com/madhasoeki/CamCraft
Installed-Size: ${INSTALLED_SIZE}
Depends: ${DEB_DEPENDS}
Recommends: pipewire, libpipewire-0.3-0 | libpipewire-0.3-0t64
Suggests: v4l2loopback-dkms
Description: Low-latency Android webcam and virtual microphone receiver for Linux
 CamCraft turns an Android smartphone into a local, ultra-low-latency
 1080p-4K 60fps virtual webcam and virtual microphone on Linux over USB
 (ADB / AOA) or Wi-Fi LAN.
EOF
chmod 0644 "${PKG_ROOT}/DEBIAN/control"

cat > "${PKG_ROOT}/DEBIAN/postinst" <<'EOF'
#!/bin/sh
set -e
if command -v udevadm >/dev/null 2>&1; then
    udevadm control --reload-rules >/dev/null 2>&1 || true
    udevadm trigger --subsystem-match=usb >/dev/null 2>&1 || true
fi
if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database -q /usr/share/applications >/dev/null 2>&1 || true
fi
if command -v gtk-update-icon-cache >/dev/null 2>&1; then
    gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor >/dev/null 2>&1 || true
fi
exit 0
EOF

cat > "${PKG_ROOT}/DEBIAN/postrm" <<'EOF'
#!/bin/sh
set -e
if command -v udevadm >/dev/null 2>&1; then
    udevadm control --reload-rules >/dev/null 2>&1 || true
fi
if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database -q /usr/share/applications >/dev/null 2>&1 || true
fi
if command -v gtk-update-icon-cache >/dev/null 2>&1; then
    gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor >/dev/null 2>&1 || true
fi
exit 0
EOF

chmod 0755 "${PKG_ROOT}/DEBIAN" "${PKG_ROOT}/DEBIAN/postinst" "${PKG_ROOT}/DEBIAN/postrm"

OUT_DEB="${OUT_DIR}/camcraft_${RAW_VERSION}_amd64.deb"
dpkg-deb --root-owner-group --build "${PKG_ROOT}" "${OUT_DEB}"
echo "Built Debian package: ${OUT_DEB}"
