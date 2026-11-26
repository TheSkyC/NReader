// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.util.Date;

/** Owns reader-scoped time, battery, visibility and idle updates. */
public final class ReaderStatusOverlayController {
    private final Context context;
    private final ReaderStatusOverlayView view;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ReaderStatusSettings settings;
    private int currentPage;
    private int totalPages;
    private boolean foreground;
    private boolean controlsVisible = true;
    private boolean modalVisible;
    private boolean idleExpired;
    private boolean interactionActive;
    private boolean receiversRegistered;
    private boolean clockReceiverRegistered;
    private boolean batteryReceiverRegistered;
    private final BroadcastReceiver clockReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context receiverContext, Intent intent) {
            refreshClock();
            scheduleNextMinute();
        }
    };
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context receiverContext, Intent intent) {
            updateBattery(intent);
        }
    };
    private final Runnable idleTask = () -> {
        if (!foreground || controlsVisible || modalVisible) return;
        idleExpired = true;
        updateView(true);
    };
    private final Runnable clockTask = new Runnable() {
        @Override public void run() {
            if (!foreground) return;
            refreshClock();
            scheduleNextMinute();
        }
    };

    public ReaderStatusOverlayController(@NonNull Context context, @NonNull ReaderStatusOverlayView view) {
        this.context = context.getApplicationContext();
        this.view = view;
        settings = ReaderStatusSettings.load(context.getSharedPreferences("Settings", Context.MODE_PRIVATE));
        view.setSettings(settings);
    }

    public void onResume() {
        if (foreground) return;
        foreground = true;
        registerReceivers();
        refreshClock();
        updateBatteryFromStickyIntent();
        idleExpired = false;
        updateView(false);
        scheduleNextMinute();
        scheduleIdleTimeout();
    }

    public void onPause() {
        if (!foreground) return;
        foreground = false;
        interactionActive = false;
        handler.removeCallbacks(idleTask);
        handler.removeCallbacks(clockTask);
        unregisterReceivers();
        updateView(false);
    }

    public void close() {
        onPause();
        handler.removeCallbacksAndMessages(null);
    }

    public void setControlsVisible(boolean visible) {
        if (controlsVisible == visible) return;
        controlsVisible = visible;
        interactionActive = false;
        handler.removeCallbacks(idleTask);
        if (visible) {
            idleExpired = false;
            updateView(true);
        } else {
            onReaderActivity();
        }
    }

    public void setModalVisible(boolean visible) {
        if (modalVisible == visible) return;
        modalVisible = visible;
        handler.removeCallbacks(idleTask);
        if (visible) {
            idleExpired = false;
            updateView(true);
        } else {
            onReaderActivity();
        }
    }

    public void onInteractionStarted() {
        interactionActive = true;
        idleExpired = false;
        handler.removeCallbacks(idleTask);
        updateView(true);
    }

    public void onInteractionEnded() {
        interactionActive = false;
        scheduleIdleTimeout();
        updateView(true);
    }

    public void onReaderActivity() {
        idleExpired = false;
        handler.removeCallbacks(idleTask);
        updateView(true);
        scheduleIdleTimeout();
    }

    public void setProgress(int page, int total) {
        currentPage = page;
        totalPages = total;
        view.setProgressValue(page, total);
        onReaderActivity();
    }

    public void refreshSettings() {
        settings = ReaderStatusSettings.load(context.getSharedPreferences("Settings", Context.MODE_PRIVATE));
        view.setSettings(settings);
        idleExpired = false;
        scheduleIdleTimeout();
        updateView(false);
    }

    @NonNull
    public ReaderStatusSettings getSettings() {
        return settings;
    }

    public void copyPreviewStateTo(@NonNull ReaderStatusOverlayView preview,
                                   @NonNull ReaderStatusOverlayView.PositionListener listener) {
        view.copyPreviewStateTo(preview, listener);
    }

    private void registerReceivers() {
        if (receiversRegistered) return;
        IntentFilter clockFilter = new IntentFilter();
        clockFilter.addAction(Intent.ACTION_TIME_TICK);
        clockFilter.addAction(Intent.ACTION_TIME_CHANGED);
        clockFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(clockReceiver, clockFilter);
        clockReceiverRegistered = true;

        IntentFilter batteryFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent sticky = registerReceiver(batteryReceiver, batteryFilter);
        batteryReceiverRegistered = true;
        if (sticky != null) updateBattery(sticky);
        receiversRegistered = true;
    }

    private void unregisterReceivers() {
        if (!receiversRegistered) return;
        if (clockReceiverRegistered) unregisterReceiver(clockReceiver);
        if (batteryReceiverRegistered) unregisterReceiver(batteryReceiver);
        clockReceiverRegistered = false;
        batteryReceiverRegistered = false;
        receiversRegistered = false;
    }

    private void unregisterReceiver(BroadcastReceiver receiver) {
        try {
            context.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // A receiver can already be unregistered during Activity teardown.
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            return ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        return context.registerReceiver(receiver, filter);
    }

    private void updateBatteryFromStickyIntent() {
        Intent sticky = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (sticky != null) updateBattery(sticky);
    }

    private void updateBattery(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) {
            view.setBatteryValue(-1, false);
            return;
        }
        int percent = Math.max(0, Math.min(100, Math.round(level * 100f / scale)));
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        view.setBatteryValue(percent, status == BatteryManager.BATTERY_STATUS_CHARGING
            || status == BatteryManager.BATTERY_STATUS_FULL);
    }

    private void refreshClock() {
        view.setTimeValue(DateFormat.getTimeFormat(context).format(new Date()));
    }

    private void scheduleNextMinute() {
        handler.removeCallbacks(clockTask);
        long currentTime = System.currentTimeMillis();
        long delay = 60_000L - currentTime % 60_000L + 25L;
        handler.postDelayed(clockTask, delay);
    }

    private void scheduleIdleTimeout() {
        handler.removeCallbacks(idleTask);
        if (!foreground || controlsVisible || modalVisible || interactionActive || !settings.hasIdleItems()) return;
        handler.postDelayed(idleTask, settings.idleDelaySeconds * 1000L);
    }

    private void updateView(boolean animate) {
        view.setHostState(foreground, controlsVisible, modalVisible, idleExpired, interactionActive, animate);
    }
}
