package com.mauricio.adaptiveperformance;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.View;
import android.widget.*;
import com.google.android.gms.ads.*;
import com.google.android.ump.*;

/** Ads are confined to this foreground screen, never the optimization service. */
public class SupportActivity extends Activity {
    private AdView banner;
    private FrameLayout container;
    private TextView status;
    private Button privacy;
    private ConsentInformation consent;
    private boolean started, resumed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(24 * getResources().getDisplayMetrics().density);
        page.setPadding(pad, pad, pad, pad);
        page.setBackgroundColor(Color.rgb(6,16,25));
        TextView title = new TextView(this);
        title.setText("Apoiar o desenvolvimento");
        title.setTextSize(23);
        title.setTextColor(Color.WHITE);
        page.addView(title);
        status = new TextView(this);
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(16);
        status.setPadding(0,pad,0,pad);
        status.setText(BuildConfig.ADS_TEST_MODE
            ? "Demonstração de anúncios. Esta versão não gera receita."
            : "Os anúncios ajudam a manter o aplicativo gratuito.");
        page.addView(status);
        privacy = new Button(this);
        privacy.setText("Privacidade dos anúncios");
        privacy.setVisibility(View.GONE);
        page.addView(privacy);
        Button back = new Button(this);
        back.setText("Voltar");
        back.setOnClickListener(v -> finish());
        page.addView(back);
        Space space = new Space(this);
        page.addView(space,new LinearLayout.LayoutParams(1,0,1));
        TextView label = new TextView(this);
        label.setText("Publicidade");
        label.setTextColor(Color.LTGRAY);
        label.setPadding(0,pad,0,8);
        page.addView(label);
        container = new FrameLayout(this);
        page.addView(container,new LinearLayout.LayoutParams(-1,-2));
        setContentView(page);
        // Google's demo IDs always serve test ads. Production must pass UMP.
        if (BuildConfig.ADS_TEST_MODE) { initializeAds(); return; }
        consent = UserMessagingPlatform.getConsentInformation(this);
        privacy.setOnClickListener(v -> UserMessagingPlatform.showPrivacyOptionsForm(this, error -> {
            destroyBanner();
            started = false;
            if (error != null) status.setText("Não foi possível atualizar a privacidade.");
            initializeAds();
        }));
        consent.requestConsentInfoUpdate(this, new ConsentRequestParameters.Builder().build(),
            () -> {
                if (isFinishing() || isDestroyed()) return;
                privacy.setVisibility(consent.getPrivacyOptionsRequirementStatus()
                    == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED ? View.VISIBLE : View.GONE);
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(this, error -> {
                    if (error != null) status.setText("Anúncios indisponíveis no momento.");
                    initializeAds();
                });
            },
            error -> {
                if (isFinishing() || isDestroyed()) return;
                android.util.Log.w("SupportAds", "Consent update failed: " + error.getErrorCode() + " " + error.getMessage());
                status.setText("Anúncios indisponíveis. O aplicativo continua funcionando.");
                initializeAds();
            });
    }

    private void initializeAds() {
        if (started || isFinishing() || isDestroyed()) return;
        if (!BuildConfig.ADS_TEST_MODE && (consent == null || !consent.canRequestAds())) return;
        started = true;
        // The publisher's phone remains a test device; other devices use live ads.
        MobileAds.setRequestConfiguration(new RequestConfiguration.Builder()
            .setTestDeviceIds(java.util.Collections.singletonList("E3E3B2FC15E4F1EA2DA2ECE7234A73C2"))
            .build());
        new Thread(() -> MobileAds.initialize(getApplicationContext(),
            result -> runOnUiThread(() -> container.post(this::loadBanner))), "ads-init").start();
    }

    private void loadBanner() {
        if (isFinishing() || isDestroyed() || banner != null) return;
        if (!BuildConfig.ADS_TEST_MODE && !consent.canRequestAds()) return;
        int width = (int)(container.getWidth()/getResources().getDisplayMetrics().density);
        if (width <= 0) return;
        banner = new AdView(this);
        banner.setAdUnitId(getString(R.string.admob_banner_id));
        banner.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this,width));
        banner.setAdListener(new AdListener() {
            @Override public void onAdFailedToLoad(LoadAdError error) {
                status.setText("Anúncio indisponível no momento. Todas as funções continuam disponíveis.");
                android.util.Log.i("SupportAds", "Banner failed: " + error.getCode());
            }
            @Override public void onAdLoaded() {
                status.setText(BuildConfig.ADS_TEST_MODE
                    ? "Anúncio de teste carregado. Não gera receita."
                    : "Os anúncios ajudam a manter o aplicativo gratuito.");
                android.util.Log.i("SupportAds", "Banner loaded; test=" + BuildConfig.ADS_TEST_MODE);
                if (!resumed && banner != null) banner.pause();
            }
        });
        container.addView(banner);
        banner.loadAd(new AdRequest.Builder().build());
    }

    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (banner != null) banner.resume();
    }
    @Override protected void onPause() {
        resumed = false;
        if (banner != null) banner.pause();
        super.onPause();
    }
    private void destroyBanner() {
        if (banner != null) { banner.destroy(); container.removeView(banner); banner = null; }
    }
    @Override protected void onDestroy() { destroyBanner(); super.onDestroy(); }
}
