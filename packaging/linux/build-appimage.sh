#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

RAW_TAG="${1:-${TAG:-}}"
if [[ -z "${RAW_TAG}" ]]; then
    PKG_VER="$(awk -F'"' '/^version = / {print $2; exit}' "${REPO_ROOT}/desktop/Cargo.toml")"
    RAW_TAG="v${PKG_VER:-0.0.1}"
fi

BIN_DIR="${2:-${BIN_DIR:-${REPO_ROOT}/desktop/target/release}}"
OUT_DIR="${3:-${OUT_DIR:-${REPO_ROOT}/dist}}"

for bin in camcraft-gui camcraft-cli; do
    if [[ ! -x "${BIN_DIR}/${bin}" ]]; then
        echo "error: missing release binary '${BIN_DIR}/${bin}'" >&2
        exit 1
    fi
done

APPIMAGETOOL_BIN="${APPIMAGETOOL:-}"
if [[ -z "${APPIMAGETOOL_BIN}" ]]; then
    if command -v appimagetool >/dev/null 2>&1; then
        APPIMAGETOOL_BIN="appimagetool"
    elif command -v appimagetool-x86_64.AppImage >/dev/null 2>&1; then
        APPIMAGETOOL_BIN="appimagetool-x86_64.AppImage"
    else
        echo "error: appimagetool is required to build AppImage (set APPIMAGETOOL or add to PATH)" >&2
        exit 1
    fi
fi

is_excluded_lib() {
    local base
    base="$(basename "$1")"
    case "${base}" in
        libc.so*|libm.so*|libmvec.so*|libdl.so*|libpthread.so*|librt.so*|libresolv.so*|ld-linux*|libgcc_s.so*|libstdc++.so*)
            return 0
            ;;
        libGL*|libEGL*|libGLES*|libGLX*|libdrm*|libwayland*|libX11*|libxcb*|libxkbcommon*|libpipewire*|libpulse*|libasound*|libva*|libvdpau*|libvulkan*|libgbm*)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

extract_ldd_paths() {
    local target="$1"
    ldd "${target}" 2>/dev/null | awk '
        /=> \// { print $3; next }
        /^[[:space:]]*\// { print $1; next }
    ' || true
}

STAGE_DIR="$(mktemp -d)"
trap 'rm -rf "${STAGE_DIR}"' EXIT
APPDIR="${STAGE_DIR}/CamCraft.AppDir"

mkdir -p "${APPDIR}/usr/bin" "${APPDIR}/usr/lib" "${OUT_DIR}"

install -Dm755 "${BIN_DIR}/camcraft-gui" "${APPDIR}/usr/bin/camcraft-gui"
install -Dm755 "${BIN_DIR}/camcraft-cli" "${APPDIR}/usr/bin/camcraft-cli"

install -Dm644 "${REPO_ROOT}/assets/branding/linux/org.artcraft.camcraft.desktop" \
    "${APPDIR}/org.artcraft.camcraft.desktop"
install -Dm644 "${REPO_ROOT}/assets/branding/linux/org.artcraft.camcraft.desktop" \
    "${APPDIR}/usr/share/applications/org.artcraft.camcraft.desktop"
install -Dm644 "${REPO_ROOT}/assets/branding/png/camcraft-app-icon-256.png" \
    "${APPDIR}/org.artcraft.camcraft.png"
install -Dm644 "${REPO_ROOT}/assets/branding/png/camcraft-app-icon-256.png" \
    "${APPDIR}/usr/share/icons/hicolor/256x256/apps/org.artcraft.camcraft.png"
install -Dm644 "${REPO_ROOT}/assets/branding/svg/camcraft-symbol-app-icon.svg" \
    "${APPDIR}/usr/share/icons/hicolor/scalable/apps/org.artcraft.camcraft.svg"
ln -sf "org.artcraft.camcraft.png" "${APPDIR}/.DirIcon"

cat > "${APPDIR}/AppRun" <<'EOF'
#!/bin/sh
set -eu

SELF="$(readlink -f "$0")"
APPDIR="$(dirname "$SELF")"
export LD_LIBRARY_PATH="${APPDIR}/usr/lib:${LD_LIBRARY_PATH:-}"
export XDG_DATA_DIRS="${APPDIR}/usr/share:${XDG_DATA_DIRS:-/usr/local/share:/usr/share}"

ARGV0_BASE="$(basename "${ARGV0:-$0}")"
if [ "${ARGV0_BASE}" = "camcraft-cli" ]; then
    exec "${APPDIR}/usr/bin/camcraft-cli" "$@"
fi

if [ "${1:-}" = "--cli" ]; then
    shift
    exec "${APPDIR}/usr/bin/camcraft-cli" "$@"
fi

exec "${APPDIR}/usr/bin/camcraft-gui" "$@"
EOF
chmod 0755 "${APPDIR}/AppRun"

# Seed queue ONLY with libavcodec.so*, libavutil.so*, libswscale.so* resolved from our binaries,
# then walk transitive dependencies via ldd while strictly excluding core host/driver libraries.
declare -A SEEN=()
QUEUE=()

for bin in "${BIN_DIR}/camcraft-gui" "${BIN_DIR}/camcraft-cli"; do
    while IFS= read -r lib_path; do
        [[ -n "${lib_path}" && -f "${lib_path}" ]] || continue
        base="$(basename "${lib_path}")"
        if [[ "${base}" == libavcodec.so* || "${base}" == libavutil.so* || "${base}" == libswscale.so* ]]; then
            if [[ -z "${SEEN[${base}]+x}" ]]; then
                SEEN["${base}"]=1
                QUEUE+=("${lib_path}")
            fi
        fi
    done < <(extract_ldd_paths "${bin}")
done

idx=0
while (( idx < ${#QUEUE[@]} )); do
    lib_path="${QUEUE[$idx]}"
    idx=$((idx + 1))
    base="$(basename "${lib_path}")"

    if is_excluded_lib "${base}"; then
        continue
    fi

    if [[ ! -e "${APPDIR}/usr/lib/${base}" ]]; then
        cp -L "${lib_path}" "${APPDIR}/usr/lib/${base}"
        chmod 0755 "${APPDIR}/usr/lib/${base}"
    fi

    while IFS= read -r dep_path; do
        [[ -n "${dep_path}" && -f "${dep_path}" ]] || continue
        dep_base="$(basename "${dep_path}")"
        if is_excluded_lib "${dep_base}"; then
            continue
        fi
        if [[ -z "${SEEN[${dep_base}]+x}" ]]; then
            SEEN["${dep_base}"]=1
            QUEUE+=("${dep_path}")
        fi
    done < <(extract_ldd_paths "${lib_path}")
done

OUT_APPIMAGE="${OUT_DIR}/CamCraft-${RAW_TAG}-x86_64.AppImage"
rm -f "${OUT_APPIMAGE}"
ARCH=x86_64 "${APPIMAGETOOL_BIN}" --appimage-extract-and-run "${APPDIR}" "${OUT_APPIMAGE}"
chmod 0755 "${OUT_APPIMAGE}"
echo "Built AppImage: ${OUT_APPIMAGE}"
