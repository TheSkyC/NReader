package com.theskyc.nreader;

import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ContextThemeWrapper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class CFTokenLayoutTest {
    @Test
    public void hideActionIsDrawnAboveTheWebView() {
        AppCompatActivity activity = Robolectric.buildActivity(AppCompatActivity.class).setup().get();
        Context context = new ContextThemeWrapper(activity, R.style.AppTheme_Material3);
        ViewGroup root = (ViewGroup) LayoutInflater.from(context).inflate(R.layout.cftoken_layout, null);
        View hide = root.findViewById(R.id.hideWebView);
        View web = root.findViewById(R.id.webView);
        activity.setContentView(root);

        assertTrue(hide.getZ() > web.getZ()
            || (hide.getZ() == web.getZ() && root.indexOfChild(hide) > root.indexOfChild(web)));

        int width = Math.round(320 * context.getResources().getDisplayMetrics().density);
        int height = Math.round(640 * context.getResources().getDisplayMetrics().density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        boolean[] clicked = {false};
        hide.setOnClickListener(view -> clicked[0] = true);
        float x = (hide.getLeft() + hide.getRight()) / 2f;
        float y = (hide.getTop() + hide.getBottom()) / 2f;
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(0, 1, MotionEvent.ACTION_UP, x, y, 0);
        root.dispatchTouchEvent(down);
        root.dispatchTouchEvent(up);
        shadowOf(Looper.getMainLooper()).idle();
        down.recycle();
        up.recycle();
        assertTrue("button bounds: " + hide.getLeft() + "," + hide.getTop() + "-"
            + hide.getRight() + "," + hide.getBottom(), clicked[0]);
    }
}
