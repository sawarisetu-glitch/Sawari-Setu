package com.sawarisetu.app;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
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
import java.util.Locale;

public class MainActivity extends Activity {

    private WebView webView;
    private LocationManager locationManager;

    private static final int LOCATION_REQUEST_CODE = 10;

    private LocationListener activeLocationListener;
    private boolean waitingForFreshLocation = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        locationManager =
                (LocationManager) getSystemService(LOCATION_SERVICE);

        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setGeolocationEnabled(true);

        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);

        /*
         * Local HTML को HTTPS-style origin से serve करेंगे।
         * इससे fetch(), Leaflet, Nominatim, Photon और OSRM
         * जैसी web requests file:// origin की समस्या से बचती हैं।
         */
        webView.setWebViewClient(new LocalAssetWebViewClient());

        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onGeolocationPermissionsShowPrompt(
                    String origin,
                    GeolocationPermissions.Callback callback) {

                if (hasLocationPermission()) {
                    callback.invoke(origin, true, false);
                } else {
                    callback.invoke(origin, false, false);
                }
            }
        });

        /*
         * index.html में पहले से:
         *
         * window.AndroidLocation.requestLocation()
         *
         * मौजूद है।
         */
        webView.addJavascriptInterface(
                new AndroidLocationBridge(),
                "AndroidLocation"
        );

        if (!hasLocationPermission()) {

            requestPermissions(
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    LOCATION_REQUEST_CODE
            );
        }

        /*
         * file:///android_asset/index.html की जगह
         * HTTPS-style local origin।
         */
        webView.loadUrl(
                "https://appassets.androidplatform.net/assets/index.html"
        );
    }

    private boolean hasLocationPermission() {

        return checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
                ||
                checkSelfPermission(
                        Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED;
    }

    /*
     * JavaScript -> Android GPS bridge
     */
    private class AndroidLocationBridge {

        @JavascriptInterface
        public void requestLocation() {

            runOnUiThread(new Runnable() {
                @Override
                public void run() {

                    if (!hasLocationPermission()) {

                        requestPermissions(
                                new String[]{
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                },
                                LOCATION_REQUEST_CODE
                        );

                        return;
                    }

                    requestFreshLocation();
                }
            });
        }
    }

    /*
     * केवल नई/fresh location मांगते हैं।
     * पुरानी getLastKnownLocation() को इस्तेमाल नहीं करते।
     */
    private void requestFreshLocation() {

        if (waitingForFreshLocation) {
            return;
        }

        if (locationManager == null) {
            sendLocationError(
                    "Location service उपलब्ध नहीं है।"
            );
            return;
        }

        waitingForFreshLocation = true;

        try {

            activeLocationListener = new LocationListener() {

                @Override
                public void onLocationChanged(Location location) {

                    if (location == null) {
                        return;
                    }

                    /*
                     * पहली fresh location मिलते ही
                     * GPS request बंद कर दें।
                     */
                    stopFreshLocationUpdates();

                    sendLocation(
                            location.getLatitude(),
                            location.getLongitude(),
                            location.getAccuracy()
                    );
                }

                @Override
                public void onProviderEnabled(String provider) {
                }

                @Override
                public void onProviderDisabled(String provider) {
                }
            };

            boolean requested = false;

            /*
             * GPS provider
             */
            if (locationManager.isProviderEnabled(
                    LocationManager.GPS_PROVIDER)) {

                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        1000L,
                        0f,
                        activeLocationListener,
                        Looper.getMainLooper()
                );

                requested = true;
            }

            /*
             * Network provider
             * GPS के साथ parallel में चल सकता है।
             */
            if (locationManager.isProviderEnabled(
                    LocationManager.NETWORK_PROVIDER)) {

                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        1000L,
                        0f,
                        activeLocationListener,
                        Looper.getMainLooper()
                );

                requested = true;
            }

            if (!requested) {

                waitingForFreshLocation = false;

                sendLocationError(
                        "फोन का Location/GPS बंद है।"
                );

                return;
            }

            /*
             * अधिकतम 30 seconds तक fresh fix का इंतजार।
             */
            final Handler handler =
                    new Handler(Looper.getMainLooper());

            handler.postDelayed(new Runnable() {

                @Override
                public void run() {

                    if (waitingForFreshLocation) {

                        stopFreshLocationUpdates();

                        sendLocationError(
                                "Fresh GPS location नहीं मिली। " +
                                "कृपया खुले स्थान में GPS/Location चालू रखें।"
                        );
                    }
                }

            }, 30000L);

        } catch (SecurityException e) {

            waitingForFreshLocation = false;

            sendLocationError(
                    "Location permission नहीं मिली।"
            );
        }
    }

    private void stopFreshLocationUpdates() {

        waitingForFreshLocation = false;

        if (locationManager != null &&
                activeLocationListener != null) {

            try {
                locationManager.removeUpdates(
                        activeLocationListener
                );
            } catch (SecurityException ignored) {
            }
        }

        activeLocationListener = null;
    }

    /*
     * Native Android location -> index.html
     */
    private void sendLocation(
            double latitude,
            double longitude,
            float accuracy) {

        final String js = String.format(
                Locale.US,
                "window.onNativeLocation(%f,%f,%f);",
                latitude,
                longitude,
                accuracy
        );

        webView.post(new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript(js, null);
            }
        });
    }

    private void sendLocationError(String message) {

        final String safeMessage =
                message
                        .replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\n", " ");

        final String js =
                "alert('" + safeMessage + "');";

        webView.post(new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript(js, null);
            }
        });
    }

    /*
     * Android local assets को HTTPS-style URL पर serve करता है।
     *
     * https://appassets.androidplatform.net/assets/index.html
     *                  |
     *                  +--> assets/index.html
     */
    private class LocalAssetWebViewClient
            extends WebViewClient {

        private final AssetManager assetManager =
                getAssets();

        @Override
        public WebResourceResponse shouldInterceptRequest(
                WebView view,
                WebResourceRequest request) {

            return loadLocalAsset(request.getUrl());
        }

        @Override
        @SuppressWarnings("deprecation")
        public WebResourceResponse shouldInterceptRequest(
                WebView view,
                String url) {

            return loadLocalAsset(Uri.parse(url));
        }

        private WebResourceResponse loadLocalAsset(
                Uri uri) {

            if (uri == null) {
                return null;
            }

            String host = uri.getHost();

            if (!"appassets.androidplatform.net".equals(host)) {
                return null;
            }

            String path = uri.getPath();

            if (path == null ||
                    !path.startsWith("/assets/")) {
                return null;
            }

            String assetPath =
                    path.substring("/assets/".length());

            if (assetPath.length() == 0) {
                return null;
            }

            try {

                InputStream inputStream =
                        assetManager.open(assetPath);

                String mimeType =
                        getMimeType(assetPath);

                return new WebResourceResponse(
                        mimeType,
                        "UTF-8",
                        inputStream
                );

            } catch (IOException e) {

                return null;
            }
        }
    }

    private String getMimeType(String path) {

        String lower =
                path.toLowerCase(Locale.US);

        if (lower.endsWith(".html") ||
                lower.endsWith(".htm")) {
            return "text/html";
        }

        if (lower.endsWith(".js")) {
            return "application/javascript";
        }

        if (lower.endsWith(".css")) {
            return "text/css";
        }

        if (lower.endsWith(".json")) {
            return "application/json";
        }

        if (lower.endsWith(".png")) {
            return "image/png";
        }

        if (lower.endsWith(".jpg") ||
                lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }

        if (lower.endsWith(".gif")) {
            return "image/gif";
        }

        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }

        if (lower.endsWith(".ico")) {
            return "image/x-icon";
        }

        return "application/octet-stream";
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {

        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode == LOCATION_REQUEST_CODE) {

            if (!hasLocationPermission()) {

                sendLocationError(
                        "Location permission नहीं मिली।"
                );
            }

            /*
             * Permission मिलने के बाद automatic old/cached
             * location नहीं भेजते।
             *
             * User जब GPS button दबाएगा तभी fresh request होगी।
             */
        }
    }

    @Override
    protected void onDestroy() {

        stopFreshLocationUpdates();

        if (webView != null) {
            webView.destroy();
        }

        super.onDestroy();
    }

    @Override
    public void onBackPressed() {

        if (webView != null &&
                webView.canGoBack()) {

            webView.goBack();

        } else {

            super.onBackPressed();
        }
    }
}
