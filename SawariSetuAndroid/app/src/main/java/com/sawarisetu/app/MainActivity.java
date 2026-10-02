ठीक है। नीचे आपकी मौजूदा MainActivity.java का पूरा corrected version है। इसमें index.html को बदलने की जरूरत नहीं है।
मुख्य सुधार: Call, WhatsApp, Email, GPS permission और WebView geolocation को native Android तरीके से संभाला गया है।
Writing
package com.sawarisetu.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.GeolocationPermissions;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {

    private WebView webView;

    private ValueCallback<Uri[]> uploadMessage;

    private static final int FILE_CHOOSER_RESULT_CODE = 1001;
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 100;

    private String pendingGeoOrigin;
    private GeolocationPermissions.Callback pendingGeoCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // -----------------------------
        // WebView बनाना
        // -----------------------------
        webView = new WebView(this);
        setContentView(webView);

        WebSettings webSettings = webView.getSettings();

        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);

        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);

        webSettings.setGeolocationEnabled(true);

        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webSettings.setMixedContentMode(
                    WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            );
        }

        // -----------------------------
        // WebView Client
        // -----------------------------
        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    WebResourceRequest request
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    Uri uri = request.getUrl();
                    return handleExternalUri(uri);
                }

                return false;
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    String url
            ) {
                if (url == null) {
                    return false;
                }

                return handleExternalUri(Uri.parse(url));
            }
        });

        // -----------------------------
        // WebChromeClient
        // GPS + File Chooser
        // -----------------------------
        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams
            ) {
                if (uploadMessage != null) {
                    uploadMessage.onReceiveValue(null);
                    uploadMessage = null;
                }

                uploadMessage = filePathCallback;

                try {
                    Intent intent = fileChooserParams.createIntent();

                    startActivityForResult(
                            intent,
                            FILE_CHOOSER_RESULT_CODE
                    );

                    return true;

                } catch (ActivityNotFoundException e) {

                    uploadMessage = null;

                    Toast.makeText(
                            MainActivity.this,
                            "फ़ाइल चुनने के लिए कोई ऐप उपलब्ध नहीं है।",
                            Toast.LENGTH_SHORT
                    ).show();

                    return false;

                } catch (Exception e) {

                    uploadMessage = null;

                    Toast.makeText(
                            MainActivity.this,
                            "फ़ाइल पिकर खोलने में समस्या आई।",
                            Toast.LENGTH_SHORT
                    ).show();

                    return false;
                }
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(
                    String origin,
                    GeolocationPermissions.Callback callback
            ) {

                pendingGeoOrigin = origin;
                pendingGeoCallback = callback;

                requestLocationPermissionIfNeeded();
            }
        });

        // -----------------------------
        // Android GPS Permission
        // -----------------------------
        requestLocationPermissionIfNeeded();

        // -----------------------------
        // Master HTML
        // -----------------------------
        webView.loadUrl(
                "file:///android_asset/index.html"
        );
    }

    // =========================================================
    // External URI Handler
    // =========================================================

    private boolean handleExternalUri(Uri uri) {

        if (uri == null) {
            return false;
        }

        String scheme = uri.getScheme();

        if (scheme == null) {
            return false;
        }

        scheme = scheme.toLowerCase();

        try {

            // -------------------------
            // PHONE CALL
            // -------------------------
            if ("tel".equals(scheme)) {

                Intent intent = new Intent(
                        Intent.ACTION_DIAL,
                        uri
                );

                startActivity(intent);

                return true;
            }

            // -------------------------
            // SMS
            // -------------------------
            if ("sms".equals(scheme)
                    || "smsto".equals(scheme)) {

                Intent intent = new Intent(
                        Intent.ACTION_SENDTO,
                        uri
                );

                startActivity(intent);

                return true;
            }

            // -------------------------
            // EMAIL
            // -------------------------
            if ("mailto".equals(scheme)) {

                Intent intent = new Intent(
                        Intent.ACTION_SENDTO,
                        uri
                );

                intent.addCategory(Intent.CATEGORY_DEFAULT);

                startActivity(intent);

                return true;
            }

            // -------------------------
            // GEO / MAP
            // -------------------------
            if ("geo".equals(scheme)) {

                Intent intent = new Intent(
                        Intent.ACTION_VIEW,
                        uri
                );

                startActivity(intent);

                return true;
            }

            // -------------------------
            // WHATSAPP SCHEME
            // -------------------------
            if ("whatsapp".equals(scheme)) {

                return openWhatsApp(uri);
            }

            // -------------------------
            // wa.me HTTPS
            // -------------------------
            String host = uri.getHost();

            if ("wa.me".equalsIgnoreCase(host)
                    || "www.wa.me".equalsIgnoreCase(host)) {

                return openWhatsApp(uri);
            }

            // -------------------------
            // NORMAL HTTP/HTTPS
            // WebView में ही रहने दें
            // -------------------------
            if ("http".equals(scheme)
                    || "https".equals(scheme)) {

                return false;
            }

        } catch (ActivityNotFoundException e) {

            Toast.makeText(
                    MainActivity.this,
                    "इस कार्य के लिए संबंधित ऐप उपलब्ध नहीं है।",
                    Toast.LENGTH_SHORT
            ).show();

            return true;

        } catch (Exception e) {

            Toast.makeText(
                    MainActivity.this,
                    "लिंक खोलने में समस्या आई।",
                    Toast.LENGTH_SHORT
            ).show();

            return true;
        }

        return false;
    }

    // =========================================================
    // WhatsApp Handler
    // =========================================================

    private boolean openWhatsApp(Uri uri) {

        try {

            // पहले WhatsApp application को खोलने का प्रयास
            Intent whatsappIntent = new Intent(
                    Intent.ACTION_VIEW,
                    uri
            );

            whatsappIntent.setPackage(
                    "com.whatsapp"
            );

            startActivity(whatsappIntent);

            return true;

        } catch (ActivityNotFoundException e) {

            // WhatsApp उपलब्ध नहीं है।
            // WhatsApp Business को प्रयास करें।

            try {

                Intent businessIntent = new Intent(
                        Intent.ACTION_VIEW,
                        uri
                );

                businessIntent.setPackage(
                        "com.whatsapp.w4b"
                );

                startActivity(businessIntent);

                return true;

            } catch (ActivityNotFoundException e2) {

                // अंतिम fallback:
                // सामान्य browser से wa.me खोलें।

                try {

                    Intent browserIntent = new Intent(
                            Intent.ACTION_VIEW,
                            uri
                    );

                    startActivity(browserIntent);

                    return true;

                } catch (Exception e3) {

                    Toast.makeText(
                            MainActivity.this,
                            "WhatsApp या browser उपलब्ध नहीं है।",
                            Toast.LENGTH_SHORT
                    ).show();

                    return true;
                }
            }
        }
    }

    // =========================================================
    // Location Permission
    // =========================================================

    private void requestLocationPermissionIfNeeded() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {

            grantWebViewLocation();

            return;
        }

        boolean fineGranted =
                ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED;

        boolean coarseGranted =
                ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED;

        if (fineGranted || coarseGranted) {

            grantWebViewLocation();

        } else {

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    LOCATION_PERMISSION_REQUEST_CODE
            );
        }
    }

    // =========================================================
    // WebView Location Permission
    // =========================================================

    private void grantWebViewLocation() {

        if (pendingGeoCallback != null
                && pendingGeoOrigin != null) {

            pendingGeoCallback.invoke(
                    pendingGeoOrigin,
                    true,
                    false
            );

            pendingGeoCallback = null;
            pendingGeoOrigin = null;
        }
    }

    private void denyWebViewLocation() {

        if (pendingGeoCallback != null
                && pendingGeoOrigin != null) {

            pendingGeoCallback.invoke(
                    pendingGeoOrigin,
                    false,
                    false
            );

            pendingGeoCallback = null;
            pendingGeoOrigin = null;
        }
    }

    // =========================================================
    // Permission Result
    // =========================================================

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {

        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode
                == LOCATION_PERMISSION_REQUEST_CODE) {

            boolean granted = false;

            if (grantResults != null) {

                for (int result : grantResults) {

                    if (result
                            == PackageManager.PERMISSION_GRANTED) {

                        granted = true;
                        break;
                    }
                }
            }

            if (granted) {

                grantWebViewLocation();

                Toast.makeText(
                        this,
                        "📍 GPS लोकेशन अनुमति सक्रिय है।",
                        Toast.LENGTH_SHORT
                ).show();

            } else {

                denyWebViewLocation();

                Toast.makeText(
                        this,
                        "📍 GPS अनुमति नहीं मिली। GPS Address के लिए अनुमति आवश्यक है।",
                        Toast.LENGTH_LONG
                ).show();
            }
        }
    }

    // =========================================================
    // File Chooser Result
    // =========================================================

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {

        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );

        if (requestCode
                == FILE_CHOOSER_RESULT_CODE) {

            if (uploadMessage == null) {
                return;
            }

            Uri[] results =
                    WebChromeClient.FileChooserParams
                            .parseResult(
                                    resultCode,
                                    data
                            );

            uploadMessage.onReceiveValue(results);

            uploadMessage = null;
        }
    }

    // =========================================================
    // Back Button
    // =========================================================

    @Override
    public void onBackPressed() {

        if (webView != null
                && webView.canGoBack()) {

            webView.goBack();

        } else {

            super.onBackPressed();
        }
    }

    // =========================================================
    // Activity Destroy
    // =========================================================

    @Override
    protected void onDestroy() {

        if (webView != null) {

            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();

            webView = null;
        }

        uploadMessage = null;
        pendingGeoCallback = null;
        pendingGeoOrigin = null;

        super.onDestroy();
    }
}
एक जरूरी चीज़ और
इस Java file को लगाने के बाद Manifest में GPS permission मौजूद होना जरूरी है। AndroidManifest.xml में कम-से-कम ये permissions होनी चाहिए:
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.INTERNET" />
अभी APK build मत करें। पहले मुझे आपका AndroidManifest.xml पूरा code भेज दें। मैं उसे भी इसी master structure के हिसाब से जाँच लूँगा। उसके बाद हम क्रम से Manifest → Java → Assets → Build → Install → Call/WhatsApp/Email/GPS test करेंगे।
