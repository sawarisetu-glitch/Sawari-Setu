package com.sawarisetu.app;

import android.Manifest;
import android.app.Activity;
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
    private static final long LOCATION_TIMEOUT_MS = 30000L;
    private static final float MAX_ACCEPTABLE_ACCURACY_M = 100f;

    private WebView webView;
    private LocationManager locationManager;
    private Location bestLocation;
    private LocationListener locationListener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean locationRequestRunning = false;

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
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onGeolocationPermissionsShowPrompt(String origin,
                    GeolocationPermissions.Callback callback) {
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

    private boolean hasAnyLocationPermission() {
        return Build.VERSION.SDK_INT < 23 ||
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNativeLocation() {
        stopLocationUpdates();

        if (!hasAnyLocationPermission()) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, LOCATION_REQUEST);
            notifyJs("लोकेशन permission दें, फिर 'मेरी वर्तमान लोकेशन' दोबारा दबाएँ।", true);
            return;
        }

        // Ride pickup needs precise location. Do not silently accept Android's
        // approximate/coarse permission, because that can place the user many km away.
        if (!hasFineLocation()) {
            notifyJs("सटीक (Precise) Location permission चालू करें। Android में Location permission में 'Precise' चुनें।", true);
            return;
        }

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) {
            notifyJs("फोन का Location service उपलब्ध नहीं है।", true);
            return;
        }

        boolean gpsEnabled = false;
        boolean networkEnabled = false;
        try {
            gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception ignored) {}

        if (!gpsEnabled && !networkEnabled) {
            notifyJs("Phone का Location/GPS ON करें, फिर दोबारा प्रयास करें।", true);
            return;
        }

        bestLocation = null;
        locationRequestRunning = true;
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (!isUsableFreshLocation(location)) return;

                if (bestLocation == null || isBetter(location, bestLocation)) {
                    bestLocation = new Location(location);
                }

                if (bestLocation.hasAccuracy() && bestLocation.getAccuracy() <= MAX_ACCEPTABLE_ACCURACY_M) {
                    sendLocation(bestLocation);
                    stopLocationUpdates();
                }
            }
        };

        try {
            if (gpsEnabled) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 500L, 0f, locationListener, Looper.getMainLooper());
            }
            if (networkEnabled) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper());
            }
        } catch (SecurityException e) {
            stopLocationUpdates();
            notifyJs("Location permission उपलब्ध नहीं है।", true);
            return;
        }

        handler.postDelayed(() -> {
            if (!locationRequestRunning) return;
            Location result = bestLocation;
            if (result != null && isUsableFreshLocation(result)
                    && result.hasAccuracy() && result.getAccuracy() <= MAX_ACCEPTABLE_ACCURACY_M) {
                sendLocation(result);
            } else {
                notifyJs("सटीक GPS location नहीं मिली। कृपया खुले स्थान में जाकर Location/GPS ON करके फिर कोशिश करें।", true);
            }
            stopLocationUpdates();
        }, LOCATION_TIMEOUT_MS);
    }

    private boolean isUsableFreshLocation(Location location) {
        if (location == null || !location.hasAccuracy()) return false;
        if (location.getAccuracy() <= 0f || location.getAccuracy() > 1000f) return false;

        long now = System.currentTimeMillis();
        long ageMs = now - location.getTime();
        if (ageMs < -10000L) return false;
        if (ageMs > 15000L) return false;

        if (Build.VERSION.SDK_INT >= 18 && location.isFromMockProvider()) return false;
        return true;
    }

    private boolean isBetter(Location a, Location b) {
        if (b == null) return true;
        if (!a.hasAccuracy()) return false;
        if (!b.hasAccuracy()) return true;
        return a.getAccuracy() + 5f < b.getAccuracy();
    }

    private void sendLocation(Location location) {
        if (location == null) return;
        final double lat = location.getLatitude();
        final double lon = location.getLongitude();
        final float accuracy = location.hasAccuracy() ? location.getAccuracy() : 0f;

        runOnUiThread(() -> {
            if (webView == null) return;
            String js = "if(typeof window.onNativeLocation==='function'){window.onNativeLocation(" +
                    lat + "," + lon + "," + accuracy + ");}" +
                    "else{window._pendingNativeLocation={lat:" + lat + ",lng:" + lon + ",accuracy:" + accuracy + "};}";
            webView.evaluateJavascript(js, null);
        });
    }

    private void notifyJs(String message, boolean error) {
        runOnUiThread(() -> {
            if (webView == null) return;
            String safe = message.replace("\\", "\\\\").replace("'", "\\'");
            webView.evaluateJavascript(
                    "if(typeof window.showNativeLocationMessage==='function'){window.showNativeLocationMessage('" + safe + "'," + error + ");}",
                    null);
            if (error) Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
        });
    }

    private void stopLocationUpdates() {
        locationRequestRunning = false;
        handler.removeCallbacksAndMessages(null);
        if (locationManager != null && locationListener != null) {
            try { locationManager.removeUpdates(locationListener); } catch (SecurityException ignored) {}
        }
        locationListener = null;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != LOCATION_REQUEST) return;
        if (hasFineLocation()) {
            notifyJs("सटीक Location permission मिल गई। अब 'मेरी वर्तमान लोकेशन' दबाएँ।", false);
        } else if (hasAnyLocationPermission()) {
            notifyJs("Approximate location मिली है। Ride pickup के लिए Precise Location चुनें।", true);
        } else {
            notifyJs("Location permission नहीं मिली।", true);
        }
    }

    private class AndroidLocationBridge {
        @JavascriptInterface public void requestLocation() { requestNativeLocation(); }
    }

    private class AssetWebViewClient extends WebViewClient {
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return serve(request.getUrl().getPath());
        }

        @Override public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
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
