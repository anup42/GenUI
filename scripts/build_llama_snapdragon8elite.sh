#!/usr/bin/env bash
set -euo pipefail

# Builds llama.cpp for Snapdragon 8 Elite class devices (Galaxy Fold 7)
# and drops the libs/headers into the existing `llama cpp Code` folder
# used by the Android project in this repository.

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
LLAMA_ROOT=${LLAMA_ROOT:-"${ROOT_DIR}/llama.cpp"}
BUILD_TYPE=${BUILD_TYPE:-Release}
ANDROID_API_LEVEL=${ANDROID_API_LEVEL:-34}
ANDROID_ABI=arm64-v8a
BUILD_DIR=${BUILD_DIR:-"${ROOT_DIR}/build/android-${ANDROID_ABI}-snapdragon8elite"}
JNI_LIB_DEST="${ROOT_DIR}/llama cpp Code/main/jniLibs/${ANDROID_ABI}"
INCLUDE_DEST="${ROOT_DIR}/llama cpp Code/main/cpp/include"
CPP_INCLUDE_DEST="${ROOT_DIR}/llama cpp Code/main/cpp_includes"

: "${ANDROID_NDK_ROOT:=${ANDROID_NDK_HOME:-${NDK_HOME:-}}}"
if [[ -z "${ANDROID_NDK_ROOT}" || ! -d "${ANDROID_NDK_ROOT}" ]]; then
  echo "error: ANDROID_NDK_ROOT (or ANDROID_NDK_HOME/NDK_HOME) must point to a valid Android NDK" >&2
  exit 1
fi

# Allow choosing a llama.cpp tag/branch; default to upstream master if already cloned
LLAMA_TAG=${LLAMA_TAG:-b2972}
if [[ ! -d "${LLAMA_ROOT}" ]]; then
  echo "Cloning llama.cpp@${LLAMA_TAG} into ${LLAMA_ROOT}" >&2
  git clone --branch "${LLAMA_TAG}" --depth 1 https://github.com/ggerganov/llama.cpp.git "${LLAMA_ROOT}"
fi

mkdir -p "${BUILD_DIR}" "${JNI_LIB_DEST}" "${INCLUDE_DEST}" "${CPP_INCLUDE_DEST}"

TOOLCHAIN_FILE="${ANDROID_NDK_ROOT}/build/cmake/android.toolchain.cmake"

# Snapdragon 8 Elite cores are Cortex-X4/A720/A520 blend.
# Force the prime core profile for math heavy workloads while keeping portability.
SNAPDRAGON_CPU_FLAGS="-O3 -DNDEBUG -ffunction-sections -fdata-sections -fomit-frame-pointer \
 -funroll-loops -fno-exceptions -fno-rtti -fPIC -mcpu=cortex-x4 -mtune=cortex-x4 -march=armv9-a+dotprod+fp16 \
 -ffast-math -fno-math-errno"

if ! command -v ninja >/dev/null 2>&1; then
  echo "error: ninja (from https://github.com/ninja-build/ninja) is required" >&2
  exit 1
fi

cmake -S "${LLAMA_ROOT}" -B "${BUILD_DIR}" \
  -G "Ninja" \
  -DCMAKE_TOOLCHAIN_FILE="${TOOLCHAIN_FILE}" \
  -DANDROID_ABI="${ANDROID_ABI}" \
  -DANDROID_PLATFORM=android-${ANDROID_API_LEVEL} \
  -DANDROID_STL=c++_static \
  -DANDROID_ARM_NEON=ON \
  -DCMAKE_BUILD_TYPE=${BUILD_TYPE} \
  -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_COMMON=ON \
  -DLLAMA_BUILD_SHARED_LIB=ON \
  -DLLAMA_CUBLAS=OFF \
  -DGGML_CPU=ON \
  -DGGML_NATIVE=ON \
  -DGGML_OPENMP=OFF \
  -DGGML_ACCELERATE=OFF \
  -DGGML_VULKAN=${GGML_VULKAN:-OFF} \
  -DCMAKE_C_FLAGS_RELEASE="${SNAPDRAGON_CPU_FLAGS}" \
  -DCMAKE_CXX_FLAGS_RELEASE="${SNAPDRAGON_CPU_FLAGS} -fvisibility=hidden -fvisibility-inlines-hidden"

cmake --build "${BUILD_DIR}" --target llama ggml ggml-base common mtmd

LIB_SRC_DIR="${BUILD_DIR}/bin"
if [[ ! -d "${LIB_SRC_DIR}" ]]; then
  echo "error: expected build output in ${LIB_SRC_DIR}" >&2
  exit 1
fi

for lib in libllama.so libggml.so libggml-base.so libcommon.a libmtmd.so; do
  if [[ -f "${LIB_SRC_DIR}/${lib}" ]]; then
    cp "${LIB_SRC_DIR}/${lib}" "${JNI_LIB_DEST}/"
  fi
done

# Copy optional acceleration libraries when present
for optlib in libggml-vulkan.so libggml-opencl.so libllava.so libllava_shared.so; do
  if [[ -f "${LIB_SRC_DIR}/${optlib}" ]]; then
    cp "${LIB_SRC_DIR}/${optlib}" "${JNI_LIB_DEST}/"
  fi
done

echo "Copied shared objects to ${JNI_LIB_DEST}" >&2

# Sync headers that the Android wrapper expects
find "${INCLUDE_DEST}" -mindepth 1 -delete 2>/dev/null || true
cp -a "${LLAMA_ROOT}/include/." "${INCLUDE_DEST}/"
rm -rf "${CPP_INCLUDE_DEST}/ggml"
mkdir -p "${CPP_INCLUDE_DEST}/ggml"
cp -a "${LLAMA_ROOT}/ggml/include/." "${CPP_INCLUDE_DEST}/ggml/"
rm -rf "${CPP_INCLUDE_DEST}/common"
mkdir -p "${CPP_INCLUDE_DEST}/common"
cp -a "${LLAMA_ROOT}/common/." "${CPP_INCLUDE_DEST}/common/"

cat <<EOF
Build complete.
Libraries: ${JNI_LIB_DEST}
Headers : ${INCLUDE_DEST} and ${CPP_INCLUDE_DEST}
Remember to clean and rebuild the Android project for the new binaries to take effect.
EOF


