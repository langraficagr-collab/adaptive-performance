# Adaptive Performance 1.11.15 — Android test release

Release date: 2026-10-30. versionCode 97, versionName `1.11.15-ui-activity-fix`.

**APK:** https://github.com/langraficagr-collab/adaptive-performance/releases/download/v1.11.15/Adaptive-Performance-v1.11.15.apk

## Correções / Fixes

- Atividade recente / Recent activity: always-on unified event history with full view independent of optional efficacy scoring.
- Apps restritos por inatividade / Inactivity restrictions: remove records for uninstalled packages, count only confirmed restorations, list tracked installed apps, exclude system apps.
- Notificações / Notifications: alerts independent of battery-tuning trials, explicit blocked-channel status and a test-notification button.
- Congelamento manual / Manual freezing: chosen apps stop after foreground exit with critical-package protections.
- Avisos térmicos com consentimento / Consent-based heat suggestions: repeated CPU evidence + elevated temperature, explicit approval before freezing.

## Validation

Gradle `assembleFullDebug` succeeded. On-device update preserved app data. The on-device review removed 29 stale app-tracking entries and verified delivery of an Android notification (`adaptive_changes`). The complete periodic inactivity scan was not confirmed before publication. Device-level energy improvement and attribution of physical heat to a particular app are not proven.

Development-signed, not a production signing key. Install updates only over APKs signed with the same certificate; root is not required for app installation, but privileged optimization operations require working Shizuku/ADB shell permissions. Protected system packages are not eligible for automatic inactivity restrictions.
