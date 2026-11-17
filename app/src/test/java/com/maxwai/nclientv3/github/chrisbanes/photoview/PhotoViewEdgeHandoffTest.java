// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.github.chrisbanes.photoview;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

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
