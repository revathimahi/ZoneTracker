package com.zonetracker.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
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

public class MainActivity extends Activity {
    private WebView web;
    private LocationManager lm;
    private GnssStatus.Callback gnssCb;
    private LocationListener keepAlive;
    private boolean pageLoaded = false;

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
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                cb.invoke(origin, true, false);
            }
        });
        if (hasPerm()) {
            loadPage();
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
        }
    }

    private boolean hasPerm() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void loadPage() {
        if (pageLoaded) return;
        pageLoaded = true;
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html");
        startGnss();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        loadPage();
    }

    private void startGnss() {
        if (!hasPerm()) return;
        try {
            lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            gnssCb = new GnssStatus.Callback() {
                @Override
                public void onSatelliteStatusChanged(GnssStatus st) {
                    int n = st.getSatelliteCount(), used = 0;
                    for (int i = 0; i < n; i++) if (st.usedInFix(i)) used++;
                    push(used, n);
                }
            };
            lm.registerGnssStatusCallback(gnssCb, new Handler(Looper.getMainLooper()));
            keepAlive = new LocationListener() {
                @Override public void onLocationChanged(Location l) {}
                @Override public void onStatusChanged(String p, int s, Bundle e) {}
                @Override public void onProviderEnabled(String p) {}
                @Override public void onProviderDisabled(String p) {}
            };
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, keepAlive);
        } catch (Exception ignored) {}
    }

    private void push(final int used, final int inView) {
        web.post(new Runnable() {
            @Override public void run() {
                web.evaluateJavascript("window.onGnss&&window.onGnss({used:" + used + ",inView:" + inView + "})", null);
            }
        });
    }

    @Override
    protected void onDestroy() {
        try {
            if (lm != null) {
                if (gnssCb != null) lm.unregisterGnssStatusCallback(gnssCb);
                if (keepAlive != null) lm.removeUpdates(keepAlive);
            }
        } catch (Exception ignored) {}
        super.onDestroy();
    }
}
