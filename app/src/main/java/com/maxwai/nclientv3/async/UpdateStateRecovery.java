// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import android.content.Context;

import androidx.annotation.NonNull;

import com.maxwai.nclientv3.BuildConfig;
import com.maxwai.nclientv3.async.update.UpdatePhase;
import com.maxwai.nclientv3.async.update.UpdateState;
import com.maxwai.nclientv3.async.update.UpdateStateStore;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;

import java.io.File;
import java.io.IOException;
import java.util.regex.Pattern;

/** Reconciles persisted state with files and the version actually running. */
public final class UpdateStateRecovery {
    private static final Pattern OWNED_ARTIFACT = Pattern.compile(
        "(?i)^NReader-v?\\d+\\.\\d+\\.\\d+(?:-(?:pre\\d+|(?:alpha|beta|rc)\\.\\d+))?"
            + "(?:-(?:debug|relwithdebinfo))?-(?:minApi26|minApi28)-"
            + "(?:release|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"
            + "\\.apk(?:\\.part)?$"
    );
    private UpdateStateRecovery() {}

    @NonNull
    public static UpdateState reconcileStored(@NonNull Context context) {
        UpdateStateStore store = new UpdateStateStore(context);
        while (true) {
            long revision = store.revision();
            UpdateState[] result = new UpdateState[1];
            if (store.runIfUnchanged(revision, () -> {
                UpdateState state = store.load();
                if (state.getPhase() == UpdatePhase.DOWNLOADING && UpdateDownloadManager.isActive(state.getFilePath())) {
                    result[0] = state;
                    return;
                }
                UpdateState recovered = reconcile(context, state);
                if (recovered != state) store.save(recovered);
                result[0] = recovered;
            })) return result[0];
        }
    }

    @NonNull
    public static UpdateState reconcile(@NonNull Context context, @NonNull UpdateState state) {
        String version = state.getVersion();
        String filePath = state.getFilePath();
        File file = filePath == null ? null : new File(filePath);
        long now = System.currentTimeMillis();

        UpdateMetadataStore metadata = new UpdateMetadataStore(context);
        long selectedVersionCode = metadata.getSelectedVersionCode();
        boolean matchingSelection = version != null && version.equals(metadata.getSelectedVersion());
        boolean installed = matchingSelection && selectedVersionCode > 0L
            ? BuildConfig.VERSION_CODE >= selectedVersionCode
            : version != null && VersionChecker.GitHubRelease.isVersionAtLeast(BuildConfig.VERSION_NAME, version);
        if (installed) {
            deleteUpdateArtifact(file);
            metadata.clearSelectedRelease();
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
            deletePartialFile(file);
            UpdateNotification.clear(context);
            return UpdateState.error(version, filePath, "interrupted_download", now);
        }
        return state;
    }

    private static void deletePartialFile(File file) {
        if (file != null && file.getName().endsWith(".part")) deleteUpdateArtifact(file);
    }

    private static void deleteUpdateArtifact(File file) {
        if (file == null || Global.UPDATEFOLDER == null || !OWNED_ARTIFACT.matcher(file.getName()).matches()) return;
        try {
            File canonical = file.getCanonicalFile();
            if (!Global.UPDATEFOLDER.getCanonicalFile().equals(canonical.getParentFile()) || !canonical.isFile()) return;
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        } catch (IOException | SecurityException exception) {
            LogUtility.w("Unable to verify update artifact location: " + exception.getClass().getSimpleName());
        }
    }
}
