package com.maxwai.nclientv3.components.views;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.utility.LogUtility;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

public final class TurnstileDialog {
    public interface Callback {
        void onToken(@NonNull String token, @NonNull String userAgent, @NonNull String protectionCookies);
        void onCancelled();
        void onFailure();
    }

    private TurnstileDialog() {
    }

    @NonNull
    public static Dialog show(@NonNull Context context, @NonNull String siteKey, @NonNull Callback callback) {
        WebView view = new WebView(context);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        String userAgent = settings.getUserAgentString();
        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(@NonNull WebView webView, @NonNull WebResourceRequest request) {
                return true;
            }

            @Override
            public void onReceivedError(@NonNull WebView webView, @NonNull WebResourceRequest request,
                                        @NonNull WebResourceError error) {
                if ("challenges.cloudflare.com".equalsIgnoreCase(request.getUrl().getHost()))
                    LogUtility.w("Account verification resource failed with network code " + error.getErrorCode());
            }
        });
        AtomicBoolean finished = new AtomicBoolean();
        AtomicBoolean scriptReady = new AtomicBoolean();
        Handler main = new Handler(Looper.getMainLooper());
        Dialog dialog = new MaterialAlertDialogBuilder(context).setTitle(R.string.account_verify_title)
            .setMessage(R.string.account_verify_message).setView(view)
            .setNegativeButton(R.string.cancel, (ignored, which) -> { }).create();
        view.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void ready() {
                scriptReady.set(true);
            }

            @JavascriptInterface
            public void token(String value) {
                if (value == null || value.isEmpty() || value.length() > 4096) return;
                main.post(() -> {
                    if (!finished.compareAndSet(false, true)) return;
                    String cookies = CookieManager.getInstance().getCookie("https://nhentai.net/");
                    StringBuilder protection = new StringBuilder();
                    if (cookies != null) {
                        for (String part : cookies.split(";")) {
                            String item = part.trim();
                            if (item.startsWith("cf_clearance=") || item.startsWith("__cf_bm=")) {
                                if (protection.length() > 0) protection.append("; ");
                                protection.append(item);
                            }
                        }
                    }
                    callback.onToken(value, userAgent, protection.toString());
                    dialog.dismiss();
                });
            }

            @JavascriptInterface
            public void error(String code) {
                if (code != null && code.matches("[0-9]{1,8}"))
                    LogUtility.w("Account verification failed with code " + code);
                else LogUtility.w("Account verification failed");
                main.post(() -> {
                    if (!finished.compareAndSet(false, true)) return;
                    callback.onFailure();
                    dialog.dismiss();
                });
            }
        }, "NReaderVerification");
        String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<script src='https://challenges.cloudflare.com/turnstile/v0/api.js?onload=ready&render=explicit' async defer></script>"
            + "</head><body><div id='challenge'></div><script>function ready(){NReaderVerification.ready();turnstile.render('#challenge',"
            + "{sitekey:" + JSONObject.quote(siteKey) + ",theme:'auto',callback:function(t){NReaderVerification.token(t)},"
            + "'error-callback':function(code){NReaderVerification.error(String(code))},"
            + "'expired-callback':function(){NReaderVerification.error('')}})}</script></body></html>";
        Runnable timeout = () -> {
            if (!finished.compareAndSet(false, true)) return;
            LogUtility.w(scriptReady.get() ? "Account verification timed out after script initialization"
                : "Account verification timed out before script initialization");
            callback.onFailure();
            dialog.dismiss();
        };
        dialog.setOnDismissListener(ignored -> {
            main.removeCallbacks(timeout);
            if (finished.compareAndSet(false, true)) callback.onCancelled();
            view.stopLoading();
            view.removeJavascriptInterface("NReaderVerification");
            view.destroy();
        });
        dialog.show();
        view.loadDataWithBaseURL("https://nhentai.net/", html, "text/html", "UTF-8", null);
        main.postDelayed(timeout, 60000);
        return dialog;
    }
}
