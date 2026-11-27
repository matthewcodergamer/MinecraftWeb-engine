# Minecraft Java 26.3 — Android shell

This is the Tauri v2 Android wrapper for the browser client. It embeds the live
Hugging Face client in a full-screen WebView, so the same Java/TeaVM client,
world save picker, sponsor card and touch controls are used on Android.

## Requirements

- Android 12 or newer, with Android System WebView 121 or newer (WebGPU).
- A device with a Qualcomm or ARM GPU. WebGPU is not available on every
  Android 11 device and is disabled by some vendor WebViews.
- ~250 MB of free storage for the WASM and asset caches.

The shell does not bundle the 112 MB WebAssembly module or the Minecraft
assets. They stream from the model repository and are cached by the WebView.
That keeps the APK small and lets the game update without reinstalling.

## Build

The APK is built on GitHub Actions by
`.github/workflows/build-android-apk.yml`. It uses the runner's Android SDK/NDK
and produces `minecraft-browser-android.apk` as a workflow artifact.

Local build (with Android SDK + NDK installed):

```sh
cd android
npm install
npx tauri icon src-tauri/icons/source.png
npx tauri android init
npx tauri android build --apk --target aarch64
```

## Ads

The web client always shows the five-second house sponsor card before Play.
Native Unity LevelPlay/AppLovin mediation is intentionally not compiled into
this shell: it needs a production app key, consent flow and a Play Console /
LevelPlay account. The sponsor card is the guaranteed ad on every launch.

## Controls

Touch controls come from the client's `input.js`. The Bedrock layout mapping is
documented in `docs/ANDROID-CONTROLS.md` and follows the public
`Mojang/bedrock-samples` UI resources and GeyserMC mappings.
