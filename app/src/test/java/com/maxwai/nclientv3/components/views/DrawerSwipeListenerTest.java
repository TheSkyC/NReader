package com.maxwai.nclientv3.components.views;

import android.app.Application;
import android.view.MotionEvent;
import android.view.View;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.RecyclerView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class DrawerSwipeListenerTest {
    private TestDrawer drawer;
    private RecyclerView recycler;
    private DrawerSwipeListener listener;

    @Before public void setUp() {
        drawer = new TestDrawer();
        recycler = new RecyclerView(RuntimeEnvironment.getApplication());
        listener = new DrawerSwipeListener(drawer);
    }

    @Test public void opensFromContentInLayoutDirection() {
        gesture(MotionEvent.ACTION_DOWN, 200, 200);
        assertTrue(gesture(MotionEvent.ACTION_MOVE, 400, 205));
        assertEquals(1, drawer.opens);
        drawer.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        gesture(MotionEvent.ACTION_DOWN, 400, 200);
        assertTrue(gesture(MotionEvent.ACTION_MOVE, 200, 205));
        assertEquals(2, drawer.opens);
    }

    @Test public void verticalWrongWayAndCancelledGesturesDoNotOpen() {
        gesture(MotionEvent.ACTION_DOWN, 200, 200);
        gesture(MotionEvent.ACTION_MOVE, 205, 300);
        assertFalse(gesture(MotionEvent.ACTION_MOVE, 400, 300));
        gesture(MotionEvent.ACTION_DOWN, 200, 200);
        assertFalse(gesture(MotionEvent.ACTION_MOVE, 50, 200));
        gesture(MotionEvent.ACTION_DOWN, 200, 200);
        gesture(MotionEvent.ACTION_CANCEL, 200, 200);
        assertFalse(gesture(MotionEvent.ACTION_MOVE, 400, 200));
        assertEquals(0, drawer.opens);
    }

    @Test public void lockedDrawerAndMultitouchDoNotOpen() {
        drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.START);
        gesture(MotionEvent.ACTION_DOWN, 200, 200);
        assertFalse(gesture(MotionEvent.ACTION_MOVE, 400, 200));
        drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.START);
        gesture(MotionEvent.ACTION_DOWN, 200, 200);
        gesture(MotionEvent.ACTION_POINTER_DOWN, 200, 200);
        assertFalse(gesture(MotionEvent.ACTION_MOVE, 400, 200));
        assertEquals(0, drawer.opens);
    }

    private boolean gesture(int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, 0, action, x, y, 0);
        try {
            return listener.onInterceptTouchEvent(recycler, event);
        } finally {
            event.recycle();
        }
    }

    private static class TestDrawer extends DrawerLayout {
        int opens;

        TestDrawer() {
            super(RuntimeEnvironment.getApplication());
        }

        @Override public void openDrawer(int gravity) {
            assertEquals(GravityCompat.START, gravity);
            opens++;
        }
    }
}
