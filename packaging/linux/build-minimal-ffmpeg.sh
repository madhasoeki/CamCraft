#!/usr/bin/env bash
set -euo pipefail

PREFIX_DIR="${1:-/tmp/camcraft-ffmpeg}"
PRIVLIB_DIR="${2:-/tmp/camcraft-privlib}"
FFMPEG_VERSION="7.1.1"
FFMPEG_URL="https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

echo "Downloading FFmpeg ${FFMPEG_VERSION}..."
curl -fsSL -o "${WORK_DIR}/ffmpeg.tar.xz" "${FFMPEG_URL}"
tar -xJf "${WORK_DIR}/ffmpeg.tar.xz" -C "${WORK_DIR}"

SRC_DIR="${WORK_DIR}/ffmpeg-${FFMPEG_VERSION}"
rm -rf "${PREFIX_DIR}" "${PRIVLIB_DIR}"
mkdir -p "${PREFIX_DIR}" "${PRIVLIB_DIR}"

pushd "${SRC_DIR}" >/dev/null
./configure \
    --prefix="${PREFIX_DIR}" \
    --enable-shared \
    --disable-static \
    --disable-everything \
    --disable-programs \
    --disable-doc \
    --disable-debug \
    --disable-network \
    --disable-autodetect \
    --disable-avdevice \
    --disable-avfilter \
    --disable-swresample \
    --enable-avcodec \
    --enable-avformat \
    --enable-swscale \
    --enable-decoder=h264 \
    --enable-parser=h264

make -j"$(nproc)"
make install
popd >/dev/null

for lib in libavcodec libavutil libswscale; do
    so_path="${PREFIX_DIR}/lib/${lib}.so"
    if [[ ! -e "${so_path}" ]]; then
        echo "error: expected ${so_path} after FFmpeg install" >&2
        exit 1
    fi

    soname="$(readelf -d "${so_path}" | sed -n 's/.*Library soname: \[\([^]]*\)\].*/\1/p' | head -n 1)"
    if [[ -z "${soname}" ]]; then
        echo "error: failed to read SONAME for ${so_path}" >&2
        exit 1
    fi

    dest="${PRIVLIB_DIR}/${soname}"
    cp -L "${so_path}" "${dest}"
    chmod 0755 "${dest}"
    strip --strip-unneeded "${dest}"
    patchelf --set-rpath '$ORIGIN' "${dest}"

    echo "=== ${soname} ($(du -h "${dest}" | cut -f1)) ==="
    needed="$(readelf -d "${dest}" | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')"
    echo "${needed}"
    while IFS= read -r dep; do
        [[ -z "${dep}" ]] && continue
        case "${dep}" in
            libavutil.so.*|libm.so.*|libc.so.*|libpthread.so.*|ld-linux*|libgcc_s.so.*)
                ;;
            *)
                echo "error: unexpected third-party dependency '${dep}' in ${soname}" >&2
                exit 1
                ;;
        esac
    done <<< "${needed}"
done

echo "Minimal LGPL FFmpeg ${FFMPEG_VERSION} staged in ${PRIVLIB_DIR}:"
ls -lh "${PRIVLIB_DIR}"
