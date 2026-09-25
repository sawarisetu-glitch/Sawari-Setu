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

    private Location bestGpsLocation = null;

    private Handler locationHandler = null;


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
         */
        webView.setWebViewClient(
                new LocalAssetWebViewClient()
        );


        webView.setWebChromeClient(
                new WebChromeClient() {

                    @Override
                    public void onGeolocationPermissionsShowPrompt(
                            String origin,
                            GeolocationPermissions.Callback callback) {

                        if (hasLocationPermission()) {

                            callback.invoke(
                                    origin,
                                    true,
                                    false
                            );

                        } else {

                            callback.invoke(
                                    origin,
                                    false,
                                    false
                            );
                        }
                    }
                }
        );


        /*
         * JavaScript -> Android GPS bridge
         */
        webView.addJavascriptInterface(
                new AndroidLocationBridge(),
                "AndroidLocation"
        );


        /*
         * Location permission
         */
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
         * Local HTML
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
     * JavaScript -> Android
     */
    private class AndroidLocationBridge {

        @JavascriptInterface
        public void requestLocation() {

            runOnUiThread(
                    new Runnable() {

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
                    }
            );
        }
    }


    /*
     * Fresh GPS location
     *
     * Network provider को यहाँ जानबूझकर
     * इस्तेमाल नहीं किया गया है।
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


        if (!hasLocationPermission()) {

            sendLocationError(
                    "Location permission नहीं मिली।"
            );

            return;
        }


        if (!locationManager.isProviderEnabled(
                LocationManager.GPS_PROVIDER
        )) {

            sendLocationError(
                    "फोन का GPS/Location बंद है।"
            );

            return;
        }


        waitingForFreshLocation = true;

        bestGpsLocation = null;


        /*
         * पुराने listener को पहले हटाएँ।
         */
        stopFreshLocationUpdates();


        /*
         * stopFreshLocationUpdates()
         * waitingForFreshLocation को false करता है,
         * इसलिए यहाँ फिर से true करेंगे।
         */
        waitingForFreshLocation = true;


        activeLocationListener =
                new LocationListener() {

                    @Override
                    public void onLocationChanged(
                            Location location) {

                        if (location == null) {
                            return;
                        }


                        /*
                         * केवल GPS provider की location स्वीकार करें।
                         */
                        if (!LocationManager.GPS_PROVIDER.equals(
                                location.getProvider()
                        )) {

                            return;
                        }


                        /*
                         * Mock location को reject करें,
                         * जहाँ Android इसे उपलब्ध कराता है।
                         */
                        if (android.os.Build.VERSION.SDK_INT >= 18) {

                            if (location.isFromMockProvider()) {
                                return;
                            }
                        }


                        /*
                         * पहली location को तुरंत accept नहीं करेंगे।
                         *
                         * जो location ज्यादा accurate होगी,
                         * उसे bestGpsLocation में रखेंगे।
                         */
                        if (bestGpsLocation == null) {

                            bestGpsLocation =
                                    new Location(location);

                        } else if (
                                location.hasAccuracy() &&
                                (
                                        !bestGpsLocation.hasAccuracy() ||
                                        location.getAccuracy() <
                                        bestGpsLocation.getAccuracy()
                                )
                        ) {

                            bestGpsLocation =
                                    new Location(location);
                        }


                        /*
                         * यदि accuracy 100 meter या उससे बेहतर है,
                         * तो location पर्याप्त अच्छी मानी जाएगी।
                         */
                        if (
                                location.hasAccuracy() &&
                                location.getAccuracy() <= 100f
                        ) {

                            Location finalLocation =
                                    new Location(location);

                            stopFreshLocationUpdates();

                            sendLocation(
                                    finalLocation.getLatitude(),
                                    finalLocation.getLongitude(),
                                    finalLocation.getAccuracy()
                            );
                        }
                    }


                    @Override
                    public void onProviderEnabled(
                            String provider) {
                    }


                    @Override
                    public void onProviderDisabled(
                            String provider) {

                        if (
                                LocationManager.GPS_PROVIDER.equals(
                                        provider
                                )
                        ) {

                            if (waitingForFreshLocation) {

                                stopFreshLocationUpdates();

                                sendLocationError(
                                        "GPS बंद हो गया। कृपया Location/GPS चालू रखें।"
                                );
                            }
                        }
                    }
                };


        try {

            /*
             * केवल GPS provider।
             *
             * 1 second interval.
             * 0 meter minimum distance.
             */
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0f,
                    activeLocationListener,
                    Looper.getMainLooper()
            );


        } catch (SecurityException e) {

            waitingForFreshLocation = false;

            activeLocationListener = null;

            sendLocationError(
                    "Location permission नहीं मिली।"
            );

            return;
        }


        /*
         * Maximum 30 seconds.
         */
        locationHandler =
                new Handler(
                        Looper.getMainLooper()
                );


        locationHandler.postDelayed(
                new Runnable() {

                    @Override
                    public void run() {

                        if (!waitingForFreshLocation) {
                            return;
                        }


                        /*
                         * अगर 30 sec में कोई GPS fix मिली है,
                         * तो सबसे accurate वाली इस्तेमाल करें।
                         */
                        if (bestGpsLocation != null) {

                            Location finalLocation =
                                    new Location(
                                            bestGpsLocation
                                    );


                            stopFreshLocationUpdates();


                            sendLocation(
                                    finalLocation.getLatitude(),
                                    finalLocation.getLongitude(),
                                    finalLocation.hasAccuracy()
                                            ?
                                            finalLocation.getAccuracy()
                                            :
                                            0f
                            );


                        } else {

                            stopFreshLocationUpdates();


                            sendLocationError(
                                    "Fresh GPS location नहीं मिली। " +
                                    "कृपया खुले स्थान में जाकर GPS/Location चालू रखें।"
                            );
                        }
                    }

                },
                30000L
        );
    }


    /*
     * GPS updates बंद करें।
     */
    private void stopFreshLocationUpdates() {

        waitingForFreshLocation = false;


        if (locationHandler != null) {

            locationHandler.removeCallbacksAndMessages(
                    null
            );

            locationHandler = null;
        }


        if (
                locationManager != null &&
                activeLocationListener != null
        ) {

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
     * Android GPS -> JavaScript
     */
    private void sendLocation(
            double latitude,
            double longitude,
            float accuracy) {

        final String js =
                String.format(
                        Locale.US,
                        "window.onNativeLocation(%f,%f,%f);",
                        latitude,
                        longitude,
                        accuracy
                );


        if (webView == null) {
            return;
        }


        webView.post(
                new Runnable() {

                    @Override
                    public void run() {

                        webView.evaluateJavascript(
                                js,
                                null
                        );
                    }
                }
        );
    }


    private void sendLocationError(
            String message) {

        final String safeMessage =
                message
                        .replace(
                                "\\",
                                "\\\\"
                        )
                        .replace(
                                "'",
                                "\\'"
                        )
                        .replace(
                                "\n",
                                " "
                        );


        final String js =
                "alert('" +
                safeMessage +
                "');";


        if (webView == null) {
            return;
        }


        webView.post(
                new Runnable() {

                    @Override
                    public void run() {

                        webView.evaluateJavascript(
                                js,
                                null
                        );
                    }
                }
        );
    }


    /*
     * Local assets को HTTPS-style URL पर serve करना।
     */
    private class LocalAssetWebViewClient
            extends WebViewClient {

        private final AssetManager assetManager =
                getAssets();


        @Override
        public WebResourceResponse shouldInterceptRequest(
                WebView view,
                WebResourceRequest request) {

            return loadLocalAsset(
                    request.getUrl()
            );
        }


        @Override
        @SuppressWarnings("deprecation")
        public WebResourceResponse shouldInterceptRequest(
                WebView view,
                String url) {

            return loadLocalAsset(
                    Uri.parse(url)
            );
        }


        private WebResourceResponse loadLocalAsset(
                Uri uri) {

            if (uri == null) {
                return null;
            }


            String host =
                    uri.getHost();


            if (
                    !"appassets.androidplatform.net"
                            .equals(host)
            ) {

                return null;
            }


            String path =
                    uri.getPath();


            if (
                    path == null ||
                    !path.startsWith("/assets/")
            ) {

                return null;
            }


            String assetPath =
                    path.substring(
                            "/assets/".length()
                    );


            if (assetPath.length() == 0) {
                return null;
            }


            try {

                InputStream inputStream =
                        assetManager.open(
                                assetPath
                        );


                String mimeType =
                        getMimeType(
                                assetPath
                        );


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


    private String getMimeType(
            String path) {

        String lower =
                path.toLowerCase(
                        Locale.US
                );


        if (
                lower.endsWith(".html") ||
                lower.endsWith(".htm")
        ) {

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


        if (
                lower.endsWith(".jpg") ||
                lower.endsWith(".jpeg")
        ) {

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


        if (
                requestCode ==
                LOCATION_REQUEST_CODE
        ) {

            if (!hasLocationPermission()) {

                sendLocationError(
                        "Location permission नहीं मिली।"
                );
            }
        }
    }


    @Override
    protected void onDestroy() {

        stopFreshLocationUpdates();


        if (webView != null) {

            webView.destroy();

            webView = null;
        }


        super.onDestroy();
    }


    @Override
    public void onBackPressed() {

        if (
                webView != null &&
                webView.canGoBack()
        ) {

            webView.goBack();

        } else {

            super.onBackPressed();
        }
    }
}
