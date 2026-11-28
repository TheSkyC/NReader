// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.views;

import static androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK;
import static androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL;

import android.annotation.SuppressLint;
import android.app.UiModeManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.JsonWriter;
import android.view.View;
import android.webkit.CookieManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.AppCompatAutoCompleteTextView;
import androidx.biometric.BiometricManager;
import androidx.core.content.ContextCompat;
import androidx.core.os.LocaleListCompat;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;
import com.theskyc.nreader.BuildConfig;
import com.theskyc.nreader.CopyToClipboardActivity;
import com.theskyc.nreader.AccountLoginActivity;
import com.theskyc.nreader.AccountsActivity;
import com.theskyc.nreader.R;
import com.theskyc.nreader.SettingsActivity;
import com.theskyc.nreader.UpdateCenterActivity;
import com.theskyc.nreader.StatusManagerActivity;
import com.theskyc.nreader.api.OnlineFavoriteClient;
import com.theskyc.nreader.async.database.OnlineFavoritePageStore;
import com.theskyc.nreader.async.database.SearchPageStore;
import com.theskyc.nreader.async.MetadataFetcher;
import com.theskyc.nreader.async.VersionChecker;
import com.theskyc.nreader.async.database.export.Importer;
import com.theskyc.nreader.components.activities.CrashApplication;
import com.theskyc.nreader.components.cache.ImageCacheManager;
import com.theskyc.nreader.components.cache.ManagedCacheStorage;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.settings.Login;
import com.theskyc.nreader.settings.MainGridSettings;
import com.theskyc.nreader.settings.TagDisplayMode;
import com.theskyc.nreader.utility.LogUtility;
import com.theskyc.nreader.utility.DebugImageMask;
import com.theskyc.nreader.utility.Utility;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

public class GeneralPreferenceFragment extends PreferenceFragmentCompat {
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ExecutorService FAVORITE_PAGE_CACHE_WORKER = Executors.newSingleThreadExecutor();
    private SettingsActivity act;
    private final AtomicInteger snapshotCheckGeneration = new AtomicInteger();
    private final AtomicInteger favoritePageCacheGeneration = new AtomicInteger();
    private final AtomicInteger favoritePageMetricsGeneration = new AtomicInteger();
    private final AtomicInteger searchPageCacheGeneration = new AtomicInteger();
    private final AtomicInteger searchPageMetricsGeneration = new AtomicInteger();
    private final AtomicInteger managedCacheGeneration = new AtomicInteger();
    @Nullable private ManagedCacheStorage.Usage managedCacheUsage;
    private final IntConsumer favoriteCacheObserver = galleryId -> {
        updateFavoriteCacheUsage();
        updateFavoritePageCacheUsage();
    };

    public void setAct(SettingsActivity act) {
        this.act = act;
    }

    public void setType(SettingsActivity.Type type) {
        switch (type) {
            case MAIN:
                mainMenu();
                break;
            case COLUMN:
                columnMenu();
                break;
            case DATA:
                dataMenu();
                break;
        }
    }

    private void dataMenu() {
        addPreferencesFromResource(R.xml.settings_data);
        configureDataUsage(Objects.requireNonNull(findPreference(getString(R.string.key_mobile_usage))));
        configureDataUsage(Objects.requireNonNull(findPreference(getString(R.string.key_wifi_usage))));
    }

    private void configureDataUsage(Preference preference) {
        SharedPreferences settings = getPreferenceManager().getSharedPreferences();
        int value = settings.getInt(preference.getKey(), 2);
        preference.setSummary(getDataUsageString(value));
        preference.setOnPreferenceClickListener(clicked -> {
            CharSequence[] options = {
                getText(R.string.data_usage_no),
                getText(R.string.data_usage_thumb),
                getText(R.string.data_usage_full)
            };
            int selected = settings.getInt(preference.getKey(), 2);
            new MaterialAlertDialogBuilder(requireContext())
                .setTitle(preference.getTitle())
                .setSingleChoiceItems(options, selected >= 0 && selected < options.length ? selected : -1,
                    (dialog, which) -> {
                        settings.edit().putInt(preference.getKey(), which).apply();
                        preference.setSummary(getDataUsageString(which));
                        dialog.dismiss();
                    })
                .setNegativeButton(R.string.cancel, null)
                .show();
            return true;
        });
    }

    private int getDataUsageString(int val) {
        switch (val) {
            case 0:
                return R.string.data_usage_no;
            case 1:
                return R.string.data_usage_thumb;
            case 2:
                return R.string.data_usage_full;
        }
        return R.string.data_usage_full;
    }

    private LocaleListCompat getLocaleListFromXml() {
        List<CharSequence> tagsList = new ArrayList<>();
        try {
            @SuppressLint("DiscouragedApi") int id = getResources().getIdentifier(
                "_generated_res_locale_config",
                "xml",
                requireContext().getPackageName()
            );
            XmlPullParser xpp = getResources().getXml(id);
            while (xpp.getEventType() != XmlPullParser.END_DOCUMENT) {
                if (xpp.getEventType() == XmlPullParser.START_TAG) {
                    if (Objects.equals(xpp.getName(), "locale")) {
                        tagsList.add(xpp.getAttributeValue(0));
                    }
                }
                xpp.next();
            }
        } catch (XmlPullParserException | IOException e) {
            LogUtility.w("Problem parsing locales xml", e);
        }
        return LocaleListCompat.forLanguageTags(tagsList.stream()
            .reduce((a, b) -> a + "," + b)
            .orElse("")
            .toString());
    }

    private void fillRoba() {
        LocaleListCompat setLocaleList = AppCompatDelegate.getApplicationLocales();
        Locale actualLocale = setLocaleList.isEmpty() ? Locale.ENGLISH : Objects.requireNonNull(setLocaleList.get(0));

        ListPreference preference = Objects.requireNonNull(findPreference(getString(R.string.preference_key_language)));
        LocaleListCompat localeList = getLocaleListFromXml();

        String[] languagesEntry = new String[localeList.size() + 1];
        String[] languagesNames = new String[localeList.size() + 1];
        // System language
        languagesEntry[0] = getString(R.string.key_default_value);
        languagesNames[0] = Character.toUpperCase(getString(R.string.system_default).charAt(0))
            + getString(R.string.system_default).substring(1);
        // Other languages
        for (int i = 0; i < localeList.size(); i++) {
            Locale locale = Objects.requireNonNull(localeList.get(i));
            languagesEntry[i + 1] = locale.toLanguageTag();
            languagesNames[i + 1] = Character.toUpperCase(locale.getDisplayName(actualLocale).charAt(0))
                + locale.getDisplayName(actualLocale).substring(1);
        }

        preference.setEntryValues(languagesEntry);
        preference.setEntries(languagesNames);

    }

    @SuppressLint("ApplySharedPref")
    private void mainMenu() {
        TagDisplayMode.normalizePreference(requireContext());
        addPreferencesFromResource(R.xml.settings);

        Preference tapTurn = Objects.requireNonNull(findPreference(getString(R.string.preference_key_change_page_buttons)));
        tapTurn.setOnPreferenceChangeListener((preference, value) -> {
            Global.setDefaultButtonChangePage(requireContext(), (Boolean) value);
            return true;
        });

        Preference notifications = Objects.requireNonNull(findPreference(getString(R.string.preference_key_notification_permission)));
        notifications.setOnPreferenceClickListener(preference -> {
            act.manageNotifications();
            return true;
        });

        if (BuildConfig.DEBUG) {
            SwitchPreferenceCompat mask = new SwitchPreferenceCompat(requireContext());
            mask.setTitle(R.string.debug_mask_images);
            mask.setSummary(R.string.debug_mask_images_summary);
            mask.setIconSpaceReserved(false);
            mask.setPersistent(false);
            mask.setChecked(DebugImageMask.isEnabled(requireContext()));
            mask.setOnPreferenceChangeListener((preference, value) -> {
                DebugImageMask.setEnabled(requireContext(), (Boolean) value);
                return true;
            });
            PreferenceScreen maintenance = Objects.requireNonNull(findPreference("settings_screen_data"));
            maintenance.addPreference(mask);
        }

        Preference updateSettings = Objects.requireNonNull(findPreference(getString(R.string.preference_key_update_settings)));
        updateSettings.setVisible(!BuildConfig.UPDATE_REPOSITORY.isEmpty() || !BuildConfig.UPDATE_ENDPOINT.isEmpty());

        fillRoba();

        Preference accountLogin = Objects.requireNonNull(findPreference(getString(R.string.preference_key_account_login)));
        accountLogin.setOnPreferenceClickListener(preference -> {
            act.startActivity(new Intent(act, AccountsActivity.class));
            return true;
        });
        Preference translationPacks = Objects.requireNonNull(findPreference(getString(R.string.preference_key_tag_translation_packs)));
        translationPacks.setOnPreferenceClickListener(preference -> {
            act.manageTranslationPacks();
            return true;
        });
        Preference catalogUpdates = Objects.requireNonNull(findPreference(getString(R.string.preference_key_tag_catalog_updates)));
        catalogUpdates.setOnPreferenceClickListener(preference -> {
            act.checkTagCatalogUpdates();
            return true;
        });
        Preference tagRuleTransfer = Objects.requireNonNull(findPreference(getString(R.string.preference_key_tag_rule_transfer)));
        tagRuleTransfer.setOnPreferenceClickListener(preference -> {
            act.manageTagRules();
            return true;
        });
        Preference tagLanguage = Objects.requireNonNull(findPreference(getString(R.string.preference_key_tag_display_language)));
        tagLanguage.setOnPreferenceClickListener(preference -> {
            act.chooseTagLanguage();
            return true;
        });

        {
            Preference statusScreen = Objects.requireNonNull(findPreference(getString(R.string.preference_key_status_screen)));
            statusScreen.setOnPreferenceClickListener(preference -> {
                Intent i = new Intent(act, StatusManagerActivity.class);
                act.runOnUiThread(() -> act.startActivity(i));
                return false;
            });
        }
        {
            Preference colScreen = Objects.requireNonNull(findPreference(getString(R.string.preference_key_col_screen)));
            colScreen.setOnPreferenceClickListener(preference -> {
                Intent i = new Intent(act, SettingsActivity.class);
                i.putExtra(act.getPackageName() + ".TYPE", SettingsActivity.Type.COLUMN.ordinal());
                act.runOnUiThread(() -> act.startActivity(i));
                return false;
            });
        }
        {
            Preference dataScreen = Objects.requireNonNull(findPreference(getString(R.string.preference_key_data_screen)));
            dataScreen.setOnPreferenceClickListener(preference -> {
                Intent i = new Intent(act, SettingsActivity.class);
                i.putExtra(act.getPackageName() + ".TYPE", SettingsActivity.Type.DATA.ordinal());
                act.runOnUiThread(() -> act.startActivity(i));
                return false;
            });
        }
        {
            Preference fetchMetadata = Objects.requireNonNull(findPreference(getString(R.string.preference_key_fetch_metadata)));
            fetchMetadata.setVisible(Global.hasStoragePermission(act));
            fetchMetadata.setOnPreferenceClickListener(preference -> {
                new Thread(new MetadataFetcher(act)).start();
                return true;
            });
        }
        {
            Preference fakeIcon = Objects.requireNonNull(findPreference(getString(R.string.preference_key_fake_icon)));
            fakeIcon.setOnPreferenceChangeListener((preference, newValue) -> {
                PackageManager pm = act.getPackageManager();
                ComponentName name1 = new ComponentName(act, "com.maxwai.nclientv3.components.launcher.LauncherReal");
                ComponentName name2 = new ComponentName(act, "com.maxwai.nclientv3.components.launcher.LauncherCalculator");
                if ((boolean) newValue) {
                    changeLauncher(pm, name1, false);
                    changeLauncher(pm, name2, true);
                } else {
                    changeLauncher(pm, name1, true);
                    changeLauncher(pm, name2, false);
                }
                return true;
            });
        }
        {
            Preference useAccountTag = Objects.requireNonNull(findPreference(getString(R.string.preference_key_use_account_tag)));
            updateAccountTagAvailability(useAccountTag);
        }

        {
            Preference themeSelect = Objects.requireNonNull(findPreference(getString(R.string.preference_key_theme_select)));
            themeSelect.setOnPreferenceChangeListener((preference, newValue) -> {
                String newTheme = (String) newValue;
                String[] availableThemes = getResources().getStringArray(R.array.theme_data);
                assert availableThemes.length == 3;
                if (Arrays.stream(availableThemes).noneMatch(newTheme::equals)) {
                    return false;
                }
                SharedPreferences settings = act.getSharedPreferences("Settings", 0);
                String oldTheme = settings.getString(getString(R.string.preference_key_theme_select), availableThemes[0]);
                boolean blackChanged = settings.getBoolean(getString(R.string.preference_key_black_theme), false)
                    != newTheme.equals(availableThemes[2]);
                settings
                    .edit()
                    .putBoolean(getString(R.string.preference_key_black_theme), newTheme.equals(availableThemes[2])) // black
                    .apply();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    int theme;
                    if (newTheme.equals(availableThemes[0])) { // light
                        theme = UiModeManager.MODE_NIGHT_NO;
                    } else if (newTheme.equals(availableThemes[1]) || newTheme.equals(availableThemes[2])) { // dark / black
                        theme = UiModeManager.MODE_NIGHT_YES;
                    } else {
                        return false;
                    }
                    UiModeManager uim = (UiModeManager) act.getSystemService(Context.UI_MODE_SERVICE);
                    uim.setApplicationNightMode(theme);
                } else {
                    CrashApplication.setDarkLightTheme(newTheme, act);
                }
                if (blackChanged && !availableThemes[0].equals(oldTheme)
                    && !availableThemes[0].equals(newTheme)) {
                    act.getWindow().getDecorView().post(act::recreate);
                }
                return true;
            });
        }
        {
            Preference keyLanguage = Objects.requireNonNull(findPreference(getString(R.string.preference_key_language)));
            keyLanguage.setOnPreferenceChangeListener((preference, newValue) -> {
                LocaleListCompat newLocale;
                if (newValue.equals(getString(R.string.key_default_value))) {
                    newLocale = LocaleListCompat.getEmptyLocaleList();
                } else {
                    newLocale = LocaleListCompat.forLanguageTags((String) newValue);
                }
                ContextCompat.getMainExecutor(act).execute(() -> AppCompatDelegate.setApplicationLocales(newLocale));
                return true;
            });
        }
        {
            Preference enableBeta = Objects.requireNonNull(findPreference(getString(R.string.preference_key_enable_beta)));
            enableBeta.setOnPreferenceChangeListener((preference, newValue) -> {
                //Instant update to allow search for updates
                Global.setEnableBeta((Boolean) newValue);
                return true;
            });
        }
        {
            Preference hasCredentials = Objects.requireNonNull(findPreference(getString(R.string.preference_key_has_credentials)));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                hasCredentials.setOnPreferenceChangeListener((preference, newValue) -> {
                    if (newValue.equals(Boolean.TRUE)) {
                        BiometricManager biometricManager = BiometricManager.from(act);
                        switch (biometricManager.canAuthenticate(BIOMETRIC_WEAK | DEVICE_CREDENTIAL)) {
                            case BiometricManager.BIOMETRIC_SUCCESS:
                                break;
                            case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED:
                                // Prompts the user to create credentials that your app accepts.
                                final Intent enrollIntent = new Intent(Settings.ACTION_BIOMETRIC_ENROLL);
                                enrollIntent.putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                                    BIOMETRIC_WEAK | DEVICE_CREDENTIAL);
                                startActivity(enrollIntent);
                            case BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE:
                            case BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE:
                            case BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED:
                            case BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED:
                            case BiometricManager.BIOMETRIC_STATUS_UNKNOWN:
                                return false;
                        }
                    }
                    return true;
                });
            } else {
                hasCredentials.setEnabled(false);
                hasCredentials.setSummary(R.string.setting_device_credentials_low_sdk);
            }
        }

        {
            Preference keyVersion = Objects.requireNonNull(findPreference(getString(R.string.preference_key_version)));
            keyVersion.setTitle(getString(R.string.app_version_format, BuildConfig.VERSION_NAME));
        }
        {
            ListPreference savePath = Objects.requireNonNull(findPreference(getString(R.string.preference_key_save_path)));
            initStoragePaths(savePath);
            Preference storageCategory = Objects.requireNonNull(findPreference("settings_storage_category"));
            storageCategory.setVisible(savePath.isVisible());
            savePath.setOnPreferenceChangeListener((preference, newValue) -> {
                if (!newValue.equals(getString(R.string.custom_path))) return true;
                manageCustomPath();
                return false;
            });
        }
        {
            ListPreference imageCacheQuota = Objects.requireNonNull(
                findPreference(getString(R.string.preference_key_image_cache_quota)));
            imageCacheQuota.setSummaryProvider(preference -> {
                CharSequence entry = ((ListPreference) preference).getEntry();
                return getString(R.string.summary_image_cache_quota,
                    entry == null ? getString(R.string.image_cache_default_quota) : entry);
            });

            Preference cacheOverview = Objects.requireNonNull(
                findPreference(getString(R.string.preference_key_managed_cache_usage)));
            cacheOverview.setEnabled(false);
            cacheOverview.setOnPreferenceClickListener(preference -> {
                ManagedCacheStorage.Usage usage = managedCacheUsage;
                if (usage == null) return true;
                new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.title_managed_cache_usage)
                    .setMessage(getString(R.string.managed_cache_usage_details,
                        formatManagedCacheBytes(usage.imageBytes),
                        formatManagedCacheBytes(usage.favoritePageBytes),
                        formatManagedCacheBytes(usage.searchPageBytes),
                        formatManagedCacheBytes(usage.readerSnapshotBytes),
                        formatManagedCacheBytes(usage.totalBytes())))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
                return true;
            });

            ListPreference favoriteStateLimit = Objects.requireNonNull(
                findPreference(getString(R.string.preference_key_online_favorite_state_limit)));
            favoriteStateLimit.setSummaryProvider(preference -> {
                CharSequence entry = ((ListPreference) preference).getEntry();
                return getString(R.string.summary_online_favorite_state_limit,
                    entry == null ? getString(R.string.online_favorite_state_limit_default) : entry);
            });
            favoriteStateLimit.setOnPreferenceChangeListener((preference, newValue) -> {
                OnlineFavoriteClient.applyCacheCapacity(act, String.valueOf(newValue));
                return true;
            });

            ListPreference favoritePageLimit = Objects.requireNonNull(
                findPreference(getString(R.string.preference_key_online_favorite_page_limit)));
            favoritePageLimit.setSummaryProvider(preference -> {
                CharSequence entry = ((ListPreference) preference).getEntry();
                return getString(R.string.summary_online_favorite_page_limit,
                    entry == null ? getString(R.string.online_favorite_page_limit_default) : entry);
            });
            favoritePageLimit.setOnPreferenceChangeListener((preference, newValue) -> {
                scheduleFavoritePageCacheTrim();
                return true;
            });

            ListPreference favoritePageQuota = Objects.requireNonNull(
                findPreference(getString(R.string.preference_key_online_favorite_page_quota)));
            favoritePageQuota.setSummaryProvider(preference -> {
                CharSequence entry = ((ListPreference) preference).getEntry();
                return getString(R.string.summary_online_favorite_page_quota,
                    entry == null ? getString(R.string.online_favorite_page_quota_default) : entry);
            });
            favoritePageQuota.setOnPreferenceChangeListener((preference, newValue) -> {
                scheduleFavoritePageCacheTrim();
                return true;
            });

            Preference favoritePageUsage = Objects.requireNonNull(
                findPreference(getString(R.string.preference_key_online_favorite_page_usage)));
            favoritePageUsage.setSelectable(false);
            Preference favoritePageMetrics = Objects.requireNonNull(
                findPreference(getString(R.string.preference_key_online_favorite_page_metrics)));
            favoritePageMetrics.setSelectable(false);
            Preference clearFavoritePages = Objects.requireNonNull(
                findPreference(getString(R.string.preference_key_online_favorite_pages_clear)));
            clearFavoritePages.setEnabled(false);
            clearFavoritePages.setOnPreferenceClickListener(preference -> {
                String identity = OnlineFavoriteClient.sessionIdentity(requireContext());
                if (identity == null) return true;
                new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.clear_online_favorite_pages)
                    .setMessage(R.string.message_clear_online_favorite_pages)
                    .setNegativeButton(R.string.no, null)
                    .setPositiveButton(R.string.yes, (dialog, which) -> clearFavoritePages(identity, clearFavoritePages))
                    .show();
                return true;
            });

            ListPreference searchPageQuota = Objects.requireNonNull(
                findPreference(getString(R.string.preference_key_search_page_quota)));
            searchPageQuota.setSummaryProvider(preference -> {
                CharSequence entry = ((ListPreference) preference).getEntry();
                return getString(R.string.summary_search_page_quota,
                    entry == null ? getString(R.string.search_page_quota_default) : entry);
            });
            searchPageQuota.setOnPreferenceChangeListener((preference, newValue) -> {
                scheduleSearchPageCacheTrim();
                return true;
            });

            Preference searchPageUsage = Objects.requireNonNull(
                findPreference(getString(R.string.preference_key_search_page_usage)));
            searchPageUsage.setSelectable(false);
            Preference searchPageMetrics = Objects.requireNonNull(
                findPreference(getString(R.string.preference_key_search_page_metrics)));
            searchPageMetrics.setSelectable(false);
            Preference clearSearchPages = Objects.requireNonNull(
                findPreference(getString(R.string.preference_key_search_pages_clear)));
            clearSearchPages.setEnabled(false);
            clearSearchPages.setOnPreferenceClickListener(preference -> {
                new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.clear_search_pages)
                    .setMessage(R.string.message_clear_search_pages)
                    .setNegativeButton(R.string.no, null)
                    .setPositiveButton(R.string.yes, (dialog, which) -> clearSearchPages(clearSearchPages))
                    .show();
                return true;
            });

            Preference cache = Objects.requireNonNull(findPreference(getString(R.string.preference_key_cache)));
            cache.setEnabled(false);
            ImageCacheManager.measure(act, (bytes, error) -> {
                if (!isAdded() || act == null || act.isFinishing()) return;
                cache.setEnabled(true);
                updateImageCacheSummary(cache, bytes, error);
            });
            cache.setOnPreferenceClickListener(preference -> {
                MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(act);
                builder.setTitle(R.string.clear_image_cache);
                builder.setMessage(R.string.message_clear_image_cache);
                builder.setPositiveButton(R.string.yes, (dialog, which) -> {
                    cache.setEnabled(false);
                    ImageCacheManager.clear(act, (bytes, error) -> {
                        if (!isAdded() || act == null || act.isFinishing()) return;
                        cache.setEnabled(true);
                        updateImageCacheSummary(cache, bytes, error);
                        updateManagedCacheUsage();
                        Toast.makeText(act, error == null
                            ? R.string.image_cache_cleared : R.string.image_cache_clear_failed, Toast.LENGTH_SHORT).show();
                    });
                }).setNegativeButton(R.string.no, null).setCancelable(true);
                builder.show();

                return true;
            });
        }
        {
            Preference cookie = Objects.requireNonNull(findPreference(getString(R.string.preference_key_cookie)));
            cookie.setOnPreferenceClickListener(preference -> {
                new MaterialAlertDialogBuilder(act)
                    .setTitle(R.string.settings_clear_web_cookies)
                    .setMessage(R.string.settings_clear_web_cookies_summary)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.clear_cookies, (dialog, which) ->
                        CookieManager.getInstance().removeAllCookies(null))
                    .show();
                return true;
            });
        }
        {
            Preference update = Objects.requireNonNull(findPreference(getString(R.string.preference_key_update)));
            update.setOnPreferenceClickListener(preference -> {
                act.startActivity(new Intent(act, UpdateCenterActivity.class));
                return true;
            });
        }
        {
            Preference bug = Objects.requireNonNull(findPreference(getString(R.string.preference_key_bug)));
            bug.setVisible(!BuildConfig.ISSUE_TRACKER_URL.isEmpty());
            bug.setOnPreferenceClickListener(preference -> {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.ISSUE_TRACKER_URL));
                startActivity(i);
                return true;
            });
        }
        {
            Preference bug = Objects.requireNonNull(findPreference(getString(R.string.preference_key_copy_logs)));
            bug.setOnPreferenceClickListener(preference -> {
                act.exportLogs();
                return true;
            });
        }
        {
            Preference credits = Objects.requireNonNull(findPreference(getString(R.string.preference_key_credits)));
            credits.setOnPreferenceClickListener(preference -> {
                try {
                    new MaterialAlertDialogBuilder(act)
                        .setTitle(R.string.app_name)
                        .setMessage(readLegalAsset("NOTICE") + "\n\n" + readLegalAsset("LICENSE"))
                        .setPositiveButton(R.string.ok, null)
                        .show();
                } catch (IOException e) {
                    LogUtility.e("Error reading legal notices", e);
                    Toast.makeText(act, R.string.error_retrieving, Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }
        {
            Preference copySettings = Objects.requireNonNull(findPreference(getString(R.string.preference_key_copy_settings)));
            copySettings.setOnPreferenceClickListener(preference -> {
                try {
                    CopyToClipboardActivity.copyTextToClipboard(act, getDataSettings(act));
                } catch (IOException e) {
                    LogUtility.e("Error copying settings into clipboard", e);
                    Toast.makeText(act, R.string.clipboard_settings_error, Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }
        {
            Preference export = Objects.requireNonNull(findPreference(getString(R.string.preference_key_export)));
            export.setOnPreferenceClickListener(preference -> {
                act.exportSettings();
                return true;
            });
        }
        {
            Preference _import = Objects.requireNonNull(findPreference(getString(R.string.preference_key_import)));
            _import.setOnPreferenceClickListener(preference -> {
                act.importSettings();
                return true;
            });
        }
        {
            Preference restore = Objects.requireNonNull(findPreference(getString(R.string.preference_key_restore_snapshot)));
            restore.setEnabled(false);
            restore.setSummary(R.string.restore_snapshot_checking);
            restore.setOnPreferenceClickListener(preference -> {
                act.restoreLastSnapshot();
                return true;
            });
            updateSnapshotAvailability(restore);
        }

        {
            ListPreference mirror = Objects.requireNonNull(findPreference(getString(R.string.preference_key_site_mirror)));
            mirror.setSummary(
                act.getSharedPreferences("Settings", Context.MODE_PRIVATE)
                    .getString(getString(R.string.preference_key_site_mirror), Utility.ORIGINAL_URL)
            );
            mirror.setOnPreferenceChangeListener((preference, newValue) -> {
                preference.setSummary(newValue.toString());
                return true;
            });
        }
    }

    private void updateImageCacheSummary(@NonNull Preference cache, long bytes, @Nullable Exception error) {
        if (error != null) {
            cache.setSummary(R.string.image_cache_size_unavailable);
            return;
        }
        cache.setSummary(getString(R.string.image_cache_size, Formatter.formatFileSize(requireContext(), bytes)));
    }

    private String readLegalAsset(String name) throws IOException {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            requireContext().getAssets().open(name), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append('\n');
            }
        }
        return text.toString();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (act == null) return;
        updateManagedCacheUsage();
        OnlineFavoriteClient.observe(act, favoriteCacheObserver);
        updateFavoriteCacheUsage();
        if (findPreference(getString(R.string.preference_key_online_favorite_page_usage)) != null) {
            scheduleFavoritePageCacheTrim();
            updateFavoritePageCacheUsage();
        }
        if (findPreference(getString(R.string.preference_key_online_favorite_page_metrics)) != null)
            updateFavoritePageCacheMetrics();
        if (findPreference(getString(R.string.preference_key_search_page_usage)) != null) {
            scheduleSearchPageCacheTrim();
            updateSearchPageCacheUsage();
        }
        if (findPreference(getString(R.string.preference_key_search_page_metrics)) != null)
            updateSearchPageCacheMetrics();
        Preference accountTags = findPreference(getString(R.string.preference_key_use_account_tag));
        if (accountTags != null) updateAccountTagAvailability(accountTags);
        Preference restore = findPreference(getString(R.string.preference_key_restore_snapshot));
        if (restore != null) updateSnapshotAvailability(restore);
    }

    @Override
    public void onPause() {
        favoritePageCacheGeneration.incrementAndGet();
        favoritePageMetricsGeneration.incrementAndGet();
        searchPageCacheGeneration.incrementAndGet();
        searchPageMetricsGeneration.incrementAndGet();
        managedCacheGeneration.incrementAndGet();
        OnlineFavoriteClient.removeObserver(favoriteCacheObserver);
        super.onPause();
    }

    private void updateManagedCacheUsage() {
        SettingsActivity activity = act;
        if (!isAdded() || !isResumed() || activity == null) return;
        Preference overview = findPreference(getString(R.string.preference_key_managed_cache_usage));
        if (overview == null) return;
        int generation = managedCacheGeneration.incrementAndGet();
        overview.setEnabled(false);
        overview.setSummary(R.string.managed_cache_usage_checking);
        ManagedCacheStorage.measure(activity, usage -> {
            if (!isAdded() || getActivity() != activity || activity.isFinishing()
                || managedCacheGeneration.get() != generation) return;
            managedCacheUsage = usage;
            overview.setEnabled(true);
            long total = usage.totalBytes();
            if (total == ManagedCacheStorage.UNAVAILABLE) {
                overview.setSummary(R.string.managed_cache_usage_unavailable);
            } else {
                overview.setSummary(getString(R.string.summary_managed_cache_usage,
                    Formatter.formatFileSize(activity, total)));
            }
        });
    }

    @NonNull
    private String formatManagedCacheBytes(long bytes) {
        return bytes == ManagedCacheStorage.UNAVAILABLE
            ? getString(R.string.managed_cache_size_unavailable)
            : Formatter.formatFileSize(requireContext(), bytes);
    }

    private void updateFavoriteCacheUsage() {
        if (!isAdded() || act == null) return;
        Preference usage = findPreference(getString(R.string.preference_key_online_favorite_state_usage));
        if (usage != null) {
            usage.setSummary(getString(R.string.summary_online_favorite_state_usage,
                OnlineFavoriteClient.cachedStateCount(act)));
        }
    }

    private void scheduleSearchPageCacheTrim() {
        SettingsActivity activity = act;
        if (activity == null) return;
        MAIN_HANDLER.post(() -> FAVORITE_PAGE_CACHE_WORKER.execute(() -> {
            SearchPageStore store = null;
            try {
                store = SearchPageStore.open(activity.getApplicationContext());
                store.trimToLimits();
            } catch (RuntimeException error) {
                LogUtility.w("Unable to apply search page cache settings", error);
            } finally {
                if (store != null) store.close();
            }
            MAIN_HANDLER.post(() -> {
                updateSearchPageCacheUsage();
                updateManagedCacheUsage();
            });
        }));
    }

    private void updateSearchPageCacheUsage() {
        SettingsActivity activity = act;
        if (!isAdded() || activity == null) return;
        Preference usage = findPreference(getString(R.string.preference_key_search_page_usage));
        Preference clear = findPreference(getString(R.string.preference_key_search_pages_clear));
        if (usage == null || clear == null) return;

        int generation = searchPageCacheGeneration.incrementAndGet();
        clear.setEnabled(false);
        Context application = activity.getApplicationContext();
        FAVORITE_PAGE_CACHE_WORKER.execute(() -> {
            SearchPageStore.PageStats stats = null;
            RuntimeException failure = null;
            SearchPageStore store = null;
            try {
                store = SearchPageStore.open(application);
                stats = store.stats();
            } catch (RuntimeException error) {
                failure = error;
            } finally {
                if (store != null) {
                    try {
                        store.close();
                    } catch (RuntimeException error) {
                        if (failure == null) failure = error;
                    }
                }
            }
            SearchPageStore.PageStats result = stats;
            RuntimeException error = failure;
            MAIN_HANDLER.post(() -> {
                if (!isAdded() || getActivity() != activity || activity.isFinishing()
                    || searchPageCacheGeneration.get() != generation) return;
                if (error != null || result == null) {
                    usage.setSummary(R.string.search_page_usage_unavailable);
                    clear.setEnabled(false);
                    return;
                }
                String lastSynced = result.lastSyncedAt <= 0
                    ? getString(R.string.search_page_never_synced)
                    : DateUtils.getRelativeTimeSpanString(result.lastSyncedAt,
                        System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString();
                usage.setSummary(getString(R.string.summary_search_page_usage,
                    result.pageCount, Formatter.formatFileSize(activity, result.bytes), lastSynced));
                clear.setEnabled(result.pageCount > 0);
            });
        });
    }

    private void updateSearchPageCacheMetrics() {
        SettingsActivity activity = act;
        if (!isAdded() || activity == null) return;
        Preference metrics = findPreference(getString(R.string.preference_key_search_page_metrics));
        if (metrics == null) return;

        int generation = searchPageMetricsGeneration.incrementAndGet();
        metrics.setSummary(R.string.search_page_metrics_checking);
        Context application = activity.getApplicationContext();
        FAVORITE_PAGE_CACHE_WORKER.execute(() -> {
            SearchPageStore.CacheMetrics stats = null;
            RuntimeException failure = null;
            SearchPageStore store = null;
            try {
                store = SearchPageStore.open(application);
                stats = store.cacheMetrics();
            } catch (RuntimeException error) {
                failure = error;
            } finally {
                if (store != null) {
                    try {
                        store.close();
                    } catch (RuntimeException error) {
                        if (failure == null) failure = error;
                    }
                }
            }
            SearchPageStore.CacheMetrics result = stats;
            RuntimeException error = failure;
            MAIN_HANDLER.post(() -> {
                if (!isAdded() || getActivity() != activity || activity.isFinishing()
                    || searchPageMetricsGeneration.get() != generation) return;
                if (error != null || result == null) {
                    metrics.setSummary(R.string.search_page_metrics_unavailable);
                    return;
                }
                long lookups = result.cacheHits > Long.MAX_VALUE - result.cacheMisses
                    ? Long.MAX_VALUE : result.cacheHits + result.cacheMisses;
                if (lookups == 0) {
                    metrics.setSummary(getString(R.string.summary_search_page_metrics_empty,
                        result.refreshSuccesses, result.refreshFailures, result.pageEvictions));
                    return;
                }
                String hitRate = NumberFormat.getPercentInstance(
                    activity.getResources().getConfiguration().getLocales().get(0))
                    .format((double) result.cacheHits / lookups);
                metrics.setSummary(getString(R.string.summary_search_page_metrics,
                    result.cacheHits, result.cacheMisses, hitRate, result.refreshSuccesses,
                    result.refreshFailures, result.pageEvictions));
            });
        });
    }

    private void clearSearchPages(Preference clearPreference) {
        SettingsActivity activity = act;
        if (activity == null) return;
        clearPreference.setEnabled(false);
        int generation = searchPageCacheGeneration.incrementAndGet();
        SearchPageStore.markClearPending();
        Context application = activity.getApplicationContext();
        FAVORITE_PAGE_CACHE_WORKER.execute(() -> {
            RuntimeException failure = null;
            SearchPageStore store = null;
            try {
                store = SearchPageStore.open(application);
                store.clearAll();
            } catch (RuntimeException error) {
                failure = error;
            } finally {
                if (store != null) {
                    try {
                        store.close();
                    } catch (RuntimeException error) {
                        if (failure == null) failure = error;
                    }
                } else {
                    SearchPageStore.cancelPendingClear();
                }
            }
            RuntimeException error = failure;
            MAIN_HANDLER.post(() -> {
                if (!isAdded() || getActivity() != activity || activity.isFinishing()
                    || searchPageCacheGeneration.get() != generation) return;
                Toast.makeText(activity, error == null
                    ? R.string.search_pages_cleared : R.string.search_pages_clear_failed,
                    Toast.LENGTH_SHORT).show();
                updateSearchPageCacheUsage();
                updateSearchPageCacheMetrics();
                updateManagedCacheUsage();
            });
        });
    }

    private void scheduleFavoritePageCacheTrim() {
        SettingsActivity activity = act;
        if (activity == null) return;
        MAIN_HANDLER.post(() -> FAVORITE_PAGE_CACHE_WORKER.execute(() -> {
            OnlineFavoritePageStore store = null;
            try {
                store = OnlineFavoritePageStore.open(activity.getApplicationContext());
                store.trimToLimits();
            } catch (RuntimeException error) {
                LogUtility.w("Unable to apply favorite page cache settings", error);
            } finally {
                if (store != null) store.close();
            }
            MAIN_HANDLER.post(() -> {
                updateFavoritePageCacheUsage();
                updateManagedCacheUsage();
            });
        }));
    }

    private void updateFavoritePageCacheUsage() {
        SettingsActivity activity = act;
        if (!isAdded() || activity == null) return;
        Preference usage = findPreference(getString(R.string.preference_key_online_favorite_page_usage));
        Preference clear = findPreference(getString(R.string.preference_key_online_favorite_pages_clear));
        if (usage == null || clear == null) return;

        String identity = OnlineFavoriteClient.sessionIdentity(activity);
        int generation = favoritePageCacheGeneration.incrementAndGet();
        clear.setEnabled(false);
        if (identity == null) {
            usage.setSummary(R.string.online_favorite_page_no_account);
            return;
        }

        Context application = activity.getApplicationContext();
        FAVORITE_PAGE_CACHE_WORKER.execute(() -> {
            OnlineFavoritePageStore.PageStats stats = null;
            RuntimeException failure = null;
            OnlineFavoritePageStore store = null;
            try {
                store = OnlineFavoritePageStore.open(application);
                stats = store.stats(identity);
            } catch (RuntimeException error) {
                failure = error;
            } finally {
                if (store != null) {
                    try {
                        store.close();
                    } catch (RuntimeException error) {
                        if (failure == null) failure = error;
                    }
                }
            }
            OnlineFavoritePageStore.PageStats result = stats;
            RuntimeException error = failure;
            MAIN_HANDLER.post(() -> {
                if (!isAdded() || getActivity() != activity || activity.isFinishing()
                    || favoritePageCacheGeneration.get() != generation) return;
                if (!identity.equals(OnlineFavoriteClient.sessionIdentity(activity))) {
                    updateFavoritePageCacheUsage();
                    return;
                }
                if (error != null || result == null) {
                    usage.setSummary(R.string.online_favorite_page_usage_unavailable);
                    clear.setEnabled(false);
                    return;
                }
                String lastSynced = result.lastSyncedAt <= 0
                    ? getString(R.string.online_favorite_page_never_synced)
                    : DateUtils.getRelativeTimeSpanString(result.lastSyncedAt,
                        System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString();
                usage.setSummary(getString(R.string.summary_online_favorite_page_usage,
                    result.pageCount, Formatter.formatFileSize(activity, result.bytes), lastSynced));
                clear.setEnabled(result.pageCount > 0);
            });
        });
    }

    private void updateFavoritePageCacheMetrics() {
        SettingsActivity activity = act;
        if (!isAdded() || activity == null) return;
        Preference metrics = findPreference(getString(R.string.preference_key_online_favorite_page_metrics));
        if (metrics == null) return;

        int generation = favoritePageMetricsGeneration.incrementAndGet();
        metrics.setSummary(R.string.online_favorite_page_metrics_checking);
        Context application = activity.getApplicationContext();
        FAVORITE_PAGE_CACHE_WORKER.execute(() -> {
            OnlineFavoritePageStore.CacheMetrics stats = null;
            RuntimeException failure = null;
            OnlineFavoritePageStore store = null;
            try {
                store = OnlineFavoritePageStore.open(application);
                stats = store.cacheMetrics();
            } catch (RuntimeException error) {
                failure = error;
            } finally {
                if (store != null) {
                    try {
                        store.close();
                    } catch (RuntimeException error) {
                        if (failure == null) failure = error;
                    }
                }
            }
            OnlineFavoritePageStore.CacheMetrics result = stats;
            RuntimeException error = failure;
            MAIN_HANDLER.post(() -> {
                if (!isAdded() || getActivity() != activity || activity.isFinishing()
                    || favoritePageMetricsGeneration.get() != generation) return;
                if (error != null || result == null) {
                    metrics.setSummary(R.string.online_favorite_page_metrics_unavailable);
                    return;
                }
                long lookups = result.cacheHits > Long.MAX_VALUE - result.cacheMisses
                    ? Long.MAX_VALUE : result.cacheHits + result.cacheMisses;
                if (lookups == 0) {
                    metrics.setSummary(getString(R.string.summary_online_favorite_page_metrics_empty,
                        result.refreshSuccesses, result.refreshFailures, result.pageEvictions));
                    return;
                }
                String hitRate = NumberFormat.getPercentInstance(
                    activity.getResources().getConfiguration().getLocales().get(0))
                    .format((double) result.cacheHits / lookups);
                metrics.setSummary(getString(R.string.summary_online_favorite_page_metrics,
                    result.cacheHits, result.cacheMisses, hitRate, result.refreshSuccesses,
                    result.refreshFailures, result.pageEvictions));
            });
        });
    }

    private void clearFavoritePages(String identity, Preference clearPreference) {
        SettingsActivity activity = act;
        if (activity == null) return;
        clearPreference.setEnabled(false);
        int generation = favoritePageCacheGeneration.incrementAndGet();
        Context application = activity.getApplicationContext();
        FAVORITE_PAGE_CACHE_WORKER.execute(() -> {
            RuntimeException failure = null;
            OnlineFavoritePageStore store = null;
            try {
                store = OnlineFavoritePageStore.open(application);
                store.clearAccount(identity);
                store.compactAfterMaintenance();
            } catch (RuntimeException error) {
                failure = error;
            } finally {
                if (store != null) {
                    try {
                        store.close();
                    } catch (RuntimeException error) {
                        if (failure == null) failure = error;
                    }
                }
            }
            RuntimeException error = failure;
            MAIN_HANDLER.post(() -> {
                if (!isAdded() || getActivity() != activity || activity.isFinishing()
                    || favoritePageCacheGeneration.get() != generation) return;
                if (!identity.equals(OnlineFavoriteClient.sessionIdentity(activity))) {
                    updateFavoritePageCacheUsage();
                    return;
                }
                Toast.makeText(activity, error == null
                    ? R.string.online_favorite_pages_cleared
                    : R.string.online_favorite_pages_clear_failed, Toast.LENGTH_SHORT).show();
                updateFavoritePageCacheUsage();
                updateManagedCacheUsage();
            });
        });
    }

    private void updateAccountTagAvailability(Preference preference) {
        boolean loggedIn = Login.isLogged();
        preference.setEnabled(loggedIn);
        SwitchPreferenceCompat accountTags = (SwitchPreferenceCompat) preference;
        if (loggedIn) {
            accountTags.setSummaryOn(R.string.setting_on_use_account_tag);
            accountTags.setSummaryOff(R.string.setting_off_use_account_tag);
        } else {
            accountTags.setSummaryOn(R.string.settings_account_tags_unavailable);
            accountTags.setSummaryOff(R.string.settings_account_tags_unavailable);
        }
    }

    @Override
    public void onDestroy() {
        snapshotCheckGeneration.incrementAndGet();
        super.onDestroy();
    }

    private void updateSnapshotAvailability(@NonNull Preference restore) {
        SettingsActivity activity = act;
        if (activity == null) return;
        int generation = snapshotCheckGeneration.incrementAndGet();
        restore.setEnabled(false);
        restore.setSummary(R.string.restore_snapshot_checking);
        new Thread(() -> {
            boolean usable = Importer.hasUsableSnapshot(activity.getApplicationContext());
            long age = usable ? Importer.snapshotAgeDays(activity.getApplicationContext()) : -1;
            activity.runOnUiThread(() -> {
                if (!isAdded() || getActivity() != activity || snapshotCheckGeneration.get() != generation) return;
                restore.setEnabled(usable);
                if (usable) restore.setSummary(getResources().getQuantityString(
                    R.plurals.restore_snapshot_summary, (int) age, age));
                else restore.setSummary(R.string.restore_snapshot_unavailable);
            });
        }).start();
    }

    public void manageCustomPath() {
        if (!Global.isExternalStorageManager()) {
            act.requestStorageManager();
            return;
        }
        final String key = getString(R.string.preference_key_save_path);
        Preference savePathPreference = Objects.requireNonNull(findPreference(key));
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(act);
        View content = View.inflate(act, R.layout.autocomplete_entry, null);
        AppCompatAutoCompleteTextView edit = content.findViewById(R.id.edit);
        TextInputLayout input = content.findViewById(R.id.autocomplete_input);
        input.setHint(R.string.insert_path);
        builder.setView(content);
        builder.setTitle(R.string.insert_path);
        builder.setPositiveButton(R.string.ok, (dialog, which) -> {
            act.getSharedPreferences("Settings", Context.MODE_PRIVATE).edit().putString(key, edit.getText().toString()).apply();
            savePathPreference.setSummary(edit.getText().toString());
        }).setNegativeButton(R.string.cancel, null).show();
    }

    private void changeLauncher(PackageManager pm, ComponentName name, boolean enabled) {
        int enableState = enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        pm.setComponentEnabledSetting(name, enableState, PackageManager.DONT_KILL_APP);
    }


    private void initStoragePaths(ListPreference storagePreference) {
        if (!Global.hasStoragePermission(act)) {
            storagePreference.setVisible(false);
            return;
        }
        List<File> files = Global.getUsableFolders(act);
        List<CharSequence> strings = new ArrayList<>(files.size() + 1);
        for (File f : files) {
            if (f != null)
                strings.add(f.getAbsolutePath());
        }
        strings.add(getString(R.string.custom_path));
        storagePreference.setEntries(strings.toArray(new CharSequence[0]));
        storagePreference.setEntryValues(strings.toArray(new CharSequence[0]));
        storagePreference.setSummary(
            act.getSharedPreferences("Settings", Context.MODE_PRIVATE)
                .getString(getString(R.string.preference_key_save_path), Global.MAINFOLDER.getParent())
        );
        storagePreference.setOnPreferenceChangeListener((preference, newValue) -> {
            preference.setSummary(newValue.toString());
            return true;
        });
    }

    private String getDataSettings(Context context) throws IOException {
        String[] names = new String[]{"Settings", "ScrapedTags"};
        try (StringWriter sw = new StringWriter();
             JsonWriter writer = new JsonWriter(sw)) {
            writer.setIndent("\t");

            writer.beginObject();
            for (String name : names)
                processSharedFromName(writer, context, name);
            writer.endObject();

            writer.flush();
            String settings = sw.toString();
            LogUtility.d(settings);
            return settings;
        }
    }

    private void processSharedFromName(JsonWriter writer, Context context, String name) throws IOException {
        writer.name(name);
        writer.beginObject();
        SharedPreferences preferences = context.getSharedPreferences(name, 0);
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            writeEntry(writer, entry);
        }
        writer.endObject();
    }

    private void writeEntry(JsonWriter writer, Map.Entry<String, ?> entry) throws IOException {
        writer.name(entry.getKey());
        if (entry.getValue() instanceof Integer) writer.value((Integer) entry.getValue());
        else if (entry.getValue() instanceof Boolean) writer.value((Boolean) entry.getValue());
        else if (entry.getValue() instanceof String) writer.value((String) entry.getValue());
        else if (entry.getValue() instanceof Long) writer.value((Long) entry.getValue());
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        getPreferenceManager().setSharedPreferencesName("Settings");
    }

    private void columnMenu() {
        new MainGridSettings(requireContext());
        MainGridSettings.forDownloads(requireContext());
        addPreferencesFromResource(R.xml.settings_column);
    }

}
