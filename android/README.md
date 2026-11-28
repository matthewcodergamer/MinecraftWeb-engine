# Native Android shell

This module packages the Rust `mc-native` Vulkan library as a sideloadable
arm64 APK.

- `android.app.NativeActivity` + `android.app.lib_name=mc_native`
- `android:hasCode="false"` — there is no Java/Kotlin app code
- `arm64-v8a` only
- `compileSdk 35`, `minSdk 24`, `targetSdk 35`
- 16 KB ELF page alignment for Android 15/16 devices

The `.so` is built by CI with `cargo-ndk` and copied into
`app/src/main/jniLibs/arm64-v8a/`. The Gradle project itself contains no
WebView or Tauri dependency.

```sh
cargo ndk -t arm64-v8a -o android/app/src/main/jniLibs \
  build --manifest-path native/Cargo.toml --release --lib
gradle -p android :app:assembleRelease
```

The workflow also checks that the APK contains `libmc_native.so`, links
`libvulkan.so`, and has no WebView/Chromium payload.
