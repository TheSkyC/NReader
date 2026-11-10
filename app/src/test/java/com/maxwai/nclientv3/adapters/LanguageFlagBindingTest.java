// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.adapters;

import android.app.Application;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.enums.Language;
import com.maxwai.nclientv3.utility.LanguageFlag;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class LanguageFlagBindingTest {
    private Context context() {
        return new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme);
    }

    @Test
    public void recycledFlagsReplaceArtworkDescriptionsAndTint() {
        ImageView image = new ImageView(context());
        Language[] languages = {Language.ENGLISH, Language.CHINESE, Language.JAPANESE, Language.UNKNOWN};
        int[] artwork = {R.drawable.flag_gb, R.drawable.flag_cn, R.drawable.flag_jp, R.drawable.flag_unknown};
        int[] descriptions = {R.string.language_name_english, R.string.language_name_chinese,
            R.string.language_name_japanese, R.string.unknown_language};
        image.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        for (int index = 0; index < languages.length; index++) {
            image.setImageTintList(ColorStateList.valueOf(Color.MAGENTA));
            LanguageFlag.bind(image, languages[index]);
            assertEquals(artwork[index], shadowOf(image.getDrawable()).getCreatedFromResId());
            assertEquals(image.getContext().getString(descriptions[index]), image.getContentDescription());
            assertEquals(image.getContentDescription(), image.getTooltipText());
            assertNull(image.getImageTintList());
            assertNotNull(image.getDrawable());
            assertFalse(image.getDrawable().isAutoMirrored());
        }
        LanguageFlag.bind(image, Language.ALL);
        assertNull(image.getDrawable());
        assertNull(image.getContentDescription());
        assertNull(image.getTooltipText());
    }

    @Test
    public void bothCardLayoutsSupportOnlineAndLocalViewHolders() {
        for (int layout : new int[]{R.layout.entry_layout, R.layout.entry_layout_single}) {
            View card = LayoutInflater.from(context()).inflate(layout, null);
            GenericAdapter.ViewHolder online = new GenericAdapter.ViewHolder(card);
            LocalAdapter.ViewHolder local = new LocalAdapter.ViewHolder(card);
            assertNotNull(online.flag);
            assertEquals(online.flag, local.flag);
            LanguageFlag.bind(online.flag, Language.JAPANESE);
            assertNotNull(online.flag.getDrawable());
        }
    }

    @Test
    @Config(qualifiers = "night")
    public void randomGalleryUsesTheSameFullColorArtworkInDarkTheme() {
        View screen = LayoutInflater.from(context()).inflate(R.layout.activity_random, null);
        ImageView image = screen.findViewById(R.id.language);
        LanguageFlag.bind(image, Language.CHINESE);
        assertEquals(R.drawable.flag_cn, shadowOf(image.getDrawable()).getCreatedFromResId());
        assertNull(image.getImageTintList());
        float density = image.getResources().getDisplayMetrics().density;
        assertEquals(Math.round(60 * density), image.getLayoutParams().width);
        assertEquals(Math.round(48 * density), image.getLayoutParams().height);
    }

    @Test
    @Config(qualifiers = "land")
    public void randomGalleryLandscapeUsesAnImageFlag() {
        View screen = LayoutInflater.from(context()).inflate(R.layout.activity_random, null);
        ImageView image = screen.findViewById(R.id.language);
        LanguageFlag.bind(image, Language.JAPANESE);
        assertEquals(R.drawable.flag_jp, shadowOf(image.getDrawable()).getCreatedFromResId());
        float density = image.getResources().getDisplayMetrics().density;
        assertEquals(Math.round(60 * density), image.getLayoutParams().width);
        assertEquals(Math.round(48 * density), image.getLayoutParams().height);
    }

    @Test
    public void randomGalleryHeaderKeepsLargeTitlesBelowTheToolbar() {
        for (float fontScale : new float[]{1f, 2f}) {
            Configuration configuration = new Configuration(context().getResources().getConfiguration());
            configuration.fontScale = fontScale;
            Context enlarged = new ContextThemeWrapper(context().createConfigurationContext(configuration), R.style.AppTheme);
            View screen = LayoutInflater.from(enlarged).inflate(R.layout.activity_random, null);
            TextView title = screen.findViewById(R.id.title);
            title.setText("Synthetic gallery title long enough to wrap across all four available lines");
            int width = Math.round(360 * screen.getResources().getDisplayMetrics().density);
            int height = Math.round(800 * screen.getResources().getDisplayMetrics().density);
            screen.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            screen.layout(0, 0, width, height);
            View toolbar = screen.findViewById(R.id.toolbar);
            View flag = screen.findViewById(R.id.language);
            View thumbnail = screen.findViewById(R.id.thumbnail);
            assertTrue(title.getTop() >= toolbar.getBottom());
            assertTrue(flag.getTop() >= title.getTop());
            assertTrue(flag.getBottom() <= title.getBottom());
            assertTrue(thumbnail.getTop() >= title.getBottom());
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    public void languageDescriptionsFollowTheSelectedLocale() {
        ImageView image = new ImageView(context());
        LanguageFlag.bind(image, Language.ENGLISH);
        assertEquals("英语", image.getContentDescription());
        LanguageFlag.bind(image, Language.UNKNOWN);
        assertEquals(image.getContext().getText(R.string.unknown_language), image.getContentDescription());
    }
}
