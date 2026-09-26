package com.sawarisetu.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;

public class MainActivity extends Activity {
    private static final String ORIGIN = "https://sawarisetu.local/";
    private static final int LOCATION_REQUEST = 10;
    private static final long GPS_TIMEOUT_MS = 25000L;

    private WebView webView;
    private LocationManager locationManager;
    private LocationListener gpsListener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isListening = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setDatabaseEnabled(true);

        webView.setWebViewClient(new AssetWebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }
        });

        webView.addJavascriptInterface(new AndroidLocationBridge(), "AndroidLocation");
        webView.loadUrl(ORIGIN + "index.html");

        if (Build.VERSION.SDK_INT >= 23 && !hasFineLocation()) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, LOCATION_REQUEST);
        }
    }

    private boolean hasFineLocation() {
        return Build.VERSION.SDK_INT < 23 ||
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNativeLocation() {
        stopLocationUpdates();

        if (!hasFineLocation()) {
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }, LOCATION_REQUEST);
            }
            notifyJs("सटीक (Precise) GPS अनुमति दें।", true);
            return;
        }

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            notifyJs("लोकेशन सेवा उपलब्ध नहीं है।", true);
            return;
        }

        boolean isGpsOn = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        if (!isGpsOn) {
            notifyJs("कृपया फोन की सेटिंग्स से GPS (Location) ON करें।", true);
            return;
        }

        isListening = true;

        gpsListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location loc) {
                if (loc != null && loc.hasAccuracy() && loc.getAccuracy() <= 60.0f) {
                    // सिर्फ सैटेलाइट आधारित सटीक फिक्स ही ऐप को भेजा जाएगा
                    sendLocationToJs(loc);
                    stopLocationUpdates();
                }
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };

        try {
            // सेल-टावर (Network) को छोड़कर केवल हाई-एक्यूरेसी GPS_PROVIDER से डेटा लेना
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0f,
                    gpsListener,
                    Looper.getMainLooper()
            );
        } catch (SecurityException e) {
            notifyJs("GPS अनुमति की समस्या है।", true);
            return;
        }

        // 25 सेकंड तक अगर 60 मीटर से बेहतर सैटेलाइट सिग्नल न मिले तो उपलब्ध बेस्ट लोकेशन भेजें
        handler.postDelayed(() -> {
            if (!isListening) return;
            try {
                Location lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (lastGps != null && (System.currentTimeMillis() - lastGps.getTime()) < 60000) {
                    sendLocationToJs(lastGps);
                } else {
                    notifyJs("कमज़ोर GPS सिग्नल। कृपया खुले स्थान में जाकर फिर प्रयास करें।", true);
                }
            } catch (SecurityException ignored) {}
            stopLocationUpdates();
        }, GPS_TIMEOUT_MS);
    }

    private void sendLocationToJs(Location loc) {
        runOnUiThread(() -> {
            if (webView == null) return;
            String js = "if(typeof window.onNativeLocation==='function'){window.onNativeLocation("
                    + loc.getLatitude() + "," + loc.getLongitude() + "," + loc.getAccuracy() + ");}";
            webView.evaluateJavascript(js, null);
        });
    }

    private void notifyJs(String message, boolean error) {
        runOnUiThread(() -> {
            if (webView == null) return;
            String safe = message.replace("'", "\\'");
            webView.evaluateJavascript(
                    "if(typeof window.showNativeLocationMessage==='function'){window.showNativeLocationMessage('" + safe + "'," + error + ");}",
                    null
            );
            if (error) Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
        });
    }

    private void stopLocationUpdates() {
        isListening = false;
        handler.removeCallbacksAndMessages(null);
        if (locationManager != null && gpsListener != null) {
            try { locationManager.removeUpdates(gpsListener); } catch (Exception ignored) {}
        }
        gpsListener = null;
    }

    private class AndroidLocationBridge {
        @JavascriptInterface
        public void requestLocation() {
            requestNativeLocation();
        }
    }

    private class AssetWebViewClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return serve(request.getUrl().getPath());
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            try { return serve(android.net.Uri.parse(url).getPath()); }
            catch (Exception e) { return null; }
        }

        private WebResourceResponse serve(String path) {
            if (path == null) return null;
            String asset = null, mime = null;
            if (path.equals("/") || path.equals("/index.html")) { asset = "index.html"; mime = "text/html"; }
            else if (path.equals("/logo.png")) { asset = "logo.png"; mime = "image/png"; }
            if (asset == null) return null;
            try {
                InputStream in = getAssets().open(asset);
                return new WebResourceResponse(mime, "UTF-8", in);
            } catch (IOException e) { return null; }
        }
    }

    @Override
    protected void onDestroy() {
        stopLocationUpdates();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
