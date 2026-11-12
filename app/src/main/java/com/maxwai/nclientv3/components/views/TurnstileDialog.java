package com.maxwai.nclientv3.components.views;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import org.json.JSONArray;
import org.json.JSONException;

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
        Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            if (finished.get() || !dialog.isShowing()) return;
            view.evaluateJavascript("JSON.stringify(window.__nreaderVerification || {state:'pending'})", result -> {
                if (finished.get() || !dialog.isShowing()) return;
                JSONObject state = parseResult(result);
                if (state != null) {
                    if (state.optBoolean("ready", false)) scriptReady.set(true);
                    String status = state.optString("state", "pending");
                    String value = state.optString("value", "");
                    if ("token".equals(status) && !value.isEmpty() && value.length() <= 4096) {
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
                        return;
                    }
                    if ("error".equals(status)) {
                        if (!finished.compareAndSet(false, true)) return;
                        if (value.matches("[0-9]{1,8}"))
                            LogUtility.w("Account verification failed with code " + value);
                        else LogUtility.w("Account verification failed");
                        callback.onFailure();
                        dialog.dismiss();
                        return;
                    }
                }
                main.postDelayed(poll[0], 500);
            });
        };
        String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "</head><body><div id='challenge'></div><script>window.__nreaderVerification={state:'pending'};"
            + "function ready(){window.__nreaderVerification.ready=true;turnstile.render('#challenge',"
            + "{sitekey:" + JSONObject.quote(siteKey) + ",theme:'auto',callback:function(t){window.__nreaderVerification={state:'token',value:t}},"
            + "'error-callback':function(code){window.__nreaderVerification={state:'error',value:String(code)}},"
            + "'expired-callback':function(){window.__nreaderVerification={state:'error'}}})}</script>"
            + "<script src='https://challenges.cloudflare.com/turnstile/v0/api.js?onload=ready&render=explicit' async defer></script>"
            + "</body></html>";
        Runnable failTimeout = () -> {
            if (!dialog.isShowing()) return;
            if (!finished.compareAndSet(false, true)) return;
            LogUtility.w(scriptReady.get() ? "Account verification timed out after script initialization"
                : "Account verification timed out before script initialization");
            callback.onFailure();
            dialog.dismiss();
        };
        Runnable timeout = () -> {
            if (finished.get() || !dialog.isShowing()) return;
            poll[0].run();
            main.postDelayed(failTimeout, 1000);
        };
        dialog.setOnDismissListener(ignored -> {
            main.removeCallbacks(timeout);
            main.removeCallbacks(failTimeout);
            main.removeCallbacks(poll[0]);
            if (finished.compareAndSet(false, true)) callback.onCancelled();
            view.stopLoading();
            view.destroy();
        });
        dialog.show();
        view.loadDataWithBaseURL("https://nhentai.net/", html, "text/html", "UTF-8", null);
        main.post(poll[0]);
        main.postDelayed(timeout, 60000);
        return dialog;
    }

    static JSONObject parseResult(String result) {
        if (result == null || result.length() > 16384) return null;
        try {
            Object value = new JSONArray("[" + result + "]").opt(0);
            return value instanceof String ? new JSONObject((String) value) : null;
        } catch (JSONException exception) {
            return null;
        }
    }
}
