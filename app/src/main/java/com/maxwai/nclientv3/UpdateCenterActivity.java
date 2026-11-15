// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;

import com.maxwai.nclientv3.async.VersionChecker;
import com.maxwai.nclientv3.async.update.UpdatePhase;
import com.maxwai.nclientv3.async.update.UpdateState;
import com.maxwai.nclientv3.async.update.UpdateStateStore;
import com.maxwai.nclientv3.components.activities.GeneralActivity;

import java.util.Objects;

public class UpdateCenterActivity extends GeneralActivity implements VersionChecker.Listener {
    private TextView status;
    private TextView version;
    private TextView notes;
    private ProgressBar progress;
    private Button action;
    private Button secondary;
    private VersionChecker checker;
    private VersionChecker.GitHubRelease release;
    private UpdateState currentState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_update_center);
        Toolbar toolbar = findViewById(R.id.update_toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = Objects.requireNonNull(getSupportActionBar());
        actionBar.setTitle(R.string.application_update);
        actionBar.setDisplayHomeAsUpEnabled(true);

        status = findViewById(R.id.update_status);
        version = findViewById(R.id.update_version);
        notes = findViewById(R.id.update_notes);
        progress = findViewById(R.id.update_progress);
        action = findViewById(R.id.update_action);
        secondary = findViewById(R.id.update_secondary);

        action.setOnClickListener(view -> {
            if (currentState != null && currentState.getPhase() == UpdatePhase.READY_TO_INSTALL) {
                checker.installReadyUpdate();
            } else if (release != null) checker.startDownload();
            else startCheck();
        });
        secondary.setOnClickListener(view -> startCheck());
        currentState = new UpdateStateStore(this).load();
        render(currentState);
        startCheck();
    }

    private void startCheck() {
        release = null;
        checker = new VersionChecker(this, false, this);
    }

    @Override
    public void onUpdateState(@NonNull UpdateState state) {
        currentState = state;
        render(state);
    }

    @Override
    public void onReleaseAvailable(@NonNull VersionChecker.GitHubRelease release) {
        this.release = release;
        version.setText(getString(R.string.update_version_format_short, BuildConfig.VERSION_NAME, release.getVersionCode()));
        String body = release.getBody();
        notes.setText(body == null || body.trim().isEmpty() ? getString(R.string.update_no_release_notes) : body.trim());
        action.setText(R.string.update_download);
        action.setEnabled(true);
    }

    private void render(@NonNull UpdateState state) {
        progress.setVisibility(View.GONE);
        secondary.setVisibility(View.VISIBLE);
        switch (state.getPhase()) {
            case CHECKING:
                status.setText(R.string.update_checking);
                action.setText(R.string.update_checking);
                action.setEnabled(false);
                break;
            case DOWNLOADING:
                status.setText(R.string.update_downloading);
                action.setText(R.string.update_downloading);
                action.setEnabled(false);
                progress.setVisibility(View.VISIBLE);
                if (state.hasKnownProgress()) {
                    progress.setIndeterminate(false);
                    progress.setProgress((int) Math.min(100L, state.getDownloadedBytes() * 100L / state.getTotalBytes()));
                } else {
                    progress.setIndeterminate(true);
                }
                break;
            case READY_TO_INSTALL:
                status.setText(R.string.update_ready_to_install);
                action.setText(R.string.update_install);
                action.setEnabled(true);
                break;
            case INSTALLING:
                status.setText(R.string.update_installing);
                action.setText(R.string.update_installing);
                action.setEnabled(false);
                break;
            case ERROR:
                status.setText(R.string.update_error);
                action.setText(R.string.update_retry);
                action.setEnabled(true);
                break;
            case AVAILABLE:
                status.setText(R.string.update_available);
                action.setText(R.string.update_download);
                action.setEnabled(true);
                break;
            case IDLE:
            default:
                status.setText(R.string.update_up_to_date);
                action.setText(R.string.check_for_updates);
                action.setEnabled(true);
                break;
        }
        if (state.getVersion() != null && version.getText().length() == 0) {
            version.setText(getString(R.string.update_version_format_short, BuildConfig.VERSION_NAME, state.getVersion()));
        }
    }
}
