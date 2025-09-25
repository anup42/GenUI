#!/usr/bin/env bash
set -euo pipefail

# Builds llama.cpp for Snapdragon 8 Elite class devices (Galaxy Fold 7)
# and stages the binaries under app/src/main so the Android project links
# against the freshly built artifacts. Set LLAMA_VULKAN=OFF to skip Vulkan,
# or BUILD_ELITE_VARIANT=ON to also emit cortex-x4 tuned binaries (copied
# with *_elite suffixes for optional loading).

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
LLAMA_ROOT=${LLAMA_ROOT:-"${ROOT_DIR}/llama.cpp"}
BUILD_TYPE=${BUILD_TYPE:-Release}
ANDROID_API_LEVEL=${ANDROID_API_LEVEL:-34}
ANDROID_ABI=arm64-v8a
BUILD_ROOT=${BUILD_ROOT:-"${ROOT_DIR}/build/android-${ANDROID_ABI}-snapdragon8elite"}

JNI_LIB_DEST="${ROOT_DIR}/app/src/main/jniLibs/${ANDROID_ABI}"
INCLUDE_DEST="${ROOT_DIR}/app/src/main/cpp/llama/include"
CPP_INCLUDE_DEST="${ROOT_DIR}/app/src/main/cpp/llama"

LLAMA_VULKAN=${LLAMA_VULKAN:-OFF}
BUILD_ELITE_VARIANT=${BUILD_ELITE_VARIANT:-OFF}

GENERIC_CPU_FLAGS=${GENERIC_CPU_FLAGS:-"-O3 -DNDEBUG -ffunction-sections -fdata-sections -fomit-frame-pointer -funroll-loops -fPIC -march=armv8.2-a+dotprod+fp16 -ffast-math -fno-math-errno"}
ELITE_CPU_FLAGS=${ELITE_CPU_FLAGS:-"-O3 -DNDEBUG -ffunction-sections -fdata-sections -fomit-frame-pointer -funroll-loops -fPIC -march=armv8.7-a+dotprod+fp16 -ffast-math -fno-math-errno"}

: "${ANDROID_NDK_ROOT:=${ANDROID_NDK_HOME:-${NDK_HOME:-}}}"
if [[ -z "${ANDROID_NDK_ROOT}" || ! -d "${ANDROID_NDK_ROOT}" ]]; then
  echo "error: ANDROID_NDK_ROOT (or ANDROID_NDK_HOME/NDK_HOME) must point to a valid Android NDK" >&2
  exit 1
fi

LLAMA_TAG=${LLAMA_TAG:-b2972}
if [[ ! -d "${LLAMA_ROOT}" ]]; then
  echo "Cloning llama.cpp@${LLAMA_TAG} into ${LLAMA_ROOT}" >&2
  git clone --branch "${LLAMA_TAG}" --depth 1 https://github.com/ggerganov/llama.cpp.git "${LLAMA_ROOT}"
fi

mkdir -p "${BUILD_ROOT}" "${JNI_LIB_DEST}" "${INCLUDE_DEST}" "${CPP_INCLUDE_DEST}"

TOOLCHAIN_FILE="${ANDROID_NDK_ROOT}/build/cmake/android.toolchain.cmake"

uppercase() {
  echo "$1" | tr '[:lower:]' '[:upper:]'
}

build_variant() {
  local variant="$1"
  local cpu_flags="$2"
  local suffix="$3"
  local build_dir="${BUILD_ROOT}/${variant}"

  local extra_cmake_args=()
  if [[ $(uppercase "${LLAMA_VULKAN}") == "ON" ]]; then
    local default_vk_include="${ROOT_DIR}/third_party/Vulkan-Headers/include"
    if [[ ! -d "${default_vk_include}" ]]; then
      default_vk_include="${ANDROID_NDK_ROOT}/sources/third_party/vulkan/src/include"
    fi
    local vk_include="${VULKAN_INCLUDE_DIR:-${default_vk_include}}"
    local vk_library="${VULKAN_LIBRARY:-${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/aarch64-linux-android/${ANDROID_API_LEVEL}/libvulkan.so}"
    extra_cmake_args+=("-DVulkan_INCLUDE_DIR=${vk_include}")
    extra_cmake_args+=("-DVulkan_LIBRARY=${vk_library}")
  fi

  local cmake_args=(
    -G "Ninja"
    -DCMAKE_TOOLCHAIN_FILE="${TOOLCHAIN_FILE}"
    -DANDROID_ABI="${ANDROID_ABI}"
    -DANDROID_PLATFORM=android-${ANDROID_API_LEVEL}
    -DANDROID_STL=c++_static
    -DANDROID_ARM_NEON=ON
    -DCMAKE_BUILD_TYPE=${BUILD_TYPE}
    -DLLAMA_BUILD_TESTS=OFF
    -DLLAMA_BUILD_EXAMPLES=OFF
    -DLLAMA_BUILD_SERVER=OFF
    -DLLAMA_CUBLAS=OFF
    -DLLAMA_CUDA=OFF
    -DBUILD_SHARED_LIBS=ON
    -DLLAMA_VULKAN=${LLAMA_VULKAN}
    -DCMAKE_C_FLAGS_RELEASE="${cpu_flags}"
    -DCMAKE_CXX_FLAGS_RELEASE="${cpu_flags} -fvisibility=hidden -fvisibility-inlines-hidden"
  )
  cmake_args+=("${extra_cmake_args[@]}")

  cmake -S "${LLAMA_ROOT}" -B "${build_dir}" "${cmake_args[@]}"

  local targets=(llama ggml_shared ggml_static common)
  cmake --build "${build_dir}" --target "${targets[@]}"

  local libs=("libllama.so" "libggml_shared.so" "libggml_static.a" "libggml.a" "libcommon.a")
  for lib in "${libs[@]}"; do
    local src="${build_dir}/${lib}"
    [[ -f "${src}" ]] || continue
    local dest_name="${lib}"
    if [[ -n "${suffix}" && "${lib}" == *.so ]]; then
      dest_name="${lib%.so}${suffix}.so"
    elif [[ -n "${suffix}" ]]; then
      dest_name="${lib%.*}${suffix}.${lib##*.}"
    fi
    cp "${src}" "${JNI_LIB_DEST}/${dest_name}"

    if [[ "${lib}" == "libggml_shared.so" ]]; then
      local alias_name="libggml${suffix}.so"
      cp "${src}" "${JNI_LIB_DEST}/${alias_name}"
    fi
  done

  if [[ $(uppercase "${LLAMA_VULKAN}") == "ON" ]]; then
    local vk_src="${build_dir}/libggml-vulkan.so"
    local vk_dest="libggml-vulkan${suffix}.so"
    if [[ -f "${vk_src}" ]]; then
      cp "${vk_src}" "${JNI_LIB_DEST}/${vk_dest}"
    else
      local shared_src="${build_dir}/libggml_shared.so"
      if [[ -f "${shared_src}" ]]; then
        cp "${shared_src}" "${JNI_LIB_DEST}/${vk_dest}"
        echo "warn: ${vk_src##*/} missing; aliased ${shared_src##*/} to ${vk_dest}" >&2
      fi
    fi
  fi
}

# Clean destination so stale binaries do not linger
find "${JNI_LIB_DEST}" -maxdepth 1 -type f \( -name 'libllama*.so' -o -name 'libggml*.so' -o -name 'libggml*.a' -o -name 'libcommon*.a' \) -delete

build_variant "generic" "${GENERIC_CPU_FLAGS}" ""

if [[ $(uppercase "${BUILD_ELITE_VARIANT}") == "ON" ]]; then
  build_variant "elite" "${ELITE_CPU_FLAGS}" "_elite"
fi

if [[ -f "${JNI_LIB_DEST}/libggml_shared.so" ]]; then
  cp "${JNI_LIB_DEST}/libggml_shared.so" "${JNI_LIB_DEST}/libggml.so"
fi

# Copy optional acceleration libraries when present
if [[ $(uppercase "${LLAMA_VULKAN}") == "ON" && -f "${BUILD_ROOT}/generic/libggml-vulkan.so" ]]; then
  cp "${BUILD_ROOT}/generic/libggml-vulkan.so" "${JNI_LIB_DEST}/libggml-vulkan.so"
fi
for optlib in libggml-opencl.so libllava.so libllava_shared.so; do
  if [[ -f "${BUILD_ROOT}/generic/${optlib}" ]]; then
    cp "${BUILD_ROOT}/generic/${optlib}" "${JNI_LIB_DEST}/${optlib}"
  fi
done

echo "Copied shared objects to ${JNI_LIB_DEST}" >&2

# Sync headers that the Android wrapper expects
find "${INCLUDE_DEST}" -mindepth 1 -delete 2>/dev/null || true
mkdir -p "${INCLUDE_DEST}"
if [[ -d "${LLAMA_ROOT}/include" ]]; then
  cp -a "${LLAMA_ROOT}/include/." "${INCLUDE_DEST}/"
else
  find "${LLAMA_ROOT}" -maxdepth 1 -type f -name 'llama*.h' -exec cp {} "${INCLUDE_DEST}/" \;
  find "${LLAMA_ROOT}" -maxdepth 1 -type f -name 'ggml*.h' -exec cp {} "${INCLUDE_DEST}/" \;
fi

rm -rf "${CPP_INCLUDE_DEST}/ggml"
mkdir -p "${CPP_INCLUDE_DEST}/ggml"
if [[ -d "${LLAMA_ROOT}/ggml/include" ]]; then
  cp -a "${LLAMA_ROOT}/ggml/include/." "${CPP_INCLUDE_DEST}/ggml/"
else
  find "${LLAMA_ROOT}" -maxdepth 1 -type f -name 'ggml*.h' -exec cp {} "${CPP_INCLUDE_DEST}/ggml/" \;
fi

rm -rf "${CPP_INCLUDE_DEST}/common"
mkdir -p "${CPP_INCLUDE_DEST}/common"
if [[ -d "${LLAMA_ROOT}/common" ]]; then
  find "${LLAMA_ROOT}/common" -maxdepth 1 -type f -name '*.h' -exec cp {} "${CPP_INCLUDE_DEST}/common/" \;
fi

cat <<EOF
Build complete.
Libraries: ${JNI_LIB_DEST}
Headers : ${INCLUDE_DEST} and ${CPP_INCLUDE_DEST}
Remember to clean and rebuild the Android project for the new binaries to take effect.
EOF


