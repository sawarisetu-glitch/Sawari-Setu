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
import android.graphics.Color;
import android.view.View;
import android.view.Window;
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
    private LocationListener tripLocationListener;
    private boolean tripTracking = false;
    private final Handler handler = new Handler();

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(7, 82, 62));
        window.setNavigationBarColor(Color.rgb(244, 247, 251));
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(true);
        } else {
            window.getDecorView().setSystemUiVisibility(0);
        }
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

    private void startTripTracking(final String bookingId) {
        if (locationManager == null) locationManager = (LocationManager)getSystemService(LOCATION_SERVICE);
        if (!hasFineLocationForTrip()) return;
        stopTripTracking();
        tripTracking = true;
        tripLocationListener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (!tripTracking || location == null) return;
                sendTripLocationToJs(location);
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10000L, 10f, tripLocationListener);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000L, 10f, tripLocationListener);
            }
        } catch (SecurityException ignored) { tripTracking = false; }
    }

    private boolean hasFineLocationForTrip() {
        return android.os.Build.VERSION.SDK_INT < 23 ||
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void sendTripLocationToJs(Location l) {
        final double lat=l.getLatitude(), lon=l.getLongitude();
        final float acc=l.hasAccuracy()?l.getAccuracy():0f;
        final long ts=System.currentTimeMillis();
        runOnUiThread(() -> {
            if (webView == null) return;
            webView.evaluateJavascript("if(typeof window.onTripLocation==='function'){window.onTripLocation("+lat+","+lon+","+acc+","+ts+");}", null);
        });
    }

    private void stopTripTracking() {
        tripTracking=false;
        if (locationManager != null && tripLocationListener != null) {
            try { locationManager.removeUpdates(tripLocationListener); } catch (Exception ignored) {}
        }
        tripLocationListener=null;
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
        @JavascriptInterface public void startTripTracking(String bookingId) { MainActivity.this.startTripTracking(bookingId); }
        @JavascriptInterface public void stopTripTracking() { MainActivity.this.stopTripTracking(); }
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
        stopTripTracking();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
