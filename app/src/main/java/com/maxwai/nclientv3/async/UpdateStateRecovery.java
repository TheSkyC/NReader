// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import android.content.Context;

import androidx.annotation.NonNull;

import com.maxwai.nclientv3.BuildConfig;
import com.maxwai.nclientv3.async.update.UpdatePhase;
import com.maxwai.nclientv3.async.update.UpdateState;

import java.io.File;

/** Reconciles persisted state with files and the version actually running. */
public final class UpdateStateRecovery {
    private UpdateStateRecovery() {}

    @NonNull
    public static UpdateState reconcile(@NonNull Context context, @NonNull UpdateState state) {
        String version = state.getVersion();
        String filePath = state.getFilePath();
        File file = filePath == null ? null : new File(filePath);
        long now = System.currentTimeMillis();

        if (version != null && VersionChecker.GitHubRelease.isVersionAtLeast(BuildConfig.VERSION_NAME, version)) {
            UpdateNotification.clear(context);
            return UpdateState.idle(now);
        }

        if (state.getPhase() == UpdatePhase.INSTALLING || state.getPhase() == UpdatePhase.READY_TO_INSTALL) {
            if (version != null && file != null && file.isFile()) {
                return UpdateState.readyToInstall(version, file.getAbsolutePath(), file.length(), now);
            }
            return UpdateState.error(version, filePath, version == null ? "missing_version" : "missing_file", now);
        }

        if (state.getPhase() == UpdatePhase.DOWNLOADING) {
            return UpdateState.error(version, filePath, "interrupted_download", now);
        }
        return state;
    }
}
