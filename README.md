# GenUIApp

Android app that runs the Qwen2.5-Coder-0.5B model locally via llama.cpp and renders the generated HTML/CSS inside an embedded WebView.

## Prerequisites

- Android Studio Giraffe (or newer) with NDK support enabled.
- Android device or emulator that supports `arm64-v8a`.
- Prebuilt llama.cpp shared libraries (already provided under `llama cpp Code/main/jniLibs`).
- Qwen2.5-Coder-0.5B model converted to GGUF (for example the official int4 quantisation `qwen2.5-coder-0.5b-instruct-q4_k_m.gguf`).

## Project layout

```
app/                       # Android application module
  src/main/java/com/samsung/genuiapp
  src/main/cpp             # JNI bridge powered by llama.cpp
llama cpp Code/            # Prebuilt llama.cpp headers + shared libraries (provided)
```

## Getting started

1. Launch Android Studio and open this folder (`GenUIApp`).
2. When prompted, allow Android Studio to download the Gradle/NDK components.
3. Copy the `*.gguf` model file onto your device (e.g. `/sdcard/Download/qwen2.5-coder-0.5b-instruct-q4_k_m.gguf`).
4. Build & run the app on an `arm64-v8a` device. The first launch may take a moment while the native libraries are extracted.
5. In the UI:
    - Paste the absolute model path.
    - Tap **Load Model** (logs appear in the status label). When the model is ready the **Generate UI** button becomes enabled.
    - Enter a prompt that describes the desired UI. The model response is rendered inside the WebView; raw text is preserved if the response is not valid HTML.
    - Tap **Unload** to free memory when finished.

## Notes

- The JNI bridge limits the context window to 4096 tokens and clamps generation to 1024 tokens by default.
- If a prompt already contains Qwen chat tags (e.g. `<|im_start|>`), it is passed through verbatim; otherwise the bridge wraps it with a system instruction that encourages clean HTML/CSS output.
- GPU acceleration is not enabled; llama.cpp runs on CPU using the bundled libraries.
- The project expects the provided `llama cpp Code` folder to stay at its current relative path. If you move it, update `app/src/main/cpp/CMakeLists.txt` and `app/build.gradle.kts` accordingly.
