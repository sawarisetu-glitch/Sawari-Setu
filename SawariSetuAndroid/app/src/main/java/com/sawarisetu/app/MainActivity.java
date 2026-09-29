package com.sawarisetu.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient.FileChooserParams;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;

public class MainActivity extends Activity {

    private static final String ORIGIN = "https://sawarisetu.local/";

    private static final int LOCATION_PERMISSION_REQUEST = 10;
    private static final int CAMERA_PERMISSION_REQUEST = 20;
    private static final int FILE_CHOOSER_REQUEST = 30;

    private WebView webView;

    private LocationManager locationManager;

    private Location bestLocation;

    private LocationListener locationListener;

    private LocationListener tripLocationListener;

    private boolean tripTracking = false;

    private final Handler handler = new Handler();

    private ValueCallback<Uri[]> filePathCallback;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ========================================================
        // SYSTEM UI
        // ========================================================

        WindowSetup();


        // ========================================================
        // WEBVIEW
        // ========================================================

        webView = new WebView(this);

        setContentView(webView);

        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        settings.setGeolocationEnabled(true);

        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        if (android.os.Build.VERSION.SDK_INT >= 16) {
            settings.setAllowFileAccessFromFileURLs(true);
            settings.setAllowUniversalAccessFromFileURLs(true);
        }


        // ========================================================
        // WEBVIEW CLIENT
        // ========================================================

        webView.setWebViewClient(new AssetWebViewClient());


        // ========================================================
        // WEB CHROME CLIENT
        // GPS + FILE/CAMERA
        // ========================================================

        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onGeolocationPermissionsShowPrompt(
                    String origin,
                    GeolocationPermissions.Callback callback) {

                if (callback != null) {
                    callback.invoke(origin, true, false);
                }
            }


            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams) {

                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }

                MainActivity.this.filePathCallback = filePathCallback;

                if (android.os.Build.VERSION.SDK_INT >= 23 &&
                        checkSelfPermission(Manifest.permission.CAMERA)
                                != PackageManager.PERMISSION_GRANTED) {

                    requestPermissions(
                            new String[]{
                                    Manifest.permission.CAMERA
                            },
                            CAMERA_PERMISSION_REQUEST
                    );

                    return true;
                }

                openFileChooser();

                return true;
            }
        });


        // ========================================================
        // JAVASCRIPT ↔ ANDROID BRIDGE
        // ========================================================

        webView.addJavascriptInterface(
                new AndroidLocationBridge(),
                "AndroidLocation"
        );


        // ========================================================
        // LOCATION PERMISSION
        // ========================================================

        requestLocationPermission();


        // ========================================================
        // LOAD MASTER INDEX.HTML
        // ========================================================

        webView.loadUrl(ORIGIN + "index.html");
    }


    // ============================================================
    // SYSTEM UI
    // ============================================================

    private void WindowSetup() {

        getWindow().setStatusBarColor(
                Color.rgb(7, 82, 62)
        );

        getWindow().setNavigationBarColor(
                Color.rgb(244, 247, 251)
        );

        if (android.os.Build.VERSION.SDK_INT >= 30) {

            getWindow().setDecorFitsSystemWindows(true);

        } else {

            getWindow()
                    .getDecorView()
                    .setSystemUiVisibility(0);
        }
    }


    // ============================================================
    // LOCATION PERMISSION
    // ============================================================

    private void requestLocationPermission() {

        if (android.os.Build.VERSION.SDK_INT < 23) {
            return;
        }

        boolean fine =
                checkSelfPermission(
                        Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED;

        boolean coarse =
                checkSelfPermission(
                        Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED;

        if (!fine && !coarse) {

            requestPermissions(
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    LOCATION_PERMISSION_REQUEST
            );
        }
    }


    // ============================================================
    // CURRENT LOCATION
    // ============================================================

    private void requestNativeLocation() {

        if (android.os.Build.VERSION.SDK_INT >= 23) {

            boolean fine =
                    checkSelfPermission(
                            Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED;

            boolean coarse =
                    checkSelfPermission(
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED;

            if (!fine && !coarse) {

                requestLocationPermission();

                runOnUiThread(() ->
                        webView.evaluateJavascript(
                                "alert('Location permission दें, फिर Current Location दोबारा दबाएँ।')",
                                null
                        )
                );

                return;
            }
        }


        locationManager =
                (LocationManager) getSystemService(
                        LOCATION_SERVICE
                );

        bestLocation = null;


        locationListener = new LocationListener() {

            @Override
            public void onLocationChanged(Location location) {

                if (location == null) {
                    return;
                }

                if (bestLocation == null ||
                        location.getAccuracy() <
                                bestLocation.getAccuracy()) {

                    bestLocation = location;
                }


                if (location.hasAccuracy() &&
                        location.getAccuracy() <= 50f) {

                    sendLocation(bestLocation);

                    stopLocationUpdates();
                }
            }


            @Override
            public void onStatusChanged(
                    String provider,
                    int status,
                    Bundle extras) {
            }


            @Override
            public void onProviderEnabled(
                    String provider) {
            }


            @Override
            public void onProviderDisabled(
                    String provider) {
            }
        };


        try {

            if (locationManager.isProviderEnabled(
                    LocationManager.GPS_PROVIDER)) {

                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        1000L,
                        0f,
                        locationListener
                );
            }


            if (locationManager.isProviderEnabled(
                    LocationManager.NETWORK_PROVIDER)) {

                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        1000L,
                        0f,
                        locationListener
                );
            }

        } catch (SecurityException e) {

            showLocationError(
                    "Location permission नहीं मिली।"
            );

            return;
        }


        handler.postDelayed(() -> {

            if (bestLocation != null) {

                sendLocation(bestLocation);

            } else {

                showLocationError(
                        "GPS location नहीं मिली। Phone का Location/GPS ON करें।"
                );
            }

            stopLocationUpdates();

        }, 20000L);
    }


    private void sendLocation(Location location) {

        if (location == null ||
                webView == null) {

            return;
        }

        final double latitude =
                location.getLatitude();

        final double longitude =
                location.getLongitude();

        final float accuracy =
                location.hasAccuracy()
                        ? location.getAccuracy()
                        : 0f;


        runOnUiThread(() ->
                webView.evaluateJavascript(
                        "if(typeof window.onNativeLocation==='function')" +
                                "{window.onNativeLocation(" +
                                latitude + "," +
                                longitude + "," +
                                accuracy +
                                ");}",
                        null
                )
        );
    }


    private void showLocationError(String message) {

        runOnUiThread(() -> {

            if (webView != null) {

                webView.evaluateJavascript(
                        "alert(" +
                                JSONObjectQuote(message) +
                                ")",
                        null
                );
            }
        });
    }


    private String JSONObjectQuote(String value) {

        if (value == null) {
            return "\"\"";
        }

        return "\"" +
                value
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r") +
                "\"";
    }


    // ============================================================
    // TRIP TRACKING
    // ============================================================

    private void startTripTracking(
            final String bookingId) {

        if (locationManager == null) {

            locationManager =
                    (LocationManager)
                            getSystemService(
                                    LOCATION_SERVICE
                            );
        }


        if (!hasLocationPermission()) {
            return;
        }


        stopTripTracking();

        tripTracking = true;


        tripLocationListener =
                new LocationListener() {

                    @Override
                    public void onLocationChanged(
                            Location location) {

                        if (!tripTracking ||
                                location == null) {

                            return;
                        }

                        sendTripLocationToJs(
                                location
                        );
                    }


                    @Override
                    public void onStatusChanged(
                            String provider,
                            int status,
                            Bundle extras) {
                    }


                    @Override
                    public void onProviderEnabled(
                            String provider) {
                    }


                    @Override
                    public void onProviderDisabled(
                            String provider) {
                    }
                };


        try {

            if (locationManager.isProviderEnabled(
                    LocationManager.GPS_PROVIDER)) {

                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        10000L,
                        10f,
                        tripLocationListener
                );
            }


            if (locationManager.isProviderEnabled(
                    LocationManager.NETWORK_PROVIDER)) {

                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        10000L,
                        10f,
                        tripLocationListener
                );
            }

        } catch (SecurityException e) {

            tripTracking = false;
        }
    }


    private boolean hasLocationPermission() {

        if (android.os.Build.VERSION.SDK_INT < 23) {
            return true;
        }

        return checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

                ||

                checkSelfPermission(
                        Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED;
    }


    private void sendTripLocationToJs(
            Location location) {

        final double latitude =
                location.getLatitude();

        final double longitude =
                location.getLongitude();

        final float accuracy =
                location.hasAccuracy()
                        ? location.getAccuracy()
                        : 0f;

        final long timestamp =
                System.currentTimeMillis();


        runOnUiThread(() -> {

            if (webView == null) {
                return;
            }


            webView.evaluateJavascript(
                    "if(typeof window.onTripLocation==='function')" +
                            "{window.onTripLocation(" +
                            latitude + "," +
                            longitude + "," +
                            accuracy + "," +
                            timestamp +
                            ");}",
                    null
            );
        });
    }


    private void stopTripTracking() {

        tripTracking = false;


        if (locationManager != null &&
                tripLocationListener != null) {

            try {

                locationManager.removeUpdates(
                        tripLocationListener
                );

            } catch (Exception ignored) {
            }
        }


        tripLocationListener = null;
    }


    private void stopLocationUpdates() {

        handler.removeCallbacksAndMessages(null);


        if (locationManager != null &&
                locationListener != null) {

            try {

                locationManager.removeUpdates(
                        locationListener
                );

            } catch (SecurityException ignored) {
            }
        }


        locationListener = null;
    }


    // ============================================================
    // JAVASCRIPT BRIDGE
    // ============================================================

    private class AndroidLocationBridge {

        @JavascriptInterface
        public void requestLocation() {

            runOnUiThread(() ->
                    requestNativeLocation()
            );
        }


        @JavascriptInterface
        public void startTripTracking(
                String bookingId) {

            runOnUiThread(() ->
                    MainActivity.this
                            .startTripTracking(
                                    bookingId
                            )
            );
        }


        @JavascriptInterface
        public void stopTripTracking() {

            runOnUiThread(() ->
                    MainActivity.this
                            .stopTripTracking()
            );
        }
    }


    // ============================================================
    // CALL / WHATSAPP / EMAIL / GEO
    // ============================================================

    private boolean handleExternalUrl(
            String url) {

        if (url == null ||
                url.trim().isEmpty()) {

            return false;
        }


        try {

            Uri uri = Uri.parse(url);

            String scheme =
                    uri.getScheme();

            if (scheme == null) {
                return false;
            }


            scheme =
                    scheme.toLowerCase();


            // -------------------------
            // PHONE CALL
            // -------------------------

            if (scheme.equals("tel")) {

                Intent intent =
                        new Intent(
                                Intent.ACTION_DIAL,
                                uri
                        );

                startActivity(intent);

                return true;
            }


            // -------------------------
            // EMAIL
            // -------------------------

            if (scheme.equals("mailto")) {

                Intent intent =
                        new Intent(
                                Intent.ACTION_SENDTO,
                                uri
                        );

                startActivity(intent);

                return true;
            }


            // -------------------------
            // GEO / MAPS
            // -------------------------

            if (scheme.equals("geo")) {

                Intent intent =
                        new Intent(
                                Intent.ACTION_VIEW,
                                uri
                        );

                startActivity(intent);

                return true;
            }


            // -------------------------
            // WHATSAPP
            // -------------------------

            if (scheme.equals("whatsapp")) {

                try {

                    Intent intent =
                            new Intent(
                                    Intent.ACTION_VIEW,
                                    uri
                            );

                    startActivity(intent);

                    return true;

                } catch (Exception whatsappError) {

                    // WhatsApp installed नहीं है तो
                    // https://wa.me fallback

                    try {

                        String phone =
                                uri.getQueryParameter(
                                        "phone"
                                );

                        String text =
                                uri.getQueryParameter(
                                        "text"
                                );

                        if (phone != null &&
                                !phone.isEmpty()) {

                            String fallback =
                                    "https://wa.me/" +
                                            phone;

                            if (text != null &&
                                    !text.isEmpty()) {

                                fallback +=
                                        "?text=" +
                                                Uri.encode(
                                                        text
                                                );
                            }


                            Intent browser =
                                    new Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse(
                                                    fallback
                                            )
                                    );

                            startActivity(
                                    browser
                            );

                            return true;
                        }

                    } catch (Exception ignored) {
                    }
                }
            }

        } catch (Exception e) {

            return false;
        }


        return false;
    }


    // ============================================================
    // FILE / PHOTO PICKER
    // ============================================================

    private void openFileChooser() {

        if (filePathCallback == null) {
            return;
        }


        try {

            Intent intent =
                    new Intent(
                            Intent.ACTION_GET_CONTENT
                    );

            intent.addCategory(
                    Intent.CATEGORY_OPENABLE
            );

            intent.setType("*/*");


            startActivityForResult(
                    Intent.createChooser(
                            intent,
                            "फोटो / दस्तावेज़ चुनें"
                    ),
                    FILE_CHOOSER_REQUEST
            );

        } catch (Exception e) {

            if (filePathCallback != null) {

                filePathCallback.onReceiveValue(
                        null
                );

                filePathCallback = null;
            }
        }
    }


    // ============================================================
    // ACTIVITY RESULT
    // ============================================================

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data) {

        if (requestCode ==
                FILE_CHOOSER_REQUEST) {

            if (filePathCallback == null) {

                super.onActivityResult(
                        requestCode,
                        resultCode,
                        data
                );

                return;
            }


            Uri[] results = null;


            if (resultCode == RESULT_OK &&
                    data != null) {

                if (data.getClipData() != null) {

                    int count =
                            data.getClipData()
                                    .getItemCount();

                    results =
                            new Uri[count];


                    for (int i = 0;
                         i < count;
                         i++) {

                        results[i] =
                                data.getClipData()
                                        .getItemAt(i)
                                        .getUri();
                    }

                } else if (data.getData() != null) {

                    results =
                            new Uri[]{
                                    data.getData()
                            };
                }
            }


            filePathCallback.onReceiveValue(
                    results
            );

            filePathCallback = null;


            return;
        }


        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );
    }


    // ============================================================
    // PERMISSION RESULT
    // ============================================================

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


        if (requestCode ==
                CAMERA_PERMISSION_REQUEST) {

            boolean granted = false;


            for (int result : grantResults) {

                if (result ==
                        PackageManager.PERMISSION_GRANTED) {

                    granted = true;
                    break;
                }
            }


            if (granted) {

                handler.postDelayed(
                        () -> openFileChooser(),
                        300
                );

            } else {

                if (filePathCallback != null) {

                    filePathCallback.onReceiveValue(
                            null
                    );

                    filePathCallback = null;
                }
            }
        }
    }


    // ============================================================
    // ASSET WEBVIEW CLIENT
    // ============================================================

    private class AssetWebViewClient
            extends WebViewClient {


        @Override
        public boolean shouldOverrideUrlLoading(
                WebView view,
                WebResourceRequest request) {

            if (request == null ||
                    request.getUrl() == null) {

                return false;
            }


            String url =
                    request.getUrl().toString();


            return handleExternalUrl(url);
        }


        @Override
        public boolean shouldOverrideUrlLoading(
                WebView view,
                String url) {

            return handleExternalUrl(url);
        }


        @Override
        public WebResourceResponse
        shouldInterceptRequest(
                WebView view,
                WebResourceRequest request) {

            if (request == null ||
                    request.getUrl() == null) {

                return null;
            }


            return serve(
                    request.getUrl().getPath()
            );
        }


        @Override
        public WebResourceResponse
        shouldInterceptRequest(
                WebView view,
                String url) {

            try {

                return serve(
                        Uri.parse(url).getPath()
                );

            } catch (Exception e) {

                return null;
            }
        }


        private WebResourceResponse serve(
                String path) {

            if (path == null) {
                return null;
            }


            String asset = null;
            String mime = null;


            if (path.equals("/") ||
                    path.equals("/index.html")) {

                asset = "index.html";
                mime = "text/html";

            } else if (
                    path.equals("/logo.png")) {

                asset = "logo.png";
                mime = "image/png";
            }


            if (asset == null) {
                return null;
            }


            try {

                InputStream inputStream =
                        getAssets().open(
                                asset
                        );


                return new WebResourceResponse(
                        mime,
                        "UTF-8",
                        inputStream
                );

            } catch (IOException e) {

                return null;
            }
        }
    }


    // ============================================================
    // DESTROY
    // ============================================================

    @Override
    protected void onDestroy() {

        stopLocationUpdates();

        stopTripTracking();


        if (filePathCallback != null) {

            filePathCallback.onReceiveValue(
                    null
            );

            filePathCallback = null;
        }


        if (webView != null) {

            webView.stopLoading();
            webView.destroy();
            webView = null;
        }


        super.onDestroy();
    }


    // ============================================================
    // BACK BUTTON
    // ============================================================

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
