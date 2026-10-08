# Adaptive Performance — English guide

[Português](README.md) · [English website](https://langraficagr-collab.github.io/adaptive-performance/en.html) · [Download latest APK](https://github.com/langraficagr-collab/adaptive-performance/releases/latest)

Adaptive Performance is an open-source Android application designed to monitor battery use, CPU, available RAM, thermals, storage health, background activity, and device responsiveness. Supported advanced controls use Shizuku (ADB shell privileges) instead of root; available features depend on the device and ROM.

## What's new in version 1.9.3

**English / Português switching:** Tap **English** or **Português** near the top of the main screen. The selection is saved and does not change the phone's system language. The main dashboard and main controls are translated, with additional translations across secondary screens.

**Reversible automatic testing:** In **Automatic** mode, Adaptive Performance takes a **2-minute baseline**, turns on **one reversible option**, then monitors it for **another 2 minutes**. It compares estimated electrical power, CPU usage, available RAM, temperature, and UI smoothness where readings are available. A setting is retained **only if there is a measurable improvement without a significant regression**. Worsening or inconclusive trials are rolled back. Trials are sequential and can stop when readings are unreliable, the device heats up, the battery is low, or the phone is charging. Automatic mode prioritizes safety and avoids irreversible operations.

These short observations are comparative indicators, not proof of a specific battery-life gain over a full day. Actual savings depend on device hardware, usage, apps, and Android power management.

### Automatic vs. Advanced

- **Automatic:** Selects reversible optimizations based on measured results. The current candidate and the reason for keeping or reverting it appear in the automatic optimization panel.
- **Advanced:** Exposes technical switches and manually adjustable settings. Switching out of Automatic stops an ongoing reversible trial.

### Recent-app preloading

The main edition may identify up to **four recently used apps every 15 minutes** and selectively preload their APK/resource data, subject to limits for RAM, temperature, and battery. Preloading is not the same as keeping apps locked in RAM; Android can reclaim the cache at any time.

### Other key features

Thermal protection, CPU and memory metrics, PSI monitoring, zRAM/swap protection, estimated battery time remaining, adaptive idle handling, health history, safe storage maintenance/TRIM, optional location savings, mobile signal optimization, manual app restrictions, and AdGuard private DNS controls. Individual functions may require Shizuku and device-specific support.

## Installation

1. Download **Adaptive-Performance-v1.9.3.apk** from the [v1.9.3 release](https://github.com/langraficagr-collab/adaptive-performance/releases/tag/v1.9.3).
2. Install on Android 8.0 or later. An in-place update preserves existing data when APK signatures match.
3. For privileged controls, run Shizuku and authorize Adaptive Performance.

## Build from source

With the Android SDK, Gradle, and Java 17 configured:

```bash
gradle :app:assembleFullDebug
```

Result: `app/build/outputs/apk/full/debug/app-full-debug.apk`.

The repository does not contain passwords, local Android SDK paths, or private development backups.

## Limitations and safety

- Short 2-minute samples cannot establish long-term energy savings, particularly when the battery percentage changes in 1% steps.
- Not all devices expose usable battery-current or performance signals. Without reliable measurements, the app does not automatically approve changes.
- Android may override background scheduling, memory cache, and power settings.
- Advanced operations and some restricted operations require Shizuku or additional permissions.

[Open an issue](https://github.com/langraficagr-collab/adaptive-performance/issues) for bug reports and translation feedback.
