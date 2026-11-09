// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.activities;

import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.components.views.CFTokenView;
import com.maxwai.nclientv3.settings.Global;

import java.lang.ref.WeakReference;

public abstract class GeneralActivity extends AppCompatActivity {
    private static WeakReference<GeneralActivity> lastActivity;
    private boolean isFastScrollerApplied = false;
    private CFTokenView tokenView = null;

    public static @Nullable
    CFTokenView getLastCFView() {
        if (lastActivity == null) return null;
        GeneralActivity activity = lastActivity.get();
        if (activity != null) {
            activity.runOnUiThread(activity::inflateWebView);
            return activity.tokenView;
        }
        return null;
    }

    private void inflateWebView() {
        if (tokenView == null) {
            Toast.makeText(this, R.string.fetching_cloudflare_token, Toast.LENGTH_SHORT).show();
            ViewGroup rootView = (ViewGroup) findViewById(android.R.id.content).getRootView();
            ViewGroup v = (ViewGroup) LayoutInflater.from(this).inflate(R.layout.cftoken_layout, rootView, false);
            tokenView = new CFTokenView(v);
            ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            tokenView.setVisibility(View.GONE);
            this.addContentView(v, params);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // FLAG_SECURE (防截屏/防录屏/隐藏多任务预览) 已移除，允许正常截屏录屏
        Global.initActivity(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        lastActivity = new WeakReference<>(this);
        com.maxwai.nclientv3.async.database.GalleryStateRepository.invalidateLocalFiles();
        if (!isFastScrollerApplied) {
            isFastScrollerApplied = true;
            Global.applyFastScroller(findViewById(R.id.recycler));
        }
    }

    @Override
    public Resources.Theme getTheme() {
        Resources.Theme theme = super.getTheme();
        SharedPreferences preferences = getSharedPreferences("Settings", 0);
        if (preferences.getBoolean(getString(R.string.preference_key_black_theme), false)) {
            theme.applyStyle(R.style.AppTheme_Black, true);
        }
        return theme;
    }
}
