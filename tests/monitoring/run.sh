#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
src=app/src/main/java/com/mauricio/adaptiveperformance
javac -d "$classes" tests/conservative-harness/android/content/SharedPreferences.java \
    tests/monitoring/stubs/android/os/Process.java \
    tests/monitoring/stubs/android/os/SystemClock.java \
    tests/monitoring/stubs/com/mauricio/adaptiveperformance/IPrivilegedService.java \
    "$src/BatteryFirstDefaults.java" "$src/MonitoringPolicy.java" "$src/RecurrenceTracker.java" \
    "$src/AppCostGuard.java" "$src/ConservativeTuningController.java" \
    "$src/CapabilityPolicy.java" "$src/UiLanguage.java" \
    tests/battery-first/BatteryFirstHarness.java tests/monitoring/MonitoringHarness.java \
    tests/monitoring/CostGuardHarness.java \
    tests/conservative-harness/com/mauricio/adaptiveperformance/ConservativeHarness.java
for harness in BatteryFirstHarness MonitoringHarness CostGuardHarness ConservativeHarness; do
    java -cp "$classes" "com.mauricio.adaptiveperformance.$harness"
done
