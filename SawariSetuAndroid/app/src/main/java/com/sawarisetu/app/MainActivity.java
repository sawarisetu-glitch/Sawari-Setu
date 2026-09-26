package com.sawarisetu.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.IOException;
import java.io.InputStream;

public class MainActivity extends Activity {
    private static final String ORIGIN = "https://sawarisetu.local/";
    private WebView webView;
    private LocationManager locationManager;
    private Location bestLocation;
    private LocationListener locationListener;
    private final Handler handler = new Handler();

    @Override public void onCreate(Bundle savedInstanceState) {
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
        webView.setWebChromeClient(new WebChromeClient(){
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback){
                callback.invoke(origin, true, false);
            }
        });
        webView.addJavascriptInterface(new AndroidLocationBridge(), "AndroidLocation");

        if (android.os.Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 10);
        }
        webView.loadUrl(ORIGIN + "index.html");
    }

    private void requestNativeLocation() {
        if (android.os.Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 10);
            runOnUiThread(() -> webView.evaluateJavascript("alert('Location permission दें, फिर Current Location दोबारा दबाएँ।')", null));
            return;
        }
        locationManager = (LocationManager)getSystemService(LOCATION_SERVICE);
        bestLocation = null;
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (location == null) return;
                if (bestLocation == null || location.getAccuracy() < bestLocation.getAccuracy()) {
                    bestLocation = location;
                }
                if (location.hasAccuracy() && location.getAccuracy() <= 50f) {
                    sendLocation(bestLocation);
                    stopLocationUpdates();
                }
            }
        };
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, locationListener);
            }
        } catch (SecurityException e) {
            runOnUiThread(() -> webView.evaluateJavascript("alert('Location permission नहीं मिली।')", null));
            return;
        }
        handler.postDelayed(() -> {
            if (bestLocation != null) sendLocation(bestLocation);
            else runOnUiThread(() -> webView.evaluateJavascript("alert('GPS location नहीं मिली। Phone का Location/GPS ON करें।')", null));
            stopLocationUpdates();
        }, 20000L);
    }

    private void sendLocation(Location l) {
        if (l == null) return;
        final double lat = l.getLatitude();
        final double lon = l.getLongitude();
        final float acc = l.hasAccuracy() ? l.getAccuracy() : 0f;
        runOnUiThread(() -> webView.evaluateJavascript(
                "window.onNativeLocation(" + lat + "," + lon + "," + acc + ");", null));
    }

    private void stopLocationUpdates() {
        handler.removeCallbacksAndMessages(null);
        if (locationManager != null && locationListener != null) {
            try { locationManager.removeUpdates(locationListener); } catch (SecurityException ignored) {}
        }
        locationListener = null;
    }

    private class AndroidLocationBridge {
        @JavascriptInterface public void requestLocation() { requestNativeLocation(); }
    }

    private class AssetWebViewClient extends WebViewClient {
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return serve(request.getUrl().getPath());
        }
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            try { return serve(android.net.Uri.parse(url).getPath()); } catch (Exception e) { return null; }
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

    @Override protected void onDestroy() {
        stopLocationUpdates();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
