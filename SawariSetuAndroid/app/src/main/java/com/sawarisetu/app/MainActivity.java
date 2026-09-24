package com.sawarisetu.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {

    private WebView webView;
    private static final String APP_ORIGIN = "https://sawarisetu.local/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDatabaseEnabled(true);

        webView.setWebViewClient(new AssetWebViewClient(getAssets()));

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(
                    String origin,
                    GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }
        });

        requestLocationPermission();
        webView.loadUrl(APP_ORIGIN + "index.html");
    }

    private void requestLocationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;

            if (!fine && !coarse) {
                requestPermissions(
                        new String[]{
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                        },
                        10
                );
            }
        }
    }

    private static class AssetWebViewClient extends WebViewClient {

        private final AssetManager assets;

        AssetWebViewClient(AssetManager assets) {
            this.assets = assets;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(
                WebView view,
                WebResourceRequest request) {

            String url = request.getUrl().toString();

            if (url.startsWith(APP_ORIGIN)) {
                String path = request.getUrl().getPath();

                if (path == null || "/".equals(path) || "/index.html".equals(path)) {
                    return openAsset("index.html", "text/html");
                }

                if ("/logo.png".equals(path)) {
                    return openAsset("logo.png", "image/png");
                }
            }

            return super.shouldInterceptRequest(view, request);
        }

        private WebResourceResponse openAsset(
                String assetName,
                String mimeType) {
            try {
                InputStream input = assets.open(assetName, AssetManager.ACCESS_STREAMING);
                Map<String, String> headers = new HashMap<>();
                headers.put("Access-Control-Allow-Origin", APP_ORIGIN);
                headers.put("Cache-Control", "no-cache");
                return new WebResourceResponse(
                        mimeType,
                        "UTF-8",
                        200,
                        "OK",
                        headers,
                        input
                );
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
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
