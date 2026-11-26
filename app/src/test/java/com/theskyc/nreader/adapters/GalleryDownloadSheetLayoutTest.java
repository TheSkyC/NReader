package com.theskyc.nreader.adapters;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.View;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.theskyc.nreader.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;

import java.util.Locale;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "w640dp-h320dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class GalleryDownloadSheetLayoutTest {
    @Test public void longDetailsLeaveActionsReachableAtLargeText() {
        AppCompatActivity activity = Robolectric.buildActivity(AppCompatActivity.class).get();
        for (Locale locale : new Locale[]{Locale.ENGLISH, new Locale("ar", "SA")}) {
            Configuration config = new Configuration(activity.getResources().getConfiguration());
            config.setLocale(locale);
            config.setLayoutDirection(locale);
            config.fontScale = 1.5f;
            Context context = new ContextThemeWrapper(activity.createConfigurationContext(config),
                R.style.AppTheme_Material3);
            BottomSheetDialog dialog = ReflectionHelpers.callStaticMethod(GalleryCardActions.class, "sheet",
                ReflectionHelpers.ClassParameter.from(Context.class, context),
                ReflectionHelpers.ClassParameter.from(String.class, "A long gallery title that wraps onto several lines"),
                ReflectionHelpers.ClassParameter.from(String.class,
                    "Missing pages: 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16"));
            boolean[] clicked = {false};
            ReflectionHelpers.callStaticMethod(GalleryCardActions.class, "action",
                ReflectionHelpers.ClassParameter.from(BottomSheetDialog.class, dialog),
                ReflectionHelpers.ClassParameter.from(int.class, R.string.download_repair_start),
                ReflectionHelpers.ClassParameter.from(Runnable.class, (Runnable) () -> clicked[0] = true));
            dialog.show();

            NestedScrollView scroll = dialog.findViewById(R.id.sheet_scroll);
            LinearLayout actions = dialog.findViewById(R.id.sheet_actions);
            View button = actions.getChildAt(0);
            int width = Math.round(280 * context.getResources().getDisplayMetrics().density);
            int height = Math.round(160 * context.getResources().getDisplayMetrics().density);
            scroll.setSmoothScrollingEnabled(false);
            scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            scroll.layout(0, 0, width, height);
            assertTrue(scroll.canScrollVertically(1));
            scroll.fullScroll(View.FOCUS_DOWN);
            Rect bounds = new Rect();
            button.getDrawingRect(bounds);
            scroll.offsetDescendantRectToMyCoords(button, bounds);
            assertTrue(bounds.bottom <= scroll.getScrollY() + scroll.getHeight());
            assertTrue(button.performClick());
            assertTrue(clicked[0]);
            assertFalse(dialog.isShowing());
        }
    }
}
