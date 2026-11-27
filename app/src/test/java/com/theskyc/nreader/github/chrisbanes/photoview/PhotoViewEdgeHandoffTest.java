// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.github.chrisbanes.photoview;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class PhotoViewEdgeHandoffTest {
    @Test
    public void verticalPagerWaitsForTallImageEdgeBeforeIntercepting() {
        TrackingParent parent = new TrackingParent(RuntimeEnvironment.getApplication());
        PhotoView photo = createPhoto(parent, 100, 1000);
        photo.setParentInterceptVertical(true);
        photo.setScale(2f, false);
        OnGestureListener gesture = ReflectionHelpers.getField(photo.getAttacher(), "onGestureListener");
        parent.disallowed = true;
        gesture.onDrag(0, -10);
        assertTrue(parent.disallowed);
        gesture.onDrag(0, -2000);
        assertFalse(parent.disallowed);
        gesture.onDrag(0, 10);
        assertTrue(parent.disallowed);
        parent.disallowed = false;
        gesture.onScale(1.1f, 150, 250);
        assertTrue(parent.disallowed);
    }

    @Test
    public void horizontalPagerWaitsForWideImageEdgeBeforeIntercepting() {
        TrackingParent parent = new TrackingParent(RuntimeEnvironment.getApplication());
        PhotoView photo = createPhoto(parent, 1000, 100);
        photo.setParentInterceptVertical(false);
        photo.setScale(2f, false);
        OnGestureListener gesture = ReflectionHelpers.getField(photo.getAttacher(), "onGestureListener");
        parent.disallowed = true;
        gesture.onDrag(-10, 0);
        assertTrue(parent.disallowed);
        gesture.onDrag(-2000, 0);
        assertFalse(parent.disallowed);
        gesture.onDrag(10, 0);
        assertTrue(parent.disallowed);
    }

    @Test
    public void touchingBeforeTheNextZoomFrameKeepsTheCurrentScaleAndAllowsDragging() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).create()) {
            TrackingParent parent = new TrackingParent(controller.get());
            PhotoView photo = createPhoto(parent, 500, 500);
            controller.get().setContentView(parent);
            controller.start().resume().visible();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(64, TimeUnit.MILLISECONDS);
            assertTrue(photo.isAttachedToWindow());
            float centerX = photo.getWidth() / 2f;
            float centerY = photo.getHeight() / 2f;
            photo.setScale(1.5f, centerX, centerY, false);
            photo.setScale(2f, centerX, centerY, true);

            float intermediateScale = photo.getScale();
            RectF beforeDrag = new RectF(photo.getDisplayRect());
            long downTime = SystemClock.uptimeMillis();
            dispatch(photo, downTime, MotionEvent.ACTION_DOWN, centerX, centerY);
            assertEquals(beforeDrag, photo.getDisplayRect());
            dispatch(photo, downTime, MotionEvent.ACTION_MOVE, centerX + 30f, centerY);
            RectF afterDrag = new RectF(photo.getDisplayRect());
            assertEquals(beforeDrag.left + 30f, afterDrag.left, 1f);

            Shadows.shadowOf(Looper.getMainLooper()).idleFor(1000, TimeUnit.MILLISECONDS);
            assertEquals(intermediateScale, photo.getScale(), 0f);
            assertEquals(afterDrag, photo.getDisplayRect());
            dispatch(photo, downTime, MotionEvent.ACTION_CANCEL, centerX + 30f, centerY);
        }
    }

    @Test
    public void touchScaleTakesOverAnAnimatedZoomBetweenFrames() {
        TrackingParent parent = new TrackingParent(RuntimeEnvironment.getApplication());
        PhotoView photo = createPhoto(parent, 500, 500);
        PhotoViewAttacher attacher = photo.getAttacher();
        photo.setScale(2f, 150f, 250f, true);
        Runnable frame = ReflectionHelpers.getField(attacher, "mCurrentZoomRunnable");
        ShadowSystemClock.advanceBy(Duration.ofMillis(100));
        frame.run();
        float intermediateScale = photo.getScale();
        assertTrue(intermediateScale > 1f && intermediateScale < 2f);

        OnGestureListener gesture = ReflectionHelpers.getField(attacher, "onGestureListener");
        gesture.onScale(1.1f, 150f, 250f, 0f, 0f);
        assertEquals(intermediateScale * 1.1f, photo.getScale(), 0.001f);
        ShadowSystemClock.advanceBy(Duration.ofMillis(100));
        frame.run();
        assertEquals(intermediateScale * 1.1f, photo.getScale(), 0.001f);
    }

    private static void dispatch(PhotoView photo, long downTime, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
        photo.dispatchTouchEvent(event);
        event.recycle();
    }

    private PhotoView createPhoto(TrackingParent parent, int width, int height) {
        PhotoView photo = new PhotoView(parent.getContext());
        parent.addView(photo);
        photo.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
        photo.layout(0, 0, 300, 500);
        photo.setImageDrawable(new BitmapDrawable(parent.getResources(),
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)));
        return photo;
    }

    private static class TrackingParent extends FrameLayout {
        boolean disallowed;

        TrackingParent(Context context) { super(context); }

        @Override public void requestDisallowInterceptTouchEvent(boolean disallow) {
            disallowed = disallow;
            super.requestDisallowInterceptTouchEvent(disallow);
        }
    }
}
