package com.theskyc.nreader;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.theskyc.nreader.async.TagTranslationFeed;
import com.theskyc.nreader.async.database.TagTranslationCatalog;
import com.theskyc.nreader.async.database.TagTranslationManifest;
import com.theskyc.nreader.async.database.export.BackupPreview;
import com.theskyc.nreader.async.database.export.Exporter;
import com.theskyc.nreader.async.database.export.Importer;
import com.theskyc.nreader.async.database.export.Manager;
import com.theskyc.nreader.components.activities.GeneralActivity;
import com.theskyc.nreader.components.views.GeneralPreferenceFragment;
import com.theskyc.nreader.settings.Database;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.utility.LogUtility;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class SettingsActivity extends GeneralActivity {
    GeneralPreferenceFragment fragment;
    private ActivityResultLauncher<String> IMPORT_ZIP;
    private ActivityResultLauncher<String> SAVE_SETTINGS;
    private ActivityResultLauncher<Object> REQUEST_STORAGE_MANAGER;
    private ActivityResultLauncher<String> COPY_LOGS;
    private ActivityResultLauncher<String> notificationPermission;
    private ActivityResultLauncher<String[]> IMPORT_TRANSLATION_PACK;
    private TagTranslationFeed activeTranslationFeed;
    private AlertDialog translationFeedProgress;
    private int selectedItem;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registerActivities();
        //Global.initActivity(this);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = Objects.requireNonNull(getSupportActionBar());
        actionBar.setTitle(R.string.settings);
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setDisplayShowTitleEnabled(true);
        fragment = Objects.requireNonNull((GeneralPreferenceFragment) getSupportFragmentManager().findFragmentById(R.id.fragment));
        fragment.setAct(this);
        fragment.setType(SettingsActivity.Type.values()[getIntent().getIntExtra(getPackageName() + ".TYPE", SettingsActivity.Type.MAIN.ordinal())]);

    }

    private void registerActivities() {
        notificationPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (!granted && !isFinishing()) {
                new MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.notification_permission_denied)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.notification_open_settings, (dialog, which) -> openNotificationSettings())
                    .show();
            }
        });
        IMPORT_TRANSLATION_PACK = registerForActivityResult(new ActivityResultContracts.OpenDocument(), selectedFile -> {
            if (selectedFile != null) importTranslationPack(selectedFile);
        });
        IMPORT_ZIP = registerForActivityResult(new ActivityResultContracts.GetContent(), selectedFile -> {
            if (selectedFile == null) return;
            importSettings(selectedFile);
        });
        SAVE_SETTINGS = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/zip") {
            @NonNull
            @Override
            public Intent createIntent(@NonNull Context context, @NonNull String input) {
                Intent i = super.createIntent(context, input);
                i.setType("application/zip");
                return i;
            }
        }, selectedFile -> {
            if (selectedFile == null) return;

            exportSettings(selectedFile);

        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            REQUEST_STORAGE_MANAGER = registerForActivityResult(new ActivityResultContract<>() {

                @RequiresApi(api = Build.VERSION_CODES.R)
                @NonNull
                @Override
                public Intent createIntent(@NonNull Context context, Object input) {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    return i;
                }

                @Override
                public Object parseResult(int resultCode, @Nullable Intent intent) {
                    return null;
                }
            }, result -> {
                if (Global.isExternalStorageManager()) {
                    fragment.manageCustomPath();
                }
            });
        }
        COPY_LOGS = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/log") {
            @NonNull
            @Override
            public Intent createIntent(@NonNull Context context, @NonNull String input) {
                Intent i = super.createIntent(context, input);
                i.setType("text/log");
                return i;
            }
        }, selectedFile -> {
            if (selectedFile == null) return;
            try {
                Process process = Runtime.getRuntime().exec(new String[]{"logcat", "-d"});
                try (OutputStream outputStream = getContentResolver().openOutputStream(selectedFile);
                     Writer writer = new BufferedWriter(new OutputStreamWriter(outputStream));
                     BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String output = in.lines().collect(Collectors.joining("\n"));
                    writer.write(output);
                }
                Toast.makeText(this, getString(process.exitValue() != 0 ? R.string.copy_logs_fail : R.string.export_finished), Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                LogUtility.e("Error getting logcat", e);
                Toast.makeText(this, getString(R.string.copy_logs_fail), Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void manageTranslationPacks() {
        new Thread(() -> {
            try {
                Map<String, Integer> packs = TagTranslationCatalog.installedPacks(Database.getDatabase());
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) showTranslationPacks(packs);
                });
            } catch (RuntimeException error) {
                LogUtility.e("Unable to list tag translation packs", error);
                runOnUiThread(() -> Toast.makeText(this, R.string.failed, Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void showTranslationPacks(Map<String, Integer> packs) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tag_translation_packs_title)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.tag_translation_install, (dialog, which) ->
                IMPORT_TRANSLATION_PACK.launch(new String[]{"application/json", "text/*"}));
        if (!BuildConfig.TAG_TRANSLATION_MANIFEST_URL.isEmpty()) {
            builder.setNeutralButton(R.string.tag_translation_browse_online, (dialog, which) -> browseTranslationFeed());
        }
        if (packs.isEmpty()) {
            builder.setMessage(R.string.tag_translation_packs_empty);
        } else {
            List<String> locales = new ArrayList<>(packs.keySet());
            String[] labels = locales.stream()
                .map(locale -> getString(R.string.tag_translation_pack_item, locale, packs.get(locale)))
                .toArray(String[]::new);
            builder.setItems(labels, (dialog, which) -> confirmRemoveTranslationPack(locales.get(which)));
        }
        builder.show();
    }

    private void browseTranslationFeed() {
        try {
            TagTranslationFeed feed = new TagTranslationFeed(getApplicationContext(), BuildConfig.TAG_TRANSLATION_MANIFEST_URL);
            runTranslationFeedTask(feed, getText(R.string.tag_translation_online_loading), R.string.tag_translation_online_failed,
                () -> {
                    TagTranslationManifest manifest = feed.readManifest();
                    Map<String, Integer> installed = TagTranslationCatalog.installedPacks(Database.getDatabase());
                    return manifest.getPacks().values().stream()
                        .filter(pack -> pack.revision > installed.getOrDefault(pack.locale, 0))
                        .sorted(Comparator.comparing(pack -> pack.locale))
                        .collect(Collectors.toList());
                }, packs -> showOnlineTranslationPacks(feed, packs));
        } catch (IOException error) {
            LogUtility.e("Unable to open tag translation feed", error);
            Toast.makeText(this, R.string.tag_translation_online_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void showOnlineTranslationPacks(TagTranslationFeed feed, List<TagTranslationManifest.Pack> packs) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tag_translation_browse_online)
            .setNegativeButton(R.string.cancel, null);
        if (packs.isEmpty()) {
            builder.setMessage(R.string.tag_translation_online_empty);
        } else {
            String[] labels = packs.stream().map(this::translationPackLabel).toArray(String[]::new);
            builder.setItems(labels, (dialog, which) -> confirmDownloadTranslationPack(feed, packs.get(which)));
        }
        builder.show();
    }

    private String translationPackLabel(TagTranslationManifest.Pack pack) {
        Locale uiLocale = getResources().getConfiguration().getLocales().get(0);
        String name = Locale.forLanguageTag(pack.locale).getDisplayName(uiLocale);
        return getString(R.string.tag_translation_pack_item, name, pack.revision);
    }

    private void confirmDownloadTranslationPack(TagTranslationFeed feed, TagTranslationManifest.Pack pack) {
        String label = translationPackLabel(pack);
        new MaterialAlertDialogBuilder(this)
            .setTitle(label)
            .setMessage(getString(R.string.tag_translation_online_confirm, Formatter.formatShortFileSize(this, pack.sizeBytes)))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.install, (dialog, which) -> runTranslationFeedTask(feed,
                getString(R.string.downloading_format, label), R.string.tag_translation_install_failed,
                () -> feed.install(Database.getDatabase(), pack), applied -> Toast.makeText(this,
                    applied ? R.string.tag_translation_installed : R.string.tag_translation_not_newer, Toast.LENGTH_LONG).show()))
            .show();
    }

    private <T> void runTranslationFeedTask(TagTranslationFeed feed, CharSequence message, int failureMessage,
                                            TranslationFeedTask<T> task, Consumer<T> success) {
        if (activeTranslationFeed != null) return;
        activeTranslationFeed = feed;
        translationFeedProgress = new MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setView(R.layout.dialog_tag_translation_progress)
            .setNegativeButton(R.string.cancel, (dialog, which) -> cancelTranslationFeedTask())
            .setOnCancelListener(dialog -> cancelTranslationFeedTask())
            .show();
        new Thread(() -> {
            try {
                T result = task.run();
                runOnUiThread(() -> {
                    if (finishTranslationFeedTask(feed)) success.accept(result);
                });
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> {
                    if (finishTranslationFeedTask(feed)) {
                        LogUtility.e("Tag translation feed request failed", error);
                        Toast.makeText(this, failureMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "Tag translation feed").start();
    }

    private boolean finishTranslationFeedTask(TagTranslationFeed feed) {
        if (activeTranslationFeed != feed) return false;
        activeTranslationFeed = null;
        translationFeedProgress.dismiss();
        translationFeedProgress = null;
        return !isFinishing() && !isDestroyed();
    }

    private void cancelTranslationFeedTask() {
        TagTranslationFeed feed = activeTranslationFeed;
        if (feed == null) return;
        feed.cancel();
        finishTranslationFeedTask(feed);
    }

    @Override
    protected void onStop() {
        cancelTranslationFeedTask();
        super.onStop();
    }

    @FunctionalInterface
    private interface TranslationFeedTask<T> {
        T run() throws IOException;
    }

    private void confirmRemoveTranslationPack(String locale) {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tag_translation_remove_title)
            .setMessage(getString(R.string.tag_translation_remove_message, locale))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.tag_translation_remove, (dialog, which) -> new Thread(() -> {
                try {
                    TagTranslationCatalog.remove(Database.getDatabase(), locale);
                    runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.tag_translation_removed, locale), Toast.LENGTH_SHORT).show());
                } catch (RuntimeException error) {
                    LogUtility.e("Unable to remove tag translation pack", error);
                    runOnUiThread(() -> Toast.makeText(this, R.string.failed, Toast.LENGTH_SHORT).show());
                }
            }).start())
            .show();
    }

    private void importTranslationPack(Uri uri) {
        new Thread(() -> {
            try {
                SQLiteDatabase database = Database.getDatabase();
                File scratchDirectory = getCacheDir();
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IOException("Unable to open selected translation pack");
                    boolean changed = TagTranslationCatalog.apply(database, input, scratchDirectory,
                        () -> Thread.currentThread().isInterrupted());
                    runOnUiThread(() -> Toast.makeText(this,
                        changed ? R.string.tag_translation_installed : R.string.tag_translation_not_newer,
                        Toast.LENGTH_LONG).show());
                }
            } catch (IOException | RuntimeException error) {
                LogUtility.e("Unable to install tag translation pack", error);
                runOnUiThread(() -> Toast.makeText(this, R.string.tag_translation_install_failed, Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    public void manageNotifications() {
        boolean permissionMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED;
        if (permissionMissing) {
            new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.notification_permission_title)
                .setMessage(R.string.notification_permission_explanation)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (dialog, which) -> notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS))
                .setNeutralButton(R.string.notification_open_settings, (dialog, which) -> openNotificationSettings())
                .show();
        } else {
            openNotificationSettings();
        }
    }

    private void openNotificationSettings() {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        try {
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException exception) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        }
    }

    private void importSettings(Uri selectedFile) {
        new Thread(() -> {
            try {
                BackupPreview.Summary summary = BackupPreview.inspect(this, selectedFile);
                runOnUiThread(() -> new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.backup_restore_preview)
                    .setMessage(getString(R.string.backup_restore_preview_message, summary.totalRows(),
                        summary.integrityChecked ? getString(R.string.backup_integrity_verified) : getString(R.string.backup_legacy_format)))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.ok, (dialog, which) -> new Manager(selectedFile, this, false, () -> {
                        Toast.makeText(this, R.string.import_finished, Toast.LENGTH_SHORT).show();
                        finish();
                    }).start()).show());
            } catch (IOException exception) {
                LogUtility.e("Unable to preview backup", exception);
                runOnUiThread(() -> Toast.makeText(this, R.string.failed, Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void exportSettings(Uri selectedFile) {
        new Manager(selectedFile, this, true, () -> Toast.makeText(this, R.string.export_finished, Toast.LENGTH_SHORT).show()).start();
    }

    public void importSettings() {
        if (IMPORT_ZIP != null) {
            IMPORT_ZIP.launch("application/zip");
        } else {
            importOldVersion();
        }
    }

    public void restoreLastSnapshot() {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.restore_snapshot)
            .setMessage(R.string.restore_snapshot_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok, (dialog, which) -> new Thread(() -> {
                try {
                    Importer.restoreLastSnapshot(this);
                    runOnUiThread(() -> {
                        Toast.makeText(this, R.string.import_finished, Toast.LENGTH_SHORT).show();
                        finish();
                    });
                } catch (IOException exception) {
                    LogUtility.e("Unable to restore backup snapshot", exception);
                    runOnUiThread(() -> Toast.makeText(this, R.string.failed, Toast.LENGTH_SHORT).show());
                }
            }).start()).show();
    }

    private void importOldVersion() {
        String[] files = Global.BACKUPFOLDER.list();
        if (files == null || files.length == 0) return;
        selectedItem = 0;
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setSingleChoiceItems(files, 0, (dialog, which) -> {
            LogUtility.d(which);
            selectedItem = which;
        });

        builder.setPositiveButton(R.string.ok, (dialog, which) -> importSettings(Uri.fromFile(new File(Global.BACKUPFOLDER, files[selectedItem])))).setNegativeButton(R.string.cancel, null);
        builder.show();
    }

    public void exportSettings() {
        String name = Exporter.defaultExportName(this);
        if (SAVE_SETTINGS != null)
            SAVE_SETTINGS.launch(name);
        else {
            File f = new File(Global.BACKUPFOLDER, name);
            exportSettings(Uri.fromFile(f));
        }
    }

    public void exportLogs() {
        if (COPY_LOGS == null) {
            Toast.makeText(this, R.string.failed, Toast.LENGTH_SHORT).show();
            return;
        }
        Date actualTime = new Date();
        COPY_LOGS.launch(String.format("NReader_Log_%s.log", new SimpleDateFormat("yyMMdd_HHmmss", Locale.getDefault()).format(actualTime)));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @RequiresApi(Build.VERSION_CODES.R)
    public void requestStorageManager() {
        if (REQUEST_STORAGE_MANAGER == null) {
            Toast.makeText(this, R.string.failed, Toast.LENGTH_SHORT).show();
            return;
        }
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setIcon(R.drawable.ic_file);
        builder.setTitle(R.string.requesting_storage_access);
        builder.setMessage(R.string.request_storage_manager_summary);
        builder.setPositiveButton(R.string.ok, (dialog, which) -> REQUEST_STORAGE_MANAGER.launch(null)).setNegativeButton(R.string.cancel, null).show();
    }

    public enum Type {MAIN, COLUMN, DATA}

}
