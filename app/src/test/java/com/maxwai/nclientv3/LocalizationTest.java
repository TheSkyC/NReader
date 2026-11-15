// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3;

import android.app.Application;
import android.content.res.Configuration;
import android.content.res.Resources;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
// API 28 is the lowest runtime supported by the minApi28 test APK; the same
// resource tests run against the minApi26 flavor separately.
@Config(sdk = 28, application = Application.class)
public class LocalizationTest {
    private Resources resources(String languageTag) {
        Application application = RuntimeEnvironment.getApplication();
        Configuration configuration = new Configuration(application.getResources().getConfiguration());
        configuration.setLocale(Locale.forLanguageTag(languageTag));
        return application.createConfigurationContext(configuration).getResources();
    }

    @Test
    public void englishPageAndFavoriteCountsUseSingularAndPlural() {
        Resources resources = resources("en-US");
        assertEquals("1 page", resources.getQuantityString(R.plurals.page_count_format, 1, 1));
        assertEquals("2 pages", resources.getQuantityString(R.plurals.page_count_format, 2, 2));
        assertEquals("1 favorite", resources.getQuantityString(R.plurals.favorite_count_format, 1, 1));
        assertEquals("0 favorites", resources.getQuantityString(R.plurals.favorite_count_format, 0, 0));
    }

    @Test
    public void russianPageCountsSelectOneFewAndMany() {
        Resources resources = resources("ru-RU");
        assertEquals("1 страница", resources.getQuantityString(R.plurals.page_count_format, 1, 1));
        assertEquals("2 страницы", resources.getQuantityString(R.plurals.page_count_format, 2, 2));
        assertEquals("5 страниц", resources.getQuantityString(R.plurals.page_count_format, 5, 5));
        assertEquals("11 страниц", resources.getQuantityString(R.plurals.page_count_format, 11, 11));
        assertEquals("21 страница", resources.getQuantityString(R.plurals.page_count_format, 21, 21));
    }

    @Test
    public void arabicPageCountsSelectZeroOneTwoFewManyAndOther() {
        Resources resources = resources("ar-SA");
        Locale locale = Locale.forLanguageTag("ar-SA");
        assertEquals("لا صفحات", resources.getQuantityString(R.plurals.page_count_format, 0, 0));
        assertEquals("صفحة واحدة", resources.getQuantityString(R.plurals.page_count_format, 1, 1));
        assertEquals("صفحتان", resources.getQuantityString(R.plurals.page_count_format, 2, 2));
        assertEquals(String.format(locale, "%d صفحات", 3),
            resources.getQuantityString(R.plurals.page_count_format, 3, 3));
        assertEquals(String.format(locale, "%d صفحة", 11),
            resources.getQuantityString(R.plurals.page_count_format, 11, 11));
        assertEquals(String.format(locale, "%d صفحة", 100),
            resources.getQuantityString(R.plurals.page_count_format, 100, 100));
    }

    @Test
    public void chineseAndJapaneseUseTheirLocalizedCountFormats() {
        assertEquals("1 页", resources("zh-CN").getQuantityString(R.plurals.page_count_format, 1, 1));
        assertEquals("2 頁", resources("zh-TW").getQuantityString(R.plurals.page_count_format, 2, 2));
        assertEquals("1ページ", resources("ja-JP").getQuantityString(R.plurals.page_count_format, 1, 1));
    }

    @Test
    public void allSupportedLocalesFormatCountsAndRestorePreview() {
        String[] locales = {"en-US", "ar-SA", "de-DE", "es-ES", "fr-FR", "it-IT", "ja-JP",
            "pt-BR", "ru-RU", "tr-TR", "uk-UA", "zh-CN", "zh-TW"};
        int[] quantities = {R.plurals.page_count_format, R.plurals.favorite_count_format,
            R.plurals.tags_max_reached, R.plurals.minimum_comment_length, R.plurals.restore_snapshot_summary};
        for (String languageTag : locales) {
            Resources resources = resources(languageTag);
            for (int resource : quantities) {
                for (int count : new int[]{0, 1, 2, 3, 5, 11, 21, 101}) {
                    String rendered = resources.getQuantityString(resource, count, count);
                    assertFalse(languageTag, rendered.isEmpty());
                    assertFalse(languageTag + ": " + rendered, rendered.contains("%"));
                }
            }
            String preview = resources.getString(R.string.backup_restore_preview_message, 23,
                resources.getString(R.string.backup_integrity_verified));
            assertTrue(languageTag, preview.contains(resources.getString(R.string.backup_integrity_verified)));
            assertFalse(languageTag, preview.contains("%"));
        }
    }
}
