// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import android.app.Activity;
import android.app.Application;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.Insets;
import androidx.core.view.WindowInsetsCompat;

import com.theskyc.nreader.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32, application = Application.class)
public class ReaderWindowInsetsTest {
    @Test
    public void systemBarVisibilityKeepsBothReaderBoundsStable() {
        Activity activity = createActivity();
        View root = activity.getWindow().getDecorView();
        View pager = activity.findViewById(R.id.container);
        View continuous = activity.findViewById(R.id.continuous_reader);
        continuous.setVisibility(View.VISIBLE);
        root.setPadding(0, 24, 0, 48);
        ReaderWindowInsets.apply(root, insets(Insets.of(0, 24, 0, 48), true));
        layout(root, 1080, 1920);
        Rect pagerBounds = bounds(pager);
        Rect continuousBounds = bounds(continuous);
        View toolbar = activity.findViewById(R.id.toolbar);
        View controls = activity.findViewById(R.id.master_layout);
        for (boolean visible : new boolean[]{false, true, false}) {
            toolbar.setVisibility(visible ? View.VISIBLE : View.GONE);
            controls.setVisibility(visible ? View.VISIBLE : View.GONE);
            ReaderWindowInsets.apply(root, insets(Insets.of(0, 24, 0, 48), visible));
            layout(root, 1080, 1920);
            assertEquals(pagerBounds, bounds(pager));
            assertEquals(continuousBounds, bounds(continuous));
        }
        assertEquals(new Rect(0, 0, 1080, 1920), pagerBounds);
        assertEquals(pagerBounds, continuousBounds);
    }

    @Test
    public void controlsRespectLandscapeBarsAndCutoutWithoutShrinkingTheImage() {
        Activity activity = createActivity();
        View root = activity.getWindow().getDecorView();
        WindowInsetsCompat landscape = new WindowInsetsCompat.Builder()
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 0, 48, 0))
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout(), Insets.of(72, 0, 0, 0))
            .build();
        ReaderWindowInsets.apply(root, landscape);
        layout(root, 1920, 1080);
        View toolbar = activity.findViewById(R.id.toolbar);
        View controls = activity.findViewById(R.id.master_layout);
        assertEquals(72, toolbar.getLeft());
        assertEquals(1920 - 48, toolbar.getRight());
        assertEquals(72, controls.getLeft());
        assertEquals(1920 - 48, controls.getRight());
        assertEquals(new Rect(0, 0, 1920, 1080), bounds(activity.findViewById(R.id.container)));

        ReaderWindowInsets.apply(root, insets(Insets.of(0, 24, 0, 48), false));
        layout(root, 1080, 1920);
        assertEquals(0, toolbar.getLeft());
        assertEquals(1080, toolbar.getRight());
        assertEquals(24, toolbar.getTop());
        assertEquals(1920 - 48, controls.getBottom());
    }

    @Test
    public void hiddenControlsRotateAndReturnWithoutLeavingViewportMargins() {
        Activity activity = createActivity();
        View root = activity.getWindow().getDecorView();
        View toolbar = activity.findViewById(R.id.toolbar);
        View controls = activity.findViewById(R.id.master_layout);
        View continuous = activity.findViewById(R.id.continuous_reader);
        continuous.setVisibility(View.VISIBLE);
        ReaderWindowInsets.apply(root, insets(Insets.of(0, 24, 0, 48), true));
        layout(root, 1080, 1920);
        toolbar.setVisibility(View.GONE);
        controls.setVisibility(View.GONE);
        ReaderWindowInsets.apply(root, insets(Insets.of(0, 24, 0, 48), false));
        layout(root, 1080, 1920);
        assertEquals(new Rect(0, 0, 1080, 1920), bounds(continuous));

        ReaderWindowInsets.apply(root, insets(Insets.of(32, 0, 48, 0), false));
        layout(root, 1920, 1080);
        assertEquals(new Rect(0, 0, 1920, 1080), bounds(continuous));
        toolbar.setVisibility(View.VISIBLE);
        controls.setVisibility(View.VISIBLE);
        ReaderWindowInsets.apply(root, insets(Insets.of(32, 0, 48, 0), true));
        layout(root, 1920, 1080);
        assertEquals(32, toolbar.getLeft());
        assertEquals(1920 - 48, toolbar.getRight());
        assertEquals(new Rect(0, 0, 1920, 1080), bounds(continuous));
        assertEquals(new Rect(0, 0, 1920, 1080), bounds(activity.findViewById(R.id.container)));
    }

    @Test
    public void controlsAndPageIndicatorAreDrawnAboveBothReaderModes() {
        Activity activity = createActivity();
        View pager = activity.findViewById(R.id.container);
        View continuous = activity.findViewById(R.id.continuous_reader);
        ViewGroup parent = (ViewGroup) pager.getParent();
        for (int id : new int[]{R.id.toolbar, R.id.master_layout, R.id.page_text}) {
            View overlay = activity.findViewById(id);
            assertTrue(parent.indexOfChild(overlay) > parent.indexOfChild(pager));
            assertTrue(parent.indexOfChild(overlay) > parent.indexOfChild(continuous));
        }
    }

    private Activity createActivity() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.AppTheme_Material3);
        activity.setContentView(R.layout.activity_zoom);
        return activity;
    }

    private WindowInsetsCompat insets(Insets safe, boolean visible) {
        return new WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), visible ? safe : Insets.NONE)
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars(), safe)
            .setVisible(WindowInsetsCompat.Type.systemBars(), visible)
            .build();
    }

    private void layout(View root, int width, int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }

    private Rect bounds(View view) {
        return new Rect(view.getLeft(), view.getTop(), view.getRight(), view.getBottom());
    }
}
