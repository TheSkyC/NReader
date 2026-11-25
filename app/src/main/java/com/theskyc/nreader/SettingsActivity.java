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
import android.text.TextUtils;
import android.text.format.Formatter;
import android.text.util.Linkify;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
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
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.theskyc.nreader.async.TagTranslationFeed;
import com.theskyc.nreader.async.database.TagTranslationCatalog;
import com.theskyc.nreader.async.database.TagTranslationManifest;
import com.theskyc.nreader.async.database.TagRuleTransfer;
import com.theskyc.nreader.async.database.export.BackupPreview;
import com.theskyc.nreader.async.database.export.Exporter;
import com.theskyc.nreader.async.database.export.Importer;
import com.theskyc.nreader.async.database.export.Manager;
import com.theskyc.nreader.components.activities.GeneralActivity;
import com.theskyc.nreader.components.views.GeneralPreferenceFragment;
import com.theskyc.nreader.settings.Database;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.settings.TagLanguage;
import com.theskyc.nreader.utility.LogUtility;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class SettingsActivity extends GeneralActivity implements PreferenceFragmentCompat.OnPreferenceStartScreenCallback {
    @Override protected int getBlackThemeStyle() {
        return R.style.AppTheme_Material3_Settings_Black;
    }

    private static final String SETTINGS_SCREEN = "settings_screen";
    private static final String CATALOG_CHECK_ID = "catalog_check_id";
    private static final String EXPORT_LOCALE = "translation_export_locale";
    private static final String EXPORT_REVISION = "translation_export_revision";
    private static final String EXPORT_GENERATION = "translation_export_generation";
    private static final String[] ZH_HANS_PUBLISHED_SHA256 = {
        "49df957e214c4431f37b14444a52dc924777423f89bd2403005e2e2f4c8c1d64",
        "26e1672781f481fc135dad920766441b7efd53c75295088991c403d3cb9df40f",
    };
    private static final String[] TRANSLATION_PACK_MIME_TYPES = {
        "application/json", "application/octet-stream", "text/*"
    };
    GeneralPreferenceFragment fragment;
    private ActivityResultLauncher<String> IMPORT_ZIP;
    private ActivityResultLauncher<String> SAVE_SETTINGS;
    private ActivityResultLauncher<Object> REQUEST_STORAGE_MANAGER;
    private ActivityResultLauncher<String> COPY_LOGS;
    private ActivityResultLauncher<String> notificationPermission;
    private ActivityResultLauncher<String[]> IMPORT_TRANSLATION_PACK;
    private ActivityResultLauncher<String> EXPORT_TRANSLATION_PACK;
    private ActivityResultLauncher<String[]> IMPORT_TAG_RULES;
    private ActivityResultLauncher<String> EXPORT_TAG_RULES;
    private ActivityResultLauncher<Intent> settingsScreenLauncher;
    private TagTranslationFeed activeTranslationFeed;
    private AlertDialog translationFeedProgress;
    private AlertDialog catalogCheckDialog;
    private AlertDialog translationImportErrorDialog;
    private AlertDialog translationExportProgress;
    private AlertDialog translationExportResultDialog;
    private CatalogRefreshViewModel catalogRefresh;
    private TranslationImportViewModel translationImport;
    private TranslationExportViewModel translationExport;
    private TranslationExportViewModel.Selection pendingTranslationExport;
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
        int typeIndex = getIntent().getIntExtra(getPackageName() + ".TYPE", Type.MAIN.ordinal());
        Type type = typeIndex >= 0 && typeIndex < Type.values().length ? Type.values()[typeIndex] : Type.MAIN;
        fragment.setType(type);
        if (type == Type.MAIN) {
            String screenKey = getIntent().getStringExtra(SETTINGS_SCREEN);
            if (screenKey != null) {
                PreferenceScreen screen = fragment.findPreference(screenKey);
                if (screen != null && screen.getParent() == fragment.getPreferenceScreen()) {
                    fragment.setPreferenceScreen(screen);
                    actionBar.setTitle(screen.getTitle());
                }
            }
        } else {
            actionBar.setTitle(type == Type.COLUMN ? R.string.settings_list_layout : R.string.settings_network_images);
        }
        for (int index = 0; index < toolbar.getChildCount(); index++) {
            View child = toolbar.getChildAt(index);
            if (child instanceof TextView) {
                TextView title = (TextView) child;
                if (TextUtils.equals(title.getText(), actionBar.getTitle())) {
                    title.setSingleLine(false);
                    title.setEllipsize(null);
                    break;
                }
            }
        }
        catalogRefresh = new ViewModelProvider(this).get(CatalogRefreshViewModel.class);
        catalogRefresh.status().observe(this, this::updateCatalogCheckDialog);
        translationImport = new ViewModelProvider(this).get(TranslationImportViewModel.class);
        translationImport.result().observe(this, this::showTranslationImportResult);
        translationExport = new ViewModelProvider(this).get(TranslationExportViewModel.class);
        translationExport.running().observe(this, this::showTranslationExportProgress);
        translationExport.result().observe(this, this::showTranslationExportResult);
        if (savedInstanceState != null) {
            String locale = savedInstanceState.getString(EXPORT_LOCALE);
            int revision = savedInstanceState.getInt(EXPORT_REVISION);
            long generation = savedInstanceState.getLong(EXPORT_GENERATION, -1);
            if (locale != null && revision > 0 && generation >= 0) {
                pendingTranslationExport = new TranslationExportViewModel.Selection(locale, revision, generation);
            }
            String id = savedInstanceState.getString(CATALOG_CHECK_ID);
            if (id != null) {
                showCatalogCheckDialog();
                catalogRefresh.restore(getApplicationContext(), UUID.fromString(id));
                updateCatalogCheckDialog(catalogRefresh.status().getValue());
            }
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        if (catalogCheckDialog != null && catalogRefresh.requestId() != null) {
            outState.putString(CATALOG_CHECK_ID, catalogRefresh.requestId().toString());
        }
        if (pendingTranslationExport != null) {
            outState.putString(EXPORT_LOCALE, pendingTranslationExport.locale);
            outState.putInt(EXPORT_REVISION, pendingTranslationExport.revision);
            outState.putLong(EXPORT_GENERATION, pendingTranslationExport.generation);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (translationExportProgress != null) {
            translationExportProgress.dismiss();
            translationExportProgress = null;
        }
        if (translationExportResultDialog != null) {
            translationExportResultDialog.setOnDismissListener(null);
            translationExportResultDialog.dismiss();
            translationExportResultDialog = null;
        }
        if (translationImportErrorDialog != null) {
            translationImportErrorDialog.setOnCancelListener(null);
            translationImportErrorDialog.setOnDismissListener(null);
            translationImportErrorDialog.dismiss();
            translationImportErrorDialog = null;
        }
        if (catalogCheckDialog != null) {
            catalogCheckDialog.setOnDismissListener(null);
            catalogCheckDialog.dismiss();
            catalogCheckDialog = null;
        }
        super.onDestroy();
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
        EXPORT_TRANSLATION_PACK = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"),
            this::handleTranslationExportResult);
        IMPORT_TAG_RULES = registerForActivityResult(new ActivityResultContracts.OpenDocument(), selectedFile -> {
            if (selectedFile != null) importTagRules(selectedFile);
        });
        EXPORT_TAG_RULES = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"),
            this::exportTagRules);
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
        settingsScreenLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK) finishAfterDataRestore();
        });
    }

    public void chooseTagLanguage() {
        new Thread(() -> {
            try {
                List<String> locales = new ArrayList<>(TagTranslationCatalog.installedPacks(Database.getDatabase()).keySet());
                locales.sort(String::compareTo);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    String selected = TagLanguage.selected(this);
                    boolean missingSelection = !selected.isEmpty() && !locales.contains(selected);
                    if (missingSelection) locales.add(selected);
                    locales.add(0, "");
                    Locale uiLocale = getResources().getConfiguration().getLocales().get(0);
                    String[] labels = locales.stream().map(locale -> {
                        if (locale.isEmpty()) return getString(R.string.tag_display_language_follow_app);
                        String name = Locale.forLanguageTag(locale).getDisplayName(uiLocale) + " (" + locale + ")";
                        return missingSelection && locale.equals(selected)
                            ? getString(R.string.tag_display_language_uninstalled, name) : name;
                    }).toArray(String[]::new);
                    new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.tag_display_language_title)
                        .setSingleChoiceItems(labels, locales.indexOf(selected), (dialog, which) -> {
                            TagLanguage.select(this, locales.get(which));
                            dialog.dismiss();
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
                });
            } catch (RuntimeException error) {
                LogUtility.e("Unable to list tag display languages", error);
                runOnUiThread(() -> showTranslationToast(R.string.failed, Toast.LENGTH_SHORT));
            }
        }).start();
    }

    public void manageTagRules() {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tag_rule_transfer_title)
            .setItems(new CharSequence[]{getText(R.string.tag_rule_export), getText(R.string.tag_rule_import)},
                (dialog, which) -> {
                    if (which == 0) {
                        try {
                            EXPORT_TAG_RULES.launch("nreader-tag-rules.json");
                        } catch (RuntimeException error) {
                            LogUtility.e("Unable to open tag rule export picker", error);
                            showTranslationToast(R.string.tag_rule_transfer_failed, Toast.LENGTH_LONG);
                        }
                    } else {
                        try {
                            IMPORT_TAG_RULES.launch(new String[]{"application/json", "application/octet-stream", "text/*"});
                        } catch (RuntimeException error) {
                            LogUtility.e("Unable to open tag rule import picker", error);
                            showTranslationToast(R.string.tag_rule_transfer_failed, Toast.LENGTH_LONG);
                        }
                    }
                })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void exportTagRules(Uri destination) {
        if (destination == null) return;
        new Thread(() -> {
            try {
                String json = TagRuleTransfer.exportJson(Database.getDatabase());
                try (OutputStream output = getContentResolver().openOutputStream(destination)) {
                    if (output == null) throw new IOException("Unable to open tag rule destination");
                    output.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                runOnUiThread(() -> showTranslationToast(R.string.tag_rule_export_done, Toast.LENGTH_LONG));
            } catch (Exception error) {
                LogUtility.e("Unable to export tag rules", error);
                runOnUiThread(() -> showTranslationToast(R.string.tag_rule_transfer_failed, Toast.LENGTH_LONG));
            }
        }).start();
    }

    private void importTagRules(Uri source) {
        new Thread(() -> {
            try {
                byte[] bytes = readTagRuleBytes(source);
                TagRuleTransfer.Preview preview = TagRuleTransfer.preview(Database.getDatabase(),
                    new ByteArrayInputStream(bytes));
                runOnUiThread(() -> showTagRulePreview(bytes, preview));
            } catch (Exception error) {
                LogUtility.e("Unable to preview tag rules", error);
                runOnUiThread(() -> showTranslationToast(R.string.tag_rule_transfer_failed, Toast.LENGTH_LONG));
            }
        }).start();
    }

    private void showTagRulePreview(byte[] bytes, TagRuleTransfer.Preview preview) {
        if (isFinishing() || isDestroyed()) return;
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tag_rule_import)
            .setMessage(getString(R.string.tag_rule_import_preview, preview.total, preview.additions,
                preview.updates, preview.unchanged, preview.conflicts))
            .setNegativeButton(R.string.cancel, null);
        if (preview.conflicts == 0) {
            builder.setPositiveButton(R.string.tag_rule_import_merge,
                (dialog, which) -> applyTagRules(bytes, TagRuleTransfer.Mode.MERGE));
            builder.setNeutralButton(R.string.tag_rule_import_replace,
                (dialog, which) -> applyTagRules(bytes, TagRuleTransfer.Mode.REPLACE));
        } else {
            builder.setPositiveButton(R.string.tag_rule_import_conflict, null);
        }
        builder.show();
    }

    private void applyTagRules(byte[] bytes, TagRuleTransfer.Mode mode) {
        new Thread(() -> {
            try {
                TagRuleTransfer.Result result = TagRuleTransfer.apply(Database.getDatabase(),
                    new ByteArrayInputStream(bytes), mode);
                runOnUiThread(() -> showTranslationToast(
                    getString(R.string.tag_rule_import_done, result.affected), Toast.LENGTH_LONG));
            } catch (Exception error) {
                LogUtility.e("Unable to import tag rules", error);
                runOnUiThread(() -> showTranslationToast(R.string.tag_rule_transfer_failed, Toast.LENGTH_LONG));
            }
        }).start();
    }

    private byte[] readTagRuleBytes(Uri source) throws IOException {
        try (InputStream input = getContentResolver().openInputStream(source);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("Unable to open tag rule source");
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > TagRuleTransfer.MAX_BYTES) throw new IOException("Tag rule document is too large");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    public void manageTranslationPacks() {
        new Thread(() -> {
            try {
                SQLiteDatabase database = Database.getDatabase();
                Map<String, TagTranslationCatalog.PackState> packs = TagTranslationCatalog.installedPackStates(database);
                boolean rebuilding = TagTranslationCatalog.isKeyRebuildPending(database);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (rebuilding) showTranslationRebuildNotice(packs);
                    else showTranslationPacks(packs);
                });
            } catch (RuntimeException error) {
                LogUtility.e("Unable to list tag translation packs", error);
                runOnUiThread(() -> showTranslationToast(R.string.failed, Toast.LENGTH_SHORT));
            }
        }).start();
    }

    private void showTranslationRebuildNotice(Map<String, TagTranslationCatalog.PackState> packs) {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tag_translation_rebuild_title)
            .setMessage(R.string.tag_translation_rebuild_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.tag_translation_rebuild_manage, (dialog, which) -> showTranslationPacks(packs))
            .show();
    }

    private void showTranslationPacks(Map<String, TagTranslationCatalog.PackState> packs) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tag_translation_packs_title)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.tag_translation_install, (dialog, which) ->
                IMPORT_TRANSLATION_PACK.launch(TRANSLATION_PACK_MIME_TYPES));
        if (!BuildConfig.TAG_TRANSLATION_MANIFEST_URL.isEmpty()) {
            builder.setNeutralButton(R.string.tag_translation_browse_online, (dialog, which) -> browseTranslationFeed());
        }
        if (packs.isEmpty()) {
            builder.setMessage(R.string.tag_translation_packs_empty);
        } else {
            List<String> locales = new ArrayList<>(packs.keySet());
            String[] labels = locales.stream()
                .map(locale -> getString(R.string.tag_translation_pack_item, locale, packs.get(locale).revision))
                .toArray(String[]::new);
            builder.setItems(labels, (dialog, which) -> {
                String locale = locales.get(which);
                showTranslationPackActions(locale, packs.get(locale));
            });
        }
        builder.show();
    }

    private void showTranslationPackActions(String locale, TagTranslationCatalog.PackState state) {
        new MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.tag_translation_pack_item, locale, state.revision))
            .setItems(new CharSequence[]{getText(R.string.tag_translation_export), getText(R.string.tag_translation_remove)},
                (dialog, which) -> {
                    if (which == 0) beginTranslationExport(locale, state);
                    else confirmRemoveTranslationPack(locale, state);
                })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void beginTranslationExport(String locale, TagTranslationCatalog.PackState state) {
        if (translationExport.isRunning()) {
            showTranslationExportProgress(true);
            return;
        }
        pendingTranslationExport = new TranslationExportViewModel.Selection(locale, state.revision, state.generation);
        try {
            EXPORT_TRANSLATION_PACK.launch("tag-translations-" + locale + "-" + state.revision + ".json");
        } catch (RuntimeException error) {
            pendingTranslationExport = null;
            LogUtility.e("Unable to open translation export picker", error);
            showTranslationToast(R.string.tag_translation_export_picker_failed, Toast.LENGTH_LONG);
        }
    }

    private void handleTranslationExportResult(Uri destination) {
        TranslationExportViewModel.Selection selection = pendingTranslationExport;
        pendingTranslationExport = null;
        if (destination == null) return;
        if (selection == null || !translationExport.start(getApplicationContext(), destination, selection)) {
            showTranslationToast(R.string.tag_translation_export_picker_failed, Toast.LENGTH_LONG);
        }
    }

    private void showTranslationExportProgress(Boolean running) {
        if (!Boolean.TRUE.equals(running)) {
            if (translationExportProgress != null) translationExportProgress.dismiss();
            translationExportProgress = null;
            return;
        }
        if (translationExportProgress != null || isFinishing() || isDestroyed()) return;
        translationExportProgress = new MaterialAlertDialogBuilder(this)
            .setMessage(translationExport.isCancelled() ? R.string.tag_translation_export_cancelling : R.string.tag_translation_exporting)
            .setView(R.layout.dialog_tag_translation_progress)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.tag_catalog_check_close, null)
            .setOnDismissListener(dialog -> {
                if (translationExportProgress == dialog) translationExportProgress = null;
            })
            .show();
        translationExportProgress.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!translationExport.isCancelled());
        translationExportProgress.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> {
            translationExport.cancel();
            translationExportProgress.setMessage(getString(R.string.tag_translation_export_cancelling));
            translationExportProgress.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
        });
    }

    private void showTranslationExportResult(TranslationExportViewModel.Result result) {
        if (result == null || result.isConsumed() || isFinishing() || isDestroyed()) return;
        if (translationExportResultDialog != null && translationExportResultDialog.isShowing()) return;
        int message;
        if (result.error == null) {
            message = R.string.tag_translation_export_done_without_attribution;
        } else if (result.error instanceof TagTranslationCatalog.UnknownAliasOrderException) {
            message = R.string.tag_translation_export_unknown_order;
        } else if (result.error instanceof TagTranslationCatalog.DuplicateExportAliasException) {
            message = R.string.tag_translation_export_duplicate_alias;
        } else if (result.error instanceof TranslationExportViewModel.ChangedSelectionException
            || result.error instanceof TagTranslationCatalog.ChangedDuringExportException) {
            message = R.string.tag_translation_export_changed;
        } else {
            message = R.string.tag_translation_export_failed;
        }
        translationExportResultDialog = new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tag_translation_export)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener(dialog -> {
                if (translationExportResultDialog != dialog) return;
                translationExportResultDialog = null;
                result.consume();
            })
            .show();
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

    private static boolean isPublishedZhHansPayload(TagTranslationManifest.Pack pack) {
        for (String digest : ZH_HANS_PUBLISHED_SHA256) {
            if (pack.hasPayloadDigest(digest)) return true;
        }
        return false;
    }

    private void confirmDownloadTranslationPack(TagTranslationFeed feed, TagTranslationManifest.Pack pack) {
        String label = translationPackLabel(pack);
        String size = Formatter.formatShortFileSize(this, pack.sizeBytes);
        boolean knownPack = "zh-Hans".equals(pack.locale) && isPublishedZhHansPayload(pack);
        String message = knownPack
            ? getString(R.string.tag_translation_online_confirm_zh_hans, size,
                getString(R.string.tag_translation_zh_hans_source_url),
                getString(R.string.tag_translation_zh_hans_license_url))
            : getString(R.string.tag_translation_online_confirm, size);
        AlertDialog confirmationDialog = new MaterialAlertDialogBuilder(this)
            .setTitle(label)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.install, (dialog, which) -> runTranslationFeedTask(feed,
                getString(R.string.downloading_format, label), R.string.tag_translation_install_failed,
                () -> feed.install(Database.getDatabase(), pack), applied -> Toast.makeText(this,
                    applied ? R.string.tag_translation_installed : R.string.tag_translation_not_newer, Toast.LENGTH_LONG).show()))
            .show();
        if (knownPack) {
            TextView text = confirmationDialog.findViewById(android.R.id.message);
            if (text != null) Linkify.addLinks(text, Linkify.WEB_URLS);
        }
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
                        showTranslationFailure(error, failureMessage);
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

    private void confirmRemoveTranslationPack(String locale, TagTranslationCatalog.PackState state) {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tag_translation_remove_title)
            .setMessage(getString(R.string.tag_translation_remove_message, locale))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.tag_translation_remove, (dialog, which) -> new Thread(() -> {
                try {
                    boolean removed = TagTranslationCatalog.removeIfUnchanged(Database.getDatabase(), locale, state);
                    runOnUiThread(() -> showTranslationToast(removed
                        ? getString(R.string.tag_translation_removed, locale)
                        : getString(R.string.tag_translation_remove_changed), Toast.LENGTH_LONG));
                } catch (RuntimeException error) {
                    LogUtility.e("Unable to remove tag translation pack", error);
                    runOnUiThread(() -> showTranslationToast(R.string.failed, Toast.LENGTH_SHORT));
                }
            }).start())
            .show();
    }

    private void showTranslationToast(int messageId, int duration) {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, messageId, duration).show();
    }

    private void showTranslationFailure(Throwable error, int fallbackMessage) {
        showTranslationFailure(error, fallbackMessage, null);
    }

    private void showTranslationFailure(Throwable error, int fallbackMessage,
                                        TranslationImportViewModel.Result pending) {
        if (isFinishing() || isDestroyed()) return;
        if (error instanceof TagTranslationCatalog.UnknownTagException) {
            if (pending != null && translationImportErrorDialog != null
                && translationImportErrorDialog.isShowing()) return;
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.tag_translation_packs_title);
            if (BuildConfig.TAG_CATALOG_MANIFEST_URL.isEmpty()) {
                builder.setMessage(R.string.tag_translation_unknown_tags_no_source)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        if (pending != null) pending.consume();
                    });
            } else {
                builder.setMessage(R.string.tag_translation_unknown_tags)
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                        if (pending != null) pending.consume();
                    })
                    .setPositiveButton(R.string.tag_catalog_check_updates, (dialog, which) -> {
                        if (pending != null) pending.consume();
                        showCatalogCheckDialog();
                        catalogRefresh.start(getApplicationContext());
                    });
            }
            if (pending == null) {
                builder.show();
            } else {
                builder.setOnCancelListener(dialog -> {
                    if (translationImportErrorDialog == dialog) pending.consume();
                });
                builder.setOnDismissListener(dialog -> {
                    if (translationImportErrorDialog != dialog) return;
                    translationImportErrorDialog = null;
                    pending.consume();
                });
                translationImportErrorDialog = builder.show();
            }
        } else if (error instanceof TagTranslationCatalog.ChangedDuringImportException) {
            Toast.makeText(this, R.string.tag_translation_import_changed, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, fallbackMessage, Toast.LENGTH_LONG).show();
        }
    }

    public void checkTagCatalogUpdates() {
        showCatalogCheckDialog();
        catalogRefresh.start(getApplicationContext());
    }

    private void showCatalogCheckDialog() {
        if (catalogCheckDialog != null && catalogCheckDialog.isShowing()) return;
        catalogCheckDialog = new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tag_catalog_check_updates)
            .setMessage(R.string.tag_catalog_check_queued)
            .setPositiveButton(R.string.tag_catalog_check_close, null)
            .setNeutralButton(R.string.tag_catalog_check_retry, null)
            .setOnDismissListener(dialog -> {
                if (catalogCheckDialog != dialog) return;
                catalogCheckDialog = null;
                catalogRefresh.clearRequest();
            })
            .show();
        catalogCheckDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(View.GONE);
        catalogCheckDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view ->
            catalogRefresh.start(getApplicationContext()));
    }

    private void updateCatalogCheckDialog(CatalogRefreshViewModel.Status status) {
        if (catalogCheckDialog == null || status == null) return;
        int message;
        switch (status) {
            case QUEUED: message = R.string.tag_catalog_check_queued; break;
            case RUNNING: message = R.string.tag_catalog_check_running; break;
            case CHECKED: message = R.string.tag_catalog_check_complete; break;
            case UPDATED: message = R.string.tag_catalog_check_updated; break;
            case NO_SOURCE: message = R.string.tag_catalog_check_no_source; break;
            case CANCELLED: message = R.string.tag_catalog_check_cancelled; break;
            default: message = R.string.tag_catalog_check_failed; break;
        }
        catalogCheckDialog.setMessage(getString(message));
        catalogCheckDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(
            status == CatalogRefreshViewModel.Status.FAILED || status == CatalogRefreshViewModel.Status.CANCELLED
                ? View.VISIBLE : View.GONE);
    }

    private void showTranslationToast(CharSequence message, int duration) {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, message, duration).show();
    }

    private void importTranslationPack(Uri uri) {
        if (!translationImport.start(getApplicationContext(), uri)) {
            showTranslationToast(R.string.tag_translation_import_in_progress, Toast.LENGTH_SHORT);
        }
    }

    private void showTranslationImportResult(TranslationImportViewModel.Result result) {
        if (result == null || isFinishing() || isDestroyed() || result.isConsumed()) return;
        if (result.error instanceof TagTranslationCatalog.UnknownTagException) {
            showTranslationFailure(result.error, R.string.tag_translation_install_failed, result);
        } else {
            if (!result.consume()) return;
            if (result.error != null) {
                showTranslationFailure(result.error, R.string.tag_translation_install_failed);
            } else {
                showTranslationToast(result.changed ? R.string.tag_translation_installed : R.string.tag_translation_not_newer,
                    Toast.LENGTH_LONG);
            }
        }
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
                        finishAfterDataRestore();
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
                        finishAfterDataRestore();
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
    public boolean onPreferenceStartScreen(PreferenceFragmentCompat caller, PreferenceScreen screen) {
        Intent intent = new Intent(this, SettingsActivity.class);
        intent.putExtra(SETTINGS_SCREEN, screen.getKey());
        settingsScreenLauncher.launch(intent);
        return true;
    }

    void finishAfterDataRestore() {
        setResult(RESULT_OK);
        finish();
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
