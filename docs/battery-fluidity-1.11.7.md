# Adaptive Performance 1.11.7 — Battery & Fluidity

Source branch: `fix/battery-fluidity-20261029`, based on PR #11 (Shizuku/ML).

- Migration **disables optional APK preloading once** for existing and new installations; after migration, a manual opt-in is preserved. Conservative/automatic mode no longer re-enables it by default.
- When preloading is enabled, thermal/power regression monitoring also applies in the full edition.
- Normal active sampling relaxes from 60 to 90 seconds, intensive samples from 8/20 to 30/60 seconds; screen-off remains at 10 minutes. Android native thermal callbacks provide event-based emergency checks.
- Expensive thermalservice dumps are rate-limited to 3 minutes on screen and 10 minutes off. Foreground-activity dumps are skipped when the display is off.
- A/B energy learning only accepts a short-window improvement when both windows have a power coefficient of variation <=0.40. Temperature, CPU, RAM and UI-jank regression protections remain. Validation over 6–24 hours is unchanged.
- CPU-cost reporting now uses measured process CPU milliseconds (not a fixed-watt battery claim), with earlier self-throttling at 1.5% process CPU in its monitoring window.
- Manual cache cleanup uses a single Package Manager trim attempt even when both cache categories are selected.

Tests: `assembleFullDebug`, `testFullDebugUnitTest`, BrainV2Harness, LocalMlHarness and BatteryFirstHarness. The APK uses the same Android debug signing certificate as the installed 1.11.6; `adb install -r` preserves app data.

No fixed savings are claimed. To measure real gains, compare matched screen-on/off sessions unplugged and with similar connectivity, temperature, brightness and foreground usage. User's master-enable setting is not changed by the migration.
