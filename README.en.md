# Adaptive Performance — English guide

[Português](README.md) · [English website](https://langraficagr-collab.github.io/adaptive-performance/en.html) · [Download latest APK](https://github.com/langraficagr-collab/adaptive-performance/releases/latest)

Adaptive Performance is an open-source Android application designed to monitor battery use, CPU, available RAM, thermals, storage health, background activity, and device responsiveness. Supported advanced controls use Shizuku (ADB shell privileges) instead of root; available features depend on the device and ROM.

## Preview release: 1.11.6 — Shizuku safeguards and energy ML

**Android build:** `1.11.6-shizuku-ml` (**versionCode 88**, preview). [Download full APK](https://github.com/langraficagr-collab/adaptive-performance/releases/download/v1.11.6-shizuku-ml/Adaptive-Performance-v1.11.6-shizuku-ml.apk) · [Preview notes](https://github.com/langraficagr-collab/adaptive-performance/releases/tag/v1.11.6-shizuku-ml).

This update retains all ten Adaptive Brain 2.0 extensions and existing features, with targeted fixes:

- **Local ML data integrity:** when serializing app transitions, skip entries that do not fit the 6,500-character limit but preserve later, shorter entries. Dedicated regression test included.
- **CodeRabbit query script:** filter by the bot's exact GitHub login, excluding unrelated accounts.
- **Android Lint CI:** discover the runner's Android SDK, avoid reinstalling installed Android packages, and accept licenses only when installation is necessary. This removes the obsolete `tools` package failure.
- **Review and verification:** CodeRabbit PR #4, on-device build, local regression checks, Android Lint, Java analysis and CodeQL on GitHub.

No additional always-on services. This update does not claim proven battery savings. The APK is **development/debug signed**, so in-place updates require a matching signing certificate.

## What's new in 1.11.0 — Adaptive Brain 2.0

**Android build:** `1.11.0-adaptive-brain2` (versionCode **82**). [Download the full APK](https://github.com/langraficagr-collab/adaptive-performance/releases/download/v1.11.0/Adaptive-Performance-v1.11.0.apk) · [Release and source code](https://github.com/langraficagr-collab/adaptive-performance/releases/tag/v1.11.0).

Ten lightweight features run opportunistically inside the existing service, rather than starting ten additional constantly running processes.

| Improvement | Behavior and limitations |
|---|---|
| **1. Contextual ML 2.0** | Two small on-device online logistic models learn screen, battery, CPU, RAM, charging, heat and time patterns. **48 samples** at **≥3-minute** intervals are required before ML decisions. |
| **2. Next-app prediction** | Learns up to **32 app transitions** and may only reorder the *already eligible recent-app shortlist*. Never launches apps or pins memory. |
| **3. Thermal AI Pro** | Combines battery, skin, SoC and native Android ThermalHeadroom when supported; provides an illustrative **5-minute temperature trend**. No synthetic OEM thermal override in Automatic mode. |
| **4. Charging observation** | Reads battery current if available from BatteryManager and identifies hot charging; **cannot control charger wattage**. |
| **5. Predictive RAM** | Estimates the **10-minute free RAM trend**, skipping optional preloading when a memory shortage is likely. |
| **6. UI smoothness** | Bounded **150-frame/10-second** samples of **Adaptive Performance's own interface**, at least **10 minutes** apart. Does not measure third-party app frame times. |
| **7. Self-overhead governor** | Uses the optimizer's own estimated CPU usage to reduce optional work and monitoring. Estimated CPU energy is not measured battery drain. |
| **8. Contextual recovery** | Strong negative observations under comparable circumstances can pause optional preloading reversibly for **six hours**; user settings remain unchanged. |
| **9. Routine profiles** | Recognizes idle, games, GPS navigation, messaging, social and general usage; avoids additional preloading during games/navigation/idle. |
| **10. Experiment lab** | Shows sampling, forecasts, provisional comparisons, reversions, prior evidence and diagnostic export; observational changes **do not prove energy savings**. |

### Features retained from previous releases

Automatic and Advanced modes, **English and Portuguese**, reversible automatic tuning (2-minute baseline plus 2-minute trial with later 6–24-hour evaluation when comparable), up to **four eligible recently used apps every 15 minutes**, battery and runtime estimation, RAM/zRAM/swap and PSI, CPU pressure handling, **manual app freezing** and exceptions, hardware thermal monitoring, screen-brightness mitigation, idle/Doze optimization, memory pressure protection, crash/ANR investigation and rollback, safe F2FS/TRIM/storage maintenance, large/duplicate file analysis, DNS VPN filtering, adaptive mobile signal comparison, optional GPS savings, usage history, device compatibility audit and diagnostic export. Availability varies with Android ROM, hardware, permissions and Shizuku.

### Install and privacy

Download [Adaptive-Performance-v1.11.6-shizuku-ml.apk](https://github.com/langraficagr-collab/adaptive-performance/releases/download/v1.11.6-shizuku-ml/Adaptive-Performance-v1.11.6-shizuku-ml.apk) on **Android 8.0+** (preview build). An update preserves app data if signatures match. Authorize Shizuku for advanced operations. ML runs entirely on the device without a remote AI service. Android's device backup may include app preferences if enabled. Clear or disable learning at any time.

No fixed battery gain, reduced temperature, root privileges or charger firmware control is guaranteed. Full, conservative and Lite build variants compile, but the downloadable release asset is the **full edition**.

---

## History: version 1.9.3

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

1. Download **Adaptive-Performance-v1.11.6-shizuku-ml.apk** from the [v1.11.6 preview](https://github.com/langraficagr-collab/adaptive-performance/releases/tag/v1.11.6-shizuku-ml).
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

### Development history 1.9.4 — verifiable savings
- Self-monitoring budget: app-process CPU, polling count (wake-up proxy), CPU-only mWh estimate; monitoring slows down above 3% process CPU over 10 minutes, except during thermal emergencies.
- Reversible 2-minute trial, followed by a 6-hour review and 24-hour confirmation requiring at least eight comparable samples; unsafe or inconclusive changes roll back.
- Read-only ROM capability audit for Shizuku, PSI, zRAM, CPU and network controls.
- RAM TRIM now selects eligible background apps from actual usage history instead of a fixed list; automatic TRIM still requires two consecutive RAM/PSI pressure samples.
- Exported report includes device telemetry, tuning decisions, trim results, monitoring overhead and capability audit.
- Independent `nativeapp` and `pythonapp` prototypes are excluded from the main Gradle build.
CPU energy estimates are not actual per-app battery measurements; usage variations can confound long-term comparisons.

### Development history 1.9.5 — thermal intelligence and service continuity
- Native 30-second thermal headroom forecast and status callback where supported, using at most one read every 20 seconds. OEM fallback and reversible mild protective action only after confirmed warning.
- Screen, charging, power-saver, device-idle and thermal events trigger debounced checks; periodic sampling remains as fallback.
- Experimental matching by coarse brightness, network transport, foreground workload category, screen state and battery-saver state.
- Evidence panel displays settings confirmed after 24 hours and recent reversions, plus estimated power-draw improvement (not guaranteed battery-life increase).
- Three-minute lightweight incident telemetry with a 20-minute cooldown; in-depth diagnostic bursts are also time-bounded.
- Flags possible 15-minute service-monitoring gaps; cannot bypass OEM process limits automatically.

### Local version 1.10.0 — private on-device machine learning
- Two tiny pure-Java online logistic regressors predict next-sample activity and high-temperature risk from screen state, CPU, RAM, battery, charging, hour and day of week. No new permissions or remote ML API.
- Incremental supervised learning every >=3 minutes, using the next observed outcome as the label; tiny model serialized locally.
- First 48 training samples are observation-only. Once ready, predictions may **only skip optional app preloading** when forecast thermal risk is high or activity is very low. No force-stopping, freezing, kernel changes or synthetic thermal overrides from ML.
- On-device switch and delete-model control, also reset via overall learning reset. Estimates do not prove power savings.

### Local hotfix 1.10.1 — training without privileged CPU access
- If Shizuku CPU telemetry is unavailable, the model continues learning from screen, charging, battery, free RAM and temperature. Same three-minute cadence and 48-sample safety gate.

### Technical details of the published 1.11.0 — Adaptive Brain 2.0
Ten incremental extensions, preserving prior behavior: contextual online ML with guarded observational feedback; bounded 32-edge next-app transition model; real battery/skin/SoC temperature trends and native ThermalHeadroom, with illustrative 5-minute projection; charging current and temperature observation (does not change charging power); 10-minute free-RAM trend; bounded 150-frame self-app frame sampling; self-CPU budget; reversible 6-hour optional preloading cooldown following strong, context-matched regressions; game/navigation/social/idle routines; local experiment results and diagnostic export. All automatic ML actions only inhibit optional preloading or reprioritize a recently-used eligible app. No synthetic OEM thermal status, app killing, cloud ML, new polling service, or guaranteed battery savings. Local package history may be included in Android backup if enabled.

### 1.11.6 — Rootless Shizuku and per-feature learning
Runtime capability probes disable inaccessible CPU/GPU/zRAM sysfs writes and unverified radio switching. Process compaction runs only under genuine memory pressure with at least a 30-minute cooldown and never changes zRAM configuration. Aggressive automatic cache cleaning and thermal status overrides were removed. Sequential reversible A/B trials keep core monitoring on, require at least 7% measured power reduction, and perform longer-term validation before claiming savings. Each feature tracks reverts and confirmed outcomes.
