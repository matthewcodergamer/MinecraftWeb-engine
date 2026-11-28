# Native Vulkan client

This is the real Android client: a Rust `winit` window, a `wgpu` Vulkan swap
chain, the vanilla block texture array, and a generated overworld. It does not
contain a WebView, a browser shell, or Tauri.

The Java Brain is still the authority for the full game. This crate is the
native Body milestone: it proves the platform layer (Android `NativeActivity`,
Vulkan surface, touch input, vanilla textures, chunk meshing) works without a
browser. The world generator can be replaced with the Java world snapshot
without changing the renderer.

## Controls

| Input | Action |
| --- | --- |
| Drag left half | Virtual joystick (move/strafe) |
| Drag right half | Look |
| Double-tap right half | Jump |
| Push the joystick to the edge | Sprint |
| WASD / mouse drag (desktop) | Move / look |
| Space (desktop) | Jump |
| Escape (desktop) | Quit |

## Build the APK

The APK is built by `.github/workflows/build-native-vulkan-apk.yml` with the
Android NDK, `cargo-ndk`, and Gradle. The CI also asserts that the APK contains
`libmc_native.so`, links `libvulkan.so`, uses `android.app.NativeActivity`, and
has no WebView/Tauri payload.

Local prerequisites: Rust 1.87+, Android SDK 35, NDK 27.2, and Gradle 8.11.

```sh
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384"
cd native
cargo ndk -t arm64-v8a -p 24 -o ../android/app/src/main/jniLibs build --release --lib
cd ..
gradle -p android :app:assembleRelease
```

The signed APK is published to the `native-vulkan-latest` GitHub release.
