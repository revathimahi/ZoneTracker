package com.zonetracker.app;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewAssetLoader;
import java.util.Locale;
import java.util.TreeSet;

public class MainActivity extends Activity {
    private WebView web;
    private LocationManager lm;
    private GnssStatus.Callback gnssCb;
    private LocationListener locL;
    private boolean started = false, pageLoaded = false;
    private long lastGnss = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setAllowFileAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }
            @Override
            public void onPageFinished(WebView v, String url) {
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                js("window.onDevice&&window.onDevice({brand:'" + clean(Build.MANUFACTURER) + "',model:'" + clean(Build.MODEL)
                        + "',rel:'" + clean(Build.VERSION.RELEASE) + "',sdk:" + Build.VERSION.SDK_INT
                        + ",ram:" + Math.round(mi.totalMem / 1e9) + "})");
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String o, GeolocationPermissions.Callback cb) {
                cb.invoke(o, true, false);
            }
        });
        if (hasPerm()) loadPage();
        else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
    }

    private boolean hasPerm() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void loadPage() {
        if (pageLoaded) return;
        pageLoaded = true;
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    @Override
    public void onRequestPermissionsResult(int c, String[] p, int[] r) {
        super.onRequestPermissionsResult(c, p, r);
        loadPage();
        startGnss();
    }

    @Override protected void onResume() { super.onResume(); startGnss(); }
    @Override protected void onPause() { stopGnss(); super.onPause(); }

    private static String clean(String x) { return x == null ? "" : x.replaceAll("[^A-Za-z0-9 ._-]", ""); }

    private static String name(int t) {
        switch (t) {
            case GnssStatus.CONSTELLATION_GPS: return "GPS";
            case GnssStatus.CONSTELLATION_GLONASS: return "GLONASS";
            case GnssStatus.CONSTELLATION_GALILEO: return "Galileo";
            case GnssStatus.CONSTELLATION_BEIDOU: return "BeiDou";
            case GnssStatus.CONSTELLATION_QZSS: return "QZSS";
            case GnssStatus.CONSTELLATION_IRNSS: return "NavIC";
            case GnssStatus.CONSTELLATION_SBAS: return "SBAS";
            default: return "Other";
        }
    }

    private void startGnss() {
        if (started || !hasPerm()) return;
        try {
            lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            gnssCb = new GnssStatus.Callback() {
                @Override
                public void onSatelliteStatusChanged(GnssStatus st) {
                    long now = System.currentTimeMillis();
                    if (now - lastGnss < 900) return;
                    lastGnss = now;
                    int n = st.getSatelliteCount(), used = 0, cn = 0, mx = 0, mn = 99, strong = 0;
                    boolean dual = false;
                    TreeSet<String> cons = new TreeSet<>();
                    for (int i = 0; i < n; i++) {
                        if (Build.VERSION.SDK_INT >= 26 && st.hasCarrierFrequencyHz(i)
                                && st.getCarrierFrequencyHz(i) < 1400e6f) dual = true;
                        if (st.usedInFix(i)) {
                            used++;
                            float c0 = st.getCn0DbHz(i);
                            cn += c0;
                            if (c0 > mx) mx = Math.round(c0);
                            if (c0 < mn) mn = Math.round(c0);
                            if (c0 >= 30) strong++;
                            cons.add(name(st.getConstellationType(i)));
                        }
                    }
                    int avg = used > 0 ? Math.round((float) cn / used) : 0;
                    StringBuilder sb = new StringBuilder();
                    for (String c : cons) sb.append(sb.length() > 0 ? "," : "").append('"').append(c).append('"');
                    js("window.onGnss&&window.onGnss({used:" + used + ",inView:" + n + ",cn0:" + avg + ",max:" + mx + ",min:" + (used > 0 ? mn : 0) + ",strong:" + strong
                            + ",dual:" + dual + ",cons:[" + sb + "]})");
                }
            };
            lm.registerGnssStatusCallback(gnssCb, new Handler(Looper.getMainLooper()));
            locL = new LocationListener() {
                @Override public void onLocationChanged(Location l) {
                    js(String.format(Locale.US, "window.onNativeLoc&&window.onNativeLoc({lat:%.7f,lng:%.7f,acc:%.1f,spd:%.2f,t:%d})",
                            l.getLatitude(), l.getLongitude(), l.getAccuracy(),
                            l.hasSpeed() ? l.getSpeed() : 0f, l.getTime()));
                }
                @Override public void onStatusChanged(String p, int s, Bundle e) {}
                @Override public void onProviderEnabled(String p) {}
                @Override public void onProviderDisabled(String p) {}
            };
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locL, Looper.getMainLooper());
            started = true;
        } catch (Exception ignored) {}
    }

    private void stopGnss() {
        try {
            if (lm != null) {
                if (gnssCb != null) lm.unregisterGnssStatusCallback(gnssCb);
                if (locL != null) lm.removeUpdates(locL);
            }
        } catch (Exception ignored) {}
        started = false;
    }

    private void js(final String code) {
        web.post(new Runnable() { @Override public void run() { web.evaluateJavascript(code, null); } });
    }

    @Override protected void onDestroy() { stopGnss(); super.onDestroy(); }
}
