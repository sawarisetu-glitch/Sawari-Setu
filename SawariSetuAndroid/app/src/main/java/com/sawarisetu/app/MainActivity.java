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
    private static final int PERMISSION_REQUEST_CODE = 101;

    private WebView webView;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isLocationRequested = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        // WebView सेटिंग्स कॉन्फ़िगरेशन
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
                // वेबव्यू में HTML5 Geolocation को स्वचालित अनुमति
                callback.invoke(origin, true, false);
            }
        });

        // JavaScript और Android का ब्रिज जोड़ना
        webView.addJavascriptInterface(new AndroidLocationBridge(), "AndroidLocation");

        // लोकेशन परमिशन की जांच
        checkLocationPermission();

        // लोकल HTML फाइल लोड करना
        webView.loadUrl(ORIGIN + "index.html");
    }

    private boolean checkLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }, PERMISSION_REQUEST_CODE);
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (isLocationRequested) {
                    requestNativeLocation();
                }
            } else {
                Toast.makeText(this, "सटीक लोकेशन के लिए जीपीएस परमिशन आवश्यक है", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void requestNativeLocation() {
        isLocationRequested = true;

        if (!checkLocationPermission()) {
            return;
        }

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) return;

        boolean isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

        if (!isGpsEnabled && !isNetworkEnabled) {
            runOnUiThread(() -> {
                Toast.makeText(this, "कृपया फ़ोन का GPS (Location) चालू करें", Toast.LENGTH_LONG).show();
                webView.evaluateJavascript("if(window.showNativeLocationMessage) window.showNativeLocationMessage('कृपया GPS चालू करें', true);", null);
            });
            return;
        }

        // 1. तुरंत रिस्पॉन्स के लिए सबसे ताज़ा लोकेशन (Last Known Location) भेजें
        try {
            Location lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location lastNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            Location bestRecent = null;

            if (lastGps != null && lastNetwork != null) {
                bestRecent = (lastGps.getTime() >= lastNetwork.getTime()) ? lastGps : lastNetwork;
            } else if (lastGps != null) {
                bestRecent = lastGps;
            } else {
                bestRecent = lastNetwork;
            }

            // अगर 15 मिनट से ताज़ा लोकेशन मौजूद है तो तुरंत भेजें
            if (bestRecent != null && (System.currentTimeMillis() - bestRecent.getTime()) < 15 * 60 * 1000) {
                sendLocationToWeb(bestRecent);
            }
        } catch (SecurityException ignored) {}

        // 2. लाइव सटीक सैटेलाइट लोकेशन प्राप्त करना
        stopLocationUpdates();

        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (location == null) return;

                // 60 मीटर या उससे बेहतर सटीकता मिलते ही वेबव्यू को अपडेट करें
                if (location.hasAccuracy() && location.getAccuracy() <= 60f) {
                    sendLocationToWeb(location);
                    stopLocationUpdates();
                } else {
                    sendLocationToWeb(location);
                }
            }

            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };

        try {
            if (isGpsEnabled) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, locationListener);
            }
            if (isNetworkEnabled) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 1f, locationListener);
            }
        } catch (SecurityException e) {
            e.printStackTrace();
        }

        // 15 सेकंड बाद बैटरी बचाने के लिए जीपीएस लिसनर बंद करें
        handler.postDelayed(this::stopLocationUpdates, 15000L);
    }

    private void sendLocationToWeb(Location l) {
        if (l == null) return;
        final double lat = l.getLatitude();
        final double lon = l.getLongitude();
        final float acc = l.hasAccuracy() ? l.getAccuracy() : 20f;

        runOnUiThread(() -> webView.evaluateJavascript(
                "if(window.onNativeLocation){ window.onNativeLocation(" + lat + "," + lon + "," + acc + "); }",
                null
        ));
    }

    private void stopLocationUpdates() {
        handler.removeCallbacksAndMessages(null);
        if (locationManager != null && locationListener != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (SecurityException ignored) {}
            locationListener = null;
        }
    }

    private class AndroidLocationBridge {
        @JavascriptInterface
        public void requestLocation() {
            runOnUiThread(() -> requestNativeLocation());
        }
    }

    private class AssetWebViewClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return serve(request.getUrl().getPath());
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            try {
                return serve(android.net.Uri.parse(url).getPath());
            } catch (Exception e) {
                return null;
            }
        }

        private WebResourceResponse serve(String path) {
            if (path == null) return null;
            String asset = null, mime = null;

            if (path.equals("/") || path.equals("/index.html")) {
                asset = "index.html";
                mime = "text/html";
            } else if (path.equals("/logo.png")) {
                asset = "logo.png";
                mime = "image/png";
            }

            if (asset == null) return null;

            try {
                InputStream in = getAssets().open(asset);
                return new WebResourceResponse(mime, "UTF-8", in);
            } catch (IOException e) {
                return null;
            }
        }
    }

    @Override
    protected void onDestroy() {
        stopLocationUpdates();
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
