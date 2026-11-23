// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.activities;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.DeprecatedSinceApi;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.work.Configuration;

import com.theskyc.nreader.BuildConfig;
import com.theskyc.nreader.R;
import com.theskyc.nreader.ZoomActivity;
import com.theskyc.nreader.components.reader.ReaderWindowInsets;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.downloader.DownloadGalleryV2;
import com.theskyc.nreader.async.UpdateCheckWorker;
import com.theskyc.nreader.async.LegacyWorkerFactory;
import com.theskyc.nreader.async.TagCatalogSeedWorker;
import com.theskyc.nreader.async.TagTranslationKeyRebuildWorker;
import com.theskyc.nreader.async.UpdateStateRecovery;
import com.theskyc.nreader.settings.Database;
import com.theskyc.nreader.settings.AccountRuntime;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.settings.TagV2;
import com.theskyc.nreader.utility.network.NetworkUtil;

public class CrashApplication extends Application implements Configuration.Provider {

    @NonNull
    @Override
    public Configuration getWorkManagerConfiguration() {
        return new Configuration.Builder().setWorkerFactory(new LegacyWorkerFactory()).build();
    }

    /**
     * Don't use on API >= 31 (S)
     */
    @DeprecatedSinceApi(api = Build.VERSION_CODES.S)
    public static void setDarkLightTheme(String theme, Context ctx) {
        String[] availableThemes = ctx.getResources().getStringArray(R.array.theme_data);
        ContextCompat.getMainExecutor(ctx).execute(() ->
            AppCompatDelegate.setDefaultNightMode(theme.equals(availableThemes[0]) ? // light
                AppCompatDelegate.MODE_NIGHT_NO :
                AppCompatDelegate.MODE_NIGHT_YES));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AccountRuntime.initialize(this);
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
        Global.initStorage(this);
        //noinspection resource
        Database.setDatabase(new DatabaseHelper(getApplicationContext()).getWritableDatabase());
        String version = Global.getLastVersion(this);
        SharedPreferences preferences = getSharedPreferences("Settings", 0);
        if (!BuildConfig.VERSION_NAME.equals(version))
            afterUpdateChecks(preferences, version);

        Global.initFromShared(this);
        UpdateStateRecovery.reconcileStored(this);
        NetworkUtil.initConnectivity(this);
        TagV2.initMinCount(this);
        TagV2.initSortByName(this);
        TagV2.initStatusFilter(this);
        TagV2.initTranslatedOnly(this);
        TagCatalogSeedWorker.schedule(this);
        TagTranslationKeyRebuildWorker.schedule(this);
        DownloadGalleryV2.loadDownloads(this);
        UpdateCheckWorker.schedule(this);
        registerActivityLifecycleCallbacks(new CustomActivityLifecycleCallback());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            String theme = preferences.getString(getString(R.string.preference_key_theme_select), "");
            setDarkLightTheme(theme, this);
        }
    }

    private void afterUpdateChecks(SharedPreferences preferences, String oldVersion) {
        SharedPreferences.Editor editor = preferences.edit();
        if ("0.0.0".equals(oldVersion))
            editor.putBoolean(getString(R.string.preference_key_check_update), true);
        editor.apply();
        Global.setLastVersion(this);
    }


    private static class CustomActivityLifecycleCallback implements ActivityLifecycleCallbacks {

        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
            View rootView = activity.getWindow().getDecorView().getRootView();
            ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
                if (activity instanceof ZoomActivity) {
                    ReaderWindowInsets.apply(v, insets);
                    return WindowInsetsCompat.CONSUMED;
                }
                Insets barsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                int bottom = barsInsets.bottom;
                if (activity instanceof com.theskyc.nreader.GalleryActivity) {
                    bottom = Math.max(bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
                }
                v.setPadding(
                    barsInsets.left,
                    barsInsets.top,
                    barsInsets.right,
                    bottom
                );
                return WindowInsetsCompat.CONSUMED;
            });
        }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {

        }

        @Override
        public void onActivityPaused(@NonNull Activity activity) {

        }

        @Override
        public void onActivityResumed(@NonNull Activity activity) {

        }

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {

        }

        @Override
        public void onActivityStarted(@NonNull Activity activity) {

        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {

        }
    }
}
