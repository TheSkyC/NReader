package com.theskyc.nreader;

import android.app.Application;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.LayoutInflater;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.card.MaterialCardView;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class UpdateCenterThemeTest {
    private SharedPreferences preferences() {
        return RuntimeEnvironment.getApplication().getSharedPreferences("Settings", 0);
    }

    @After
    public void clearBlackTheme() {
        preferences().edit().remove(RuntimeEnvironment.getApplication()
            .getString(R.string.preference_key_black_theme)).commit();
    }

    @Test
    @Config(qualifiers = "notnight")
    public void lightThemeUsesReadableMaterial3Colors() {
        assertTheme(false);
    }

    @Test
    @Config(qualifiers = "night")
    public void darkThemeUsesReadableMaterial3Colors() {
        assertTheme(false);
    }

    @Test
    @Config(qualifiers = "notnight")
    public void blackThemeRemainsMaterial3InLightMode() {
        assertTheme(true);
    }

    @Test
    @Config(qualifiers = "notnight")
    public void mainScreenUsesMaterial3InLightMode() {
        assertMainTheme(false);
    }

    @Test
    @Config(qualifiers = "night")
    public void mainScreenUsesMaterial3InDarkMode() {
        assertMainTheme(false);
    }

    @Test
    @Config(qualifiers = "notnight")
    public void mainScreenKeepsBlackMaterial3ThemeInLightMode() {
        assertMainTheme(true);
    }

    @Test
    @Config(qualifiers = "notnight")
    public void accountsUsesMaterial3InLightMode() {
        assertAccountsTheme(false);
    }

    @Test
    @Config(qualifiers = "night")
    public void accountsUsesMaterial3InDarkMode() {
        assertAccountsTheme(false);
    }

    @Test
    @Config(qualifiers = "notnight")
    public void accountsKeepsBlackMaterial3ThemeInLightMode() {
        assertAccountsTheme(true);
    }

    @Test
    @Config(qualifiers = "notnight")
    public void searchUsesMaterial3InLightMode() {
        assertSearchTheme(false);
    }

    @Test
    @Config(qualifiers = "night")
    public void searchUsesMaterial3InDarkMode() {
        assertSearchTheme(false);
    }

    @Test
    @Config(qualifiers = "notnight")
    public void searchKeepsBlackMaterial3ThemeInLightMode() {
        assertSearchTheme(true);
    }

    @Test
    @Config(qualifiers = "notnight")
    public void tagManagerUsesMaterial3InLightMode() {
        assertTagManagerTheme(false);
    }

    @Test
    @Config(qualifiers = "night")
    public void tagManagerUsesMaterial3InDarkMode() {
        assertTagManagerTheme(false);
    }

    @Test
    @Config(qualifiers = "notnight")
    public void tagManagerKeepsBlackMaterial3ThemeInLightMode() {
        assertTagManagerTheme(true);
    }

    private void assertTagManagerTheme(boolean black) {
        Application app = RuntimeEnvironment.getApplication();
        preferences().edit().putBoolean(app.getString(R.string.preference_key_black_theme), black).commit();
        TagFilterActivity activity = Robolectric.buildActivity(TagFilterActivity.class).get();
        assertColors(activity, black);
        MaterialCardView card = (MaterialCardView) LayoutInflater.from(activity)
            .inflate(R.layout.entry_tag_layout, null, false);
        int background = card.getCardBackgroundColor().getDefaultColor();
        assertEquals(color(activity, R.attr.nreaderSurfaceVariant), background);
        assertTrue(ColorUtils.calculateContrast(color(activity, R.attr.colorOnSurface), background) >= 4.5);
        assertTrue(ColorUtils.calculateContrast(color(activity, R.attr.colorOnSurfaceVariant), background) >= 4.5);
    }

    private void assertSearchTheme(boolean black) {
        Application app = RuntimeEnvironment.getApplication();
        preferences().edit().putBoolean(app.getString(R.string.preference_key_black_theme), black).commit();
        assertColors(Robolectric.buildActivity(SearchActivity.class).get(), black);
    }

    @Test
    @Config(qualifiers = "notnight")
    public void galleryUsesMaterial3InLightMode() {
        assertGalleryTheme(false);
    }

    @Test
    @Config(qualifiers = "night")
    public void galleryUsesMaterial3InDarkMode() {
        assertGalleryTheme(false);
    }

    @Test
    @Config(qualifiers = "notnight")
    public void galleryKeepsBlackMaterial3ThemeInLightMode() {
        assertGalleryTheme(true);
    }

    private void assertGalleryTheme(boolean black) {
        Application app = RuntimeEnvironment.getApplication();
        preferences().edit().putBoolean(app.getString(R.string.preference_key_black_theme), black).commit();
        assertColors(Robolectric.buildActivity(GalleryActivity.class).get(), black);
    }

    @Test
    @Config(qualifiers = "notnight")
    public void downloadsUsesMaterial3InLightMode() {
        assertDownloadsTheme(false);
    }

    @Test
    @Config(qualifiers = "night")
    public void downloadsUsesMaterial3InDarkMode() {
        assertDownloadsTheme(false);
    }

    @Test
    @Config(qualifiers = "notnight")
    public void downloadsKeepsBlackMaterial3ThemeInLightMode() {
        assertDownloadsTheme(true);
    }

    private void assertDownloadsTheme(boolean black) {
        Application app = RuntimeEnvironment.getApplication();
        preferences().edit().putBoolean(app.getString(R.string.preference_key_black_theme), black).commit();
        assertColors(Robolectric.buildActivity(LocalActivity.class).get(), black);
    }

    @Test
    @Config(qualifiers = "notnight")
    public void favoritesUseMaterial3InLightMode() {
        assertFavoritesTheme(false);
    }

    @Test
    @Config(qualifiers = "night")
    public void favoritesUseMaterial3InDarkMode() {
        assertFavoritesTheme(false);
    }

    @Test
    @Config(qualifiers = "notnight")
    public void favoritesKeepBlackMaterial3ThemeInLightMode() {
        assertFavoritesTheme(true);
    }

    private void assertFavoritesTheme(boolean black) {
        Application app = RuntimeEnvironment.getApplication();
        preferences().edit().putBoolean(app.getString(R.string.preference_key_black_theme), black).commit();
        assertColors(Robolectric.buildActivity(FavoriteActivity.class).get(), black);
    }

    private void assertAccountsTheme(boolean black) {
        Application app = RuntimeEnvironment.getApplication();
        preferences().edit().putBoolean(app.getString(R.string.preference_key_black_theme), black).commit();
        assertColors(Robolectric.buildActivity(AccountsActivity.class).get(), black);
    }

    private void assertMainTheme(boolean black) {
        Application app = RuntimeEnvironment.getApplication();
        preferences().edit().putBoolean(app.getString(R.string.preference_key_black_theme), black).commit();
        assertColors(Robolectric.buildActivity(MainActivity.class).get(), black);
    }

    private void assertTheme(boolean black) {
        Application app = RuntimeEnvironment.getApplication();
        preferences().edit().putBoolean(app.getString(R.string.preference_key_black_theme), black).commit();
        assertColors(Robolectric.buildActivity(UpdateCenterActivity.class).get(), black);
    }

    private void assertColors(AppCompatActivity activity, boolean black) {
        TypedValue material = new TypedValue();
        assertTrue(activity.getTheme().resolveAttribute(com.google.android.material.R.attr.isMaterial3Theme, material, true));
        assertTrue(material.data != 0);
        int primary = color(activity, R.attr.colorPrimary);
        int onPrimary = color(activity, com.google.android.material.R.attr.colorOnPrimary);
        int surface = color(activity, com.google.android.material.R.attr.colorSurface);
        int onSurface = color(activity, com.google.android.material.R.attr.colorOnSurface);
        int inverseSurface = color(activity, com.google.android.material.R.attr.colorSurfaceInverse);
        int inverseOnSurface = color(activity, com.google.android.material.R.attr.colorOnSurfaceInverse);
        int inversePrimary = color(activity, com.google.android.material.R.attr.colorPrimaryInverse);
        assertTrue(ColorUtils.calculateContrast(onPrimary, primary) >= 4.5);
        assertTrue(ColorUtils.calculateContrast(onSurface, surface) >= 4.5);
        assertTrue(ColorUtils.calculateContrast(inverseOnSurface, inverseSurface) >= 4.5);
        assertTrue(ColorUtils.calculateContrast(inversePrimary, inverseSurface) >= 4.5);
        boolean lightBars = !black && (activity.getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES;
        TypedValue systemBars = new TypedValue();
        assertTrue(activity.getTheme().resolveAttribute(android.R.attr.windowLightStatusBar, systemBars, true));
        assertEquals(lightBars, systemBars.data != 0);
        assertTrue(activity.getTheme().resolveAttribute(android.R.attr.windowLightNavigationBar, systemBars, true));
        assertEquals(lightBars, systemBars.data != 0);
        if (black) assertEquals(Color.BLACK, surface);
    }

    private int color(AppCompatActivity activity, int attribute) {
        TypedValue value = new TypedValue();
        assertTrue(activity.getTheme().resolveAttribute(attribute, value, true));
        return value.data;
    }
}
