cmake_minimum_required(VERSION 3.22.1)
project(QwenCoder LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

set(_LLAMA_BASE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../llama cpp Code/main")
file(TO_CMAKE_PATH "${_LLAMA_BASE_DIR}" LLAMA_BASE_DIR)
set(CPP_INCLUDES_DIR "${LLAMA_BASE_DIR}/cpp_includes")
set(JNI_LIBS_DIR "${LLAMA_BASE_DIR}/jniLibs/${ANDROID_ABI}")

if (NOT EXISTS "${JNI_LIBS_DIR}/libllama.so")
    message(FATAL_ERROR "Expected prebuilt llama.cpp libraries in ${JNI_LIBS_DIR}")
endif()

include_directories("${CPP_INCLUDES_DIR}")

add_library(ggml SHARED IMPORTED)
set_target_properties(ggml PROPERTIES IMPORTED_LOCATION "${JNI_LIBS_DIR}/libggml.so")

add_library(ggml_base SHARED IMPORTED)
set_target_properties(ggml_base PROPERTIES IMPORTED_LOCATION "${JNI_LIBS_DIR}/libggml-base.so")

add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES IMPORTED_LOCATION "${JNI_LIBS_DIR}/libllama.so")

add_library(native-lib SHARED qwen_coder_bridge.cpp)

find_library(log-lib log)
find_library(android-lib android)
find_library(z-lib z)

if (log-lib STREQUAL "log-lib-NOTFOUND")
    message(FATAL_ERROR "Android log library not found")
endif()

if (android-lib STREQUAL "android-lib-NOTFOUND")
    message(FATAL_ERROR "Android native android lib not found")
endif()

if (z-lib STREQUAL "z-lib-NOTFOUND")
    message(FATAL_ERROR "zlib dependency not found")
endif()

set(LINK_LIBS
        llama
        ggml
        ggml_base
        ${log-lib}
        ${android-lib}
        ${z-lib})

target_link_libraries(native-lib PRIVATE ${LINK_LIBS})
