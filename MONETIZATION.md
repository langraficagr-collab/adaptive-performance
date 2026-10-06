# AdMob integration

The default build uses the publisher AdMob IDs. Use `-PproductionAds=false` for demo IDs. Open **Apoiar** to test an adaptive banner. Ads are confined to this screen; the optimization service never requests ads. No interstitials or ad rewards are enabled.

## Production setup

1. Register `com.mauricio.adaptiveperformance` in your own AdMob account.
2. Create an adaptive banner unit and copy the app ID (with `~`) and banner ID (with `/`).
3. Configure Privacy & messaging in AdMob. Production requests are gated by UMP consent; users can reopen privacy options when required.
4. Publish an accurate privacy policy covering the Google Mobile Ads SDK and configure your store listing, app-ads.txt and AdMob readiness requirements. Store acceptance is a separate review; this app uses privileged features and package visibility.
5. Build with your IDs:

```bash
gradle :app:assembleRelease -PproductionAds=true -PadmobAppId=YOUR_APP_ID -PadmobBannerId=YOUR_BANNER_ID
```

Configure release signing with the existing distribution key before publishing an update. The current production-ID version is 1.4.8. Never test production ads by clicking them; use Google's demo IDs or registered test devices.

The demo build intentionally skips UMP because Google demo IDs are not associated with the publisher's privacy messages. Production mode rejects demo IDs and requires both real IDs. Errors and no-fill leave every optimizer feature available. AdView is paused when this screen is hidden and destroyed when it closes.

Official references:
- https://developers.google.com/admob/android/quick-start
- https://developers.google.com/admob/android/banner
- https://developers.google.com/admob/android/privacy

## Validation (2026-10-06)

- Gradle assembleDebug: successful.
- Update installed over v1.4.6 without removing app data.
- Apoiar navigation opened SupportActivity.
- Google demo banner loaded on the Poco X7; log: `SupportAds: Banner loaded; test=true`.
- Production consent flow awaits the publisher's AdMob IDs and privacy-message configuration; it has not been verified against a live publisher account.
- Test APK is approximately 6.9 MB, compared with the previous approximately 1.24 MB.

## Publisher app registered (2026-10-06)

- AdMob app: Adaptive Performance (Android, not yet listed in a supported store).
- App ID: `ca-app-pub-6594966604456519~9008375832`
- Banner Apoiar ID: `ca-app-pub-6594966604456519/1610986134`
- Registration and ad-unit creation confirmed in the AdMob console.
- Payment profile submission, privacy-message configuration, store listing and app-ads.txt remain pending. Version 1.4.8 uses the publisher IDs, with UMP gating and the publisher phone registered as a test device.

## Production-ID validation (2026-10-06)

Version 1.4.8 (versionCode 32) built successfully and installed over the previous version. The UMP request used the correct publisher app ID. UMP reported missing publisher privacy forms; banner request returned error 3 (no fill). Live ad delivery and revenue are not verified. Configure Privacy & messaging and complete AdMob app readiness before expecting live inventory. The publisher phone is registered in RequestConfiguration as a test device.

The AdSense (Brasil) payment profile was submitted and confirmed in the console. Identity verification is not currently requested.
