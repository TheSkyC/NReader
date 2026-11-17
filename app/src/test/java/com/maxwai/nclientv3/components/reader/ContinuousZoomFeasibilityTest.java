// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.maxwai.nclientv3.github.chrisbanes.photoview.PhotoView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class ContinuousZoomFeasibilityTest {
    @Test
    public void photoViewMeasuresAContinuousPageByItsAspectRatio() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        PhotoView photo = new PhotoView(activity);
        photo.setAdjustViewBounds(true);
        photo.setImageDrawable(new BitmapDrawable(activity.getResources(),
            Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)));
        photo.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST));
        assertEquals(600, photo.getMeasuredWidth());
        assertEquals(1200, photo.getMeasuredHeight());
    }

    @Test
    public void photoViewHandlesTapsBeforeItsParentClickListener() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout parent = new FrameLayout(activity);
        PhotoView photo = new PhotoView(activity);
        photo.setImageDrawable(new BitmapDrawable(activity.getResources(),
            Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)));
        AtomicInteger parentClicks = new AtomicInteger();
        AtomicInteger photoTaps = new AtomicInteger();
        parent.setOnClickListener(view -> parentClicks.incrementAndGet());
        photo.setOnViewTapListener((view, x, y) -> photoTaps.incrementAndGet());
        parent.addView(photo, new FrameLayout.LayoutParams(600, 1200));
        activity.setContentView(parent);
        parent.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY));
        parent.layout(0, 0, 600, 1200);
        MotionEvent down = MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, 300, 300, 0);
        MotionEvent up = MotionEvent.obtain(100, 120, MotionEvent.ACTION_UP, 300, 300, 0);
        parent.dispatchTouchEvent(down);
        parent.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS);
        assertEquals(1, photoTaps.get());
        assertEquals(0, parentClicks.get());
    }
}
