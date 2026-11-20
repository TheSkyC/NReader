package com.maxwai.nclientv3.components.views;

import android.view.MotionEvent;
import android.view.ViewConfiguration;
import androidx.annotation.NonNull;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.RecyclerView;

public class DrawerSwipeListener implements RecyclerView.OnItemTouchListener {
    private final DrawerLayout drawer;
    private final int touchSlop;
    private final float openDistance;
    private float startX;
    private float startY;
    private boolean tracking;

    public DrawerSwipeListener(DrawerLayout drawer) {
        this.drawer = drawer;
        touchSlop = ViewConfiguration.get(drawer.getContext()).getScaledTouchSlop();
        openDistance = Math.max(touchSlop * 2, 48 * drawer.getResources().getDisplayMetrics().density);
    }

    @Override
    public boolean onInterceptTouchEvent(@NonNull RecyclerView recyclerView, @NonNull MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = event.getX();
                startY = event.getY();
                tracking = drawer.getDrawerLockMode(GravityCompat.START) == DrawerLayout.LOCK_MODE_UNLOCKED
                    && !drawer.isDrawerVisible(GravityCompat.START);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!tracking) break;
                float dx = event.getX() - startX;
                float dy = event.getY() - startY;
                float towardEnd = ViewCompat.getLayoutDirection(drawer) == ViewCompat.LAYOUT_DIRECTION_RTL ? -dx : dx;
                if ((Math.abs(dy) > touchSlop && Math.abs(dy) >= Math.abs(dx)) || towardEnd < -touchSlop) {
                    tracking = false;
                } else if (towardEnd >= openDistance && towardEnd > Math.abs(dy)) {
                    tracking = false;
                    drawer.openDrawer(GravityCompat.START);
                    return true;
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                tracking = false;
                break;
        }
        return false;
    }

    @Override
    public void onTouchEvent(@NonNull RecyclerView recyclerView, @NonNull MotionEvent event) { }

    @Override
    public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        if (disallowIntercept) tracking = false;
    }
}
