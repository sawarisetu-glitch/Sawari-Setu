package com.sawarisetu.app;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import android.os.Handler;
import android.os.Looper;

public class MainActivity extends Activity {

    private WebView webView;
    private LocationManager locationManager;

    private static final int LOCATION_REQUEST_CODE = 10;

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

        webView.setWebViewClient(new WebViewClient());

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

        // JavaScript से Native GPS को call करने के लिए bridge
        webView.addJavascriptInterface(
                new AndroidLocationBridge(),
                "AndroidLocation"
        );

        locationManager =
                (LocationManager) getSystemService(LOCATION_SERVICE);

        if (!hasLocationPermission()) {
            requestPermissions(
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    LOCATION_REQUEST_CODE
            );
        }

        webView.loadUrl("file:///android_asset/index.html");
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

    public class AndroidLocationBridge {

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

                    getNativeLocation();
                }
            });
        }
    }

    private void getNativeLocation() {

        if (locationManager == null) {
            sendLocationError("Location service उपलब्ध नहीं है।");
            return;
        }

        try {

            Location bestLocation = null;

            if (locationManager.isProviderEnabled(
                    LocationManager.GPS_PROVIDER)) {

                Location gpsLocation =
                        locationManager.getLastKnownLocation(
                                LocationManager.GPS_PROVIDER
                        );

                if (gpsLocation != null) {
                    bestLocation = gpsLocation;
                }
            }

            if (locationManager.isProviderEnabled(
                    LocationManager.NETWORK_PROVIDER)) {

                Location networkLocation =
                        locationManager.getLastKnownLocation(
                                LocationManager.NETWORK_PROVIDER
                        );

                if (networkLocation != null &&
                        (bestLocation == null ||
                         networkLocation.getAccuracy()
                                 < bestLocation.getAccuracy())) {

                    bestLocation = networkLocation;
                }
            }

            if (bestLocation != null) {

                sendLocation(
                        bestLocation.getLatitude(),
                        bestLocation.getLongitude(),
                        bestLocation.getAccuracy()
                );

                return;
            }

            requestFreshLocation();

        } catch (SecurityException e) {

            sendLocationError(
                    "Location permission नहीं मिली।"
            );
        }
    }

    private void requestFreshLocation() {

        try {

            LocationListener listener = new LocationListener() {

                @Override
                public void onLocationChanged(Location location) {

                    sendLocation(
                            location.getLatitude(),
                            location.getLongitude(),
                            location.getAccuracy()
                    );

                    try {
                        locationManager.removeUpdates(this);
                    } catch (SecurityException ignored) {
                    }
                }

                @Override
                public void onProviderEnabled(String provider) {
                }

                @Override
                public void onProviderDisabled(String provider) {
                }
            };

            boolean requested = false;

            if (locationManager.isProviderEnabled(
                    LocationManager.GPS_PROVIDER)) {

                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        1000,
                        1,
                        listener,
                        Looper.getMainLooper()
                );

                requested = true;
            }

            if (locationManager.isProviderEnabled(
                    LocationManager.NETWORK_PROVIDER)) {

                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        1000,
                        1,
                        listener,
                        Looper.getMainLooper()
                );

                requested = true;
            }

            if (!requested) {
                sendLocationError(
                        "फोन का Location/GPS बंद है।"
                );
                return;
            }

            final LocationListener activeListener = listener;

            new Handler(Looper.getMainLooper()).postDelayed(
                    new Runnable() {
                        @Override
                        public void run() {
                            try {
                                locationManager.removeUpdates(
                                        activeListener
                                );
                            } catch (SecurityException ignored) {
                            }
                        }
                    },
                    30000
            );

        } catch (SecurityException e) {

            sendLocationError(
                    "Location permission नहीं मिली।"
            );
        }
    }

    private void sendLocation(
            double lat,
            double lon,
            float accuracy) {

        String js =
                "window.onNativeLocation(" +
                lat + "," +
                lon + "," +
                accuracy +
                ");";

        webView.post(new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript(js, null);
            }
        });
    }

    private void sendLocationError(String message) {

        String safeMessage =
                message.replace("\\", "\\\\")
                       .replace("'", "\\'");

        String js =
                "alert('" + safeMessage + "');";

        webView.post(new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript(js, null);
            }
        });
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

            if (hasLocationPermission()) {

                // Permission मिलने के बाद page को reload नहीं करते।
                // User GPS button दबाकर Native GPS request कर सकता है.

            } else {

                sendLocationError(
                        "Location permission नहीं मिली।"
                );
            }
        }
    }

    @Override
    public void onBackPressed() {

        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
