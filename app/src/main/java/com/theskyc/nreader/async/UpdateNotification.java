// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async;

import android.app.PendingIntent;
import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.theskyc.nreader.R;
import com.theskyc.nreader.UpdateCenterActivity;
import com.theskyc.nreader.async.update.UpdatePhase;
import com.theskyc.nreader.async.update.UpdateState;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.settings.NotificationSettings;

public final class UpdateNotification {
    private static final int NOTIFICATION_ID = 1201;
    private static final String PREFERENCES = "UpdateNotification";
    private static final String NOTIFIED_AVAILABLE = "notified_available";
    private static final String NOTIFIED_AVAILABLE_REMIND_AFTER = "notified_available_remind_after";
    private static final String NOTIFIED_READY = "notified_ready";

    private UpdateNotification() {}

    @NonNull
    static Notification foreground(@NonNull Context context, @NonNull UpdateState state) {
        Intent intent = new Intent(context, UpdateCenterActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, Global.CHANNEL_ID4)
            .setSmallIcon(R.drawable.ic_file)
            .setContentIntent(pendingIntent)
            .setContentTitle(context.getString(R.string.update_notification_downloading))
            .setContentText(context.getString(R.string.update_notification_version, state.getVersion()))
            .setOnlyAlertOnce(true)
            .setOngoing(true);
        if (state.hasKnownProgress()) {
            int progress = (int) Math.min(100L, state.getDownloadedBytes() * 100L / state.getTotalBytes());
            builder.setProgress(100, progress, false);
        } else {
            builder.setProgress(100, 0, true);
        }
        return builder.build();
    }

    public static synchronized void publish(@NonNull Context context, @NonNull UpdateState state) {
        String version = state.getVersion();
        if (version == null) return;
        if (!NotificationSettings.canNotify(context)) return;
        long remindAfter = new UpdateReminderStore(context).getRemindAfter();
        if (state.getPhase() == UpdatePhase.AVAILABLE
            && !shouldNotifyAvailable(context, version, System.currentTimeMillis(), remindAfter)) return;
        if (state.getPhase() == UpdatePhase.READY_TO_INSTALL && wasNotified(context, NOTIFIED_READY, version)) return;
        if (state.getPhase() != UpdatePhase.AVAILABLE
            && state.getPhase() != UpdatePhase.QUEUED
            && state.getPhase() != UpdatePhase.DOWNLOADING
            && state.getPhase() != UpdatePhase.READY_TO_INSTALL
            && state.getPhase() != UpdatePhase.ERROR) return;

        Intent intent = new Intent(context, UpdateCenterActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, Global.CHANNEL_ID4)
            .setSmallIcon(R.drawable.ic_file)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setAutoCancel(state.getPhase() != UpdatePhase.DOWNLOADING && state.getPhase() != UpdatePhase.QUEUED);
        switch (state.getPhase()) {
            case AVAILABLE:
                builder.setContentTitle(context.getString(R.string.update_notification_available))
                    .setContentText(context.getString(R.string.update_notification_version, version));
                break;
            case QUEUED:
                builder.setContentTitle(context.getString(R.string.update_queued))
                    .setContentText(context.getString(R.string.update_notification_version, version))
                    .setProgress(100, 0, true);
                break;
            case DOWNLOADING:
                builder.setContentTitle(context.getString(R.string.update_notification_downloading))
                    .setContentText(context.getString(R.string.update_notification_version, version));
                if (state.hasKnownProgress()) {
                    int progress = (int) Math.min(100L, state.getDownloadedBytes() * 100L / state.getTotalBytes());
                    builder.setProgress(100, progress, false);
                } else {
                    builder.setProgress(100, 0, true);
                }
                break;
            case READY_TO_INSTALL:
                builder.setContentTitle(context.getString(R.string.update_notification_ready))
                    .setContentText(context.getString(R.string.update_notification_version, version));
                break;
            case ERROR:
                builder.setContentTitle(context.getString(R.string.update_notification_failed))
                    .setContentText(context.getString(R.string.update_notification_version, version));
                break;
            default:
                return;
        }
        if (NotificationSettings.notifyIfChannelEnabled(context, NOTIFICATION_ID, builder.build(), Global.CHANNEL_ID4)) {
            if (state.getPhase() == UpdatePhase.AVAILABLE) markAvailable(context, version, remindAfter);
            if (state.getPhase() == UpdatePhase.READY_TO_INSTALL) {
                context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(NOTIFIED_READY, version).apply();
            }
        }
    }

    public static synchronized void showAvailablePrompt(@NonNull Context context, @NonNull String version,
                                                        @NonNull Runnable show) {
        long remindAfter = new UpdateReminderStore(context).getRemindAfter();
        if (!shouldNotifyAvailable(context, version, System.currentTimeMillis(), remindAfter)) return;
        show.run();
        clear(context);
        markAvailable(context, version, remindAfter);
    }

    public static void clear(@NonNull Context context) {
        NotificationSettings.cancel(NOTIFICATION_ID);
    }

    static synchronized void clearAvailable(@NonNull Context context) {
        clear(context);
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .remove(NOTIFIED_AVAILABLE)
            .remove(NOTIFIED_AVAILABLE_REMIND_AFTER)
            .apply();
    }

    public static synchronized void resetReady(@NonNull Context context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(NOTIFIED_READY)
            .apply();
    }

    static synchronized boolean shouldNotifyAvailable(@NonNull Context context, @NonNull String version, long now, long remindAfter) {
        UpdateReminderStore reminder = new UpdateReminderStore(context);
        if (reminder.suppresses(version, now)) return false;
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        if (version.equals(preferences.getString(NOTIFIED_AVAILABLE, null))
            && remindAfter == preferences.getLong(NOTIFIED_AVAILABLE_REMIND_AFTER, 0L)) return false;
        return true;
    }

    static synchronized void markAvailable(@NonNull Context context, @NonNull String version, long remindAfter) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(NOTIFIED_AVAILABLE, version)
            .putLong(NOTIFIED_AVAILABLE_REMIND_AFTER, remindAfter)
            .apply();
    }

    private static boolean wasNotified(Context context, String key, String version) {
        return version.equals(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(key, null));
    }
}
