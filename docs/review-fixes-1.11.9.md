# 1.11.9 — review hardening (PR #12, not a published release)

This follow-up addresses the 13 CodeRabbit review findings and the CodeQL null-dereference alert from the 1.11.8 PR head. Changes are deliberately limited to reversible behavior, Shizuku capability state, diagnostics and tests.

- Optimizer CPU throttling only starts beyond 3%; active thermal protection maintains a bounded sample cadence, with stale SoC/skin values discarded and BatteryManager/Android thermal APIs kept active.
- Invalid CPU energy estimates no longer display as negative mWh.
- Preload migrations clear stale app lists, preserve explicit opt-in after migration, and tests verify durable commit/restart behavior.
- Shizuku disconnect and exceptions invalidate cached privilege reports, trigger reprobe after reconnect, and PSI reporting falls back to direct read-only filesystem access.
- Automatic tuning pauses shell-dependent trials when Shizuku is absent; inconclusive two-minute windows no longer trigger regression cooldowns.
- Unsupported legacy zRAM profiles are normalized once when the selector is unavailable.
- Explicit signal-read requests work without granting permission to change cellular network mode; automatic radio switching remains disabled without verified write capability.
- LanguageHarness null guard addresses the CodeQL finding.

**Verification:** Android full debug compilation and local lint passed; BatteryFirstHarness and LanguageHarness passed on local JVM. A PR review and device-specific regression tests may still be required; no battery saving guarantee, no merge, and no public release yet.
