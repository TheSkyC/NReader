// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.color.MaterialColors;

import com.theskyc.nreader.async.VersionChecker;
import com.theskyc.nreader.async.UpdateMetadataStore;
import com.theskyc.nreader.async.UpdateNotification;
import com.theskyc.nreader.async.UpdateStateRecovery;
import com.theskyc.nreader.async.UpdateReminderStore;
import com.theskyc.nreader.async.update.UpdatePhase;
import com.theskyc.nreader.async.update.UpdateState;
import com.theskyc.nreader.async.update.UpdateStateStore;
import com.theskyc.nreader.components.activities.GeneralActivity;

import java.util.Objects;
import java.text.DateFormat;
import java.util.Date;

public class UpdateCenterActivity extends GeneralActivity implements VersionChecker.Listener {
    @Override
    protected int getBlackThemeStyle() {
        return R.style.AppTheme_Material3_Black;
    }

    private TextView status;
    private TextView versionFrom;
    private TextView version;
    private TextView notes;
    private TextView channel;
    private TextView size;
    private TextView lastChecked;
    private TextView errorDetail;
    private TextView progressLabel;
    private ProgressBar progress;
    private ImageView statusIcon;
    private View progressCard;
    private View errorCard;
    private View releaseCard;
    private LinearLayout metaRow;
    private Button action;
    private Button secondary;
    private Button skip;
    private VersionChecker checker;
    private VersionChecker.GitHubRelease release;
    private UpdateState currentState;
    private UpdateStateStore stateStore;
    private final Handler stateHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshState = () -> {
        if (!isFinishing() && !isDestroyed()) {
            currentState = stateStore.load();
            if (currentState.getPhase() == UpdatePhase.AVAILABLE && checker != null
                && (release == null || !currentState.getVersion().equals(release.getVersionCode()))) {
                release = null;
                checker.restoreSelectedRelease();
            }
            render(currentState);
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener stateListener = (preferences, key) -> {
        stateHandler.removeCallbacks(refreshState);
        stateHandler.post(refreshState);
    };
    private final ActivityResultLauncher<Intent> installLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> {
            if (checker != null) {
                checker.onInstallActivityResult(result.getResultCode());
                currentState = new UpdateStateStore(this).load();
                render(currentState);
            }
        }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_update_center);
        MaterialToolbar toolbar = findViewById(R.id.update_toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = Objects.requireNonNull(getSupportActionBar());
        actionBar.setTitle(R.string.application_update);
        actionBar.setDisplayHomeAsUpEnabled(true);

        status = findViewById(R.id.update_status);
        versionFrom = findViewById(R.id.update_version_from);
        version = findViewById(R.id.update_version);
        notes = findViewById(R.id.update_notes);
        channel = findViewById(R.id.update_channel);
        size = findViewById(R.id.update_size);
        lastChecked = findViewById(R.id.update_last_checked);
        errorDetail = findViewById(R.id.update_error_detail);
        progressLabel = findViewById(R.id.update_progress_label);
        progress = findViewById(R.id.update_progress);
        statusIcon = findViewById(R.id.update_status_icon);
        progressCard = findViewById(R.id.update_progress_card);
        errorCard = findViewById(R.id.update_error_card);
        releaseCard = findViewById(R.id.update_release_card);
        metaRow = findViewById(R.id.update_meta_row);
        action = findViewById(R.id.update_action);
        secondary = findViewById(R.id.update_secondary);
        skip = findViewById(R.id.update_skip);
        stateStore = new UpdateStateStore(this);
        stateStore.registerListener(stateListener);

        action.setOnClickListener(view -> {
            if (currentState != null && currentState.getPhase() == UpdatePhase.READY_TO_INSTALL) {
                if (checker.installReadyUpdate()) action.setEnabled(false);
            } else if (release != null && currentState != null
                && currentState.getPhase() == UpdatePhase.AVAILABLE
                && release.getVersionCode().equals(currentState.getVersion())) checker.startDownload();
            else startCheck();
        });
        secondary.setOnClickListener(view -> {
            if (currentState != null && (currentState.getPhase() == UpdatePhase.QUEUED
                || currentState.getPhase() == UpdatePhase.DOWNLOADING)) {
                checker.cancelDownload();
            } else if (currentState != null && currentState.getPhase() == UpdatePhase.AVAILABLE
                && release != null && release.getVersionCode() != null) {
                new UpdateReminderStore(this).remindLater(System.currentTimeMillis());
                UpdateNotification.clear(this);
                finish();
            } else {
                startCheck();
            }
        });
        skip.setOnClickListener(view -> {
            if (release != null && release.getVersionCode() != null) {
                new UpdateReminderStore(this).skip(release.getVersionCode());
                UpdateNotification.clear(this);
                finish();
            }
        });
        currentState = stateStore.load();
        render(currentState);
        boolean shouldCheck = currentState.getPhase() != UpdatePhase.READY_TO_INSTALL
            && currentState.getPhase() != UpdatePhase.QUEUED
            && currentState.getPhase() != UpdatePhase.DOWNLOADING
            && currentState.getPhase() != UpdatePhase.INSTALLING;
        checker = new VersionChecker(this, false, this, shouldCheck);
        checker.setInstallLauncher(installLauncher::launch);
    }

    @Override
    protected void onResume() {
        super.onResume();
        currentState = UpdateStateRecovery.reconcileStored(this);
        render(currentState);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            getOnBackPressedDispatcher().onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void startCheck() {
        if (checker != null) checker.cancel();
        release = null;
        notes.setText(R.string.update_no_release_notes);
        channel.setText("");
        size.setText("");
        checker = new VersionChecker(this, false, this);
        checker.setInstallLauncher(installLauncher::launch);
    }

    @Override
    protected void onDestroy() {
        if (checker != null) checker.cancel();
        stateStore.unregisterListener(stateListener);
        stateHandler.removeCallbacks(refreshState);
        super.onDestroy();
    }

    @Override
    public void onUpdateState(@NonNull UpdateState state) {
        currentState = state;
        render(state);
    }

    @Override
    public void onReleaseAvailable(@NonNull VersionChecker.GitHubRelease release) {
        currentState = stateStore.load();
        if (currentState.getPhase() == UpdatePhase.AVAILABLE
            && Objects.equals(currentState.getVersion(), release.getVersionCode())) {
            this.release = release;
        }
        render(currentState);
    }

    private void render(@NonNull UpdateState state) {
        renderStatusIcon(state.getPhase());
        progress.setVisibility(View.GONE);
        progressLabel.setVisibility(View.GONE);
        progressCard.setVisibility(View.GONE);
        errorCard.setVisibility(View.GONE);
        releaseCard.setVisibility(View.GONE);
        errorDetail.setVisibility(View.GONE);
        secondary.setVisibility(View.VISIBLE);
        skip.setVisibility(View.GONE);
        secondary.setText(R.string.check_for_updates);
        switch (state.getPhase()) {
            case CHECKING:
                status.setText(R.string.update_checking);
                action.setText(R.string.update_checking);
                action.setEnabled(false);
                progressCard.setVisibility(View.VISIBLE);
                progress.setIndeterminate(true);
                secondary.setVisibility(View.GONE);
                break;
            case QUEUED:
                status.setText(R.string.update_queued);
                action.setText(R.string.update_queued);
                action.setEnabled(false);
                progressCard.setVisibility(View.VISIBLE);
                progress.setIndeterminate(true);
                secondary.setText(R.string.update_cancel_download);
                break;
            case DOWNLOADING:
                status.setText(R.string.update_downloading);
                action.setText(R.string.update_downloading);
                action.setEnabled(false);
                progressCard.setVisibility(View.VISIBLE);
                if (state.hasKnownProgress()) {
                    progress.setIndeterminate(false);
                    progress.setProgress((int) Math.min(100L, state.getDownloadedBytes() * 100L / state.getTotalBytes()));
                    progressLabel.setVisibility(View.VISIBLE);
                    progressLabel.setText(getString(
                        R.string.update_progress_format,
                        Formatter.formatFileSize(this, state.getDownloadedBytes()),
                        Formatter.formatFileSize(this, state.getTotalBytes())
                    ));
                } else {
                    progress.setIndeterminate(true);
                }
                secondary.setText(R.string.update_cancel_download);
                break;
            case READY_TO_INSTALL:
                status.setText(R.string.update_ready_to_install);
                action.setText(R.string.update_install);
                action.setEnabled(true);
                secondary.setVisibility(View.GONE);
                break;
            case INSTALLING:
                status.setText(R.string.update_installing);
                action.setText(R.string.update_installing);
                action.setEnabled(false);
                secondary.setVisibility(View.GONE);
                break;
            case ERROR:
                status.setText(R.string.update_error);
                action.setText(R.string.update_retry);
                action.setEnabled(true);
                secondary.setVisibility(View.GONE);
                errorCard.setVisibility(View.VISIBLE);
                errorDetail.setVisibility(View.VISIBLE);
                errorDetail.setText(errorMessage(state.getErrorCode()));
                break;
            case AVAILABLE:
                status.setText(R.string.update_available);
                action.setText(R.string.update_download);
                action.setEnabled(release != null && state.getVersion().equals(release.getVersionCode()));
                if (release != null && state.getVersion().equals(release.getVersionCode())) {
                    secondary.setText(R.string.update_remind_later);
                    skip.setVisibility(View.VISIBLE);
                } else {
                    secondary.setText(R.string.check_for_updates);
                }
                break;
            case IDLE:
            default:
                status.setText(R.string.update_up_to_date);
                action.setText(R.string.check_for_updates);
                action.setEnabled(true);
                secondary.setVisibility(View.GONE);
                break;
        }
        boolean hasTargetVersion = state.getVersion() != null;
        versionFrom.setVisibility(hasTargetVersion ? View.VISIBLE : View.GONE);
        if (hasTargetVersion) {
            versionFrom.setText(getString(R.string.update_current_version_format, BuildConfig.VERSION_NAME));
            version.setText(getString(R.string.update_notification_version, state.getVersion()));
        } else {
            version.setText(getString(R.string.update_current_version_format, BuildConfig.VERSION_NAME));
        }
        UpdateMetadataStore metadataStore = new UpdateMetadataStore(this);
        boolean matchesSelected = state.getVersion() != null && state.getVersion().equals(metadataStore.getSelectedVersion());
        if (matchesSelected) {
            String body = metadataStore.getSelectedBody();
            notes.setText(body == null || body.trim().isEmpty() ? getString(R.string.update_no_release_notes) : body.trim());
            channel.setText(getString(R.string.update_release_channel,
                getString(metadataStore.isSelectedBeta() ? R.string.update_channel_prerelease : R.string.update_channel_stable)));
        } else if (state.getVersion() != null) {
            notes.setText(R.string.update_no_release_notes);
            channel.setText("");
        } else {
            notes.setText("");
            channel.setText("");
        }
        long checkedAt = metadataStore.getLastSuccessfulCheck();
        lastChecked.setText(checkedAt > 0L
            ? getString(R.string.update_last_checked_format, DateFormat.getDateTimeInstance().format(new Date(checkedAt)))
            : getString(R.string.update_last_checked_never));
        releaseCard.setVisibility(notes.getText().length() == 0 ? View.GONE : View.VISIBLE);
        long sizeBytes = state.getTotalBytes() > 0L ? state.getTotalBytes()
            : matchesSelected ? metadataStore.getSelectedSize() : -1L;
        size.setText(sizeBytes > 0L ? getString(R.string.update_size_format, Formatter.formatFileSize(this, sizeBytes)) : "");
        channel.setVisibility(channel.getText().length() == 0 ? View.GONE : View.VISIBLE);
        size.setVisibility(size.getText().length() == 0 ? View.GONE : View.VISIBLE);
        metaRow.setVisibility(channel.getVisibility() == View.VISIBLE || size.getVisibility() == View.VISIBLE
            ? View.VISIBLE : View.GONE);
    }

    private void renderStatusIcon(@NonNull UpdatePhase phase) {
        int drawable;
        int tint;
        switch (phase) {
            case CHECKING:
                drawable = R.drawable.ic_refresh;
                tint = R.attr.colorPrimary;
                break;
            case QUEUED:
                drawable = R.drawable.ic_cover_clock;
                tint = R.attr.colorPrimary;
                break;
            case DOWNLOADING:
                drawable = R.drawable.ic_cover_download;
                tint = R.attr.colorPrimary;
                break;
            case READY_TO_INSTALL:
                drawable = R.drawable.ic_cover_check_circle;
                tint = R.attr.colorPrimary;
                break;
            case INSTALLING:
                drawable = R.drawable.ic_cover_check_circle;
                tint = R.attr.colorPrimary;
                break;
            case ERROR:
                drawable = R.drawable.ic_cover_exclamation_circle;
                tint = R.attr.colorError;
                break;
            case AVAILABLE:
                drawable = R.drawable.ic_cover_download;
                tint = R.attr.colorPrimary;
                break;
            case IDLE:
            default:
                drawable = R.drawable.ic_check_circle;
                tint = R.attr.colorPrimary;
                break;
        }
        statusIcon.setImageResource(drawable);
        statusIcon.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(statusIcon, tint)));
    }

    private String errorMessage(String errorCode) {
        if ("metadata".equals(errorCode) || "background_metadata".equals(errorCode)) {
            return getString(R.string.update_error_metadata);
        }
        if ("download".equals(errorCode) || "interrupted_download".equals(errorCode)) {
            return getString(R.string.update_error_download);
        }
        if ("missing_file".equals(errorCode)) {
            return getString(R.string.update_error_missing_file);
        }
        if ("install_validation".equals(errorCode)) {
            return getString(R.string.update_error_install_validation);
        }
        return getString(R.string.update_error_retry_hint);
    }
}
