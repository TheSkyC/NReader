// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.maxwai.nclientv3.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class ContinuousZoomViewportTest {
    @Test
    public void resizingKeepsImagePointUnderTheFocus() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ContinuousZoomViewport viewport = new ContinuousZoomViewport(activity, null);
        RecyclerView reader = viewport;
        LinearLayoutManager layout = new LinearLayoutManager(activity);
        reader.setLayoutManager(layout);
        reader.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
                FrameLayout page = new FrameLayout(activity);
                page.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
                ImageView image = new ImageView(activity);
                image.setId(R.id.continuous_page_image);
                image.setAdjustViewBounds(true);
                image.setImageDrawable(new BitmapDrawable(activity.getResources(),
                    Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)));
                page.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
                View status = new View(activity);
                status.setId(R.id.continuous_page_status);
                status.setVisibility(View.GONE);
                page.addView(status);
                return new RecyclerView.ViewHolder(page) {};
            }
            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {}
            @Override public int getItemCount() { return 3; }
        });
        activity.setContentView(viewport);
        layoutViewport(viewport);
        assertNotNull(layout.findViewByPosition(0));
        View initialPage = layout.findViewByPosition(0);
        View initialImage = initialPage.findViewById(R.id.continuous_page_image);
        assertEquals(600, initialImage.getWidth());
        assertEquals(1200, initialImage.getHeight());
        assertTrue(viewport.beginScale(300, 200));
        assertTrue(viewport.scaleTo(2f, 300, 200));
        assertEquals(600, reader.getWidth());
        assertEquals(1200, initialPage.getLayoutParams().width);
        assertEquals(-300f, initialPage.getTranslationX(), 0.001f);
        assertEquals(2f, viewport.currentScale(), 0.001f);
        viewport.endScale();
        assertFalse(viewport.scaleTo(3f, 300, 200));

        dispatchPointers(viewport, 100, MotionEvent.ACTION_DOWN, 260, 200, 0, 0, 1);
        dispatchPointers(viewport, 120, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            260, 200, 340, 200, 2);
        dispatchPointers(viewport, 140, MotionEvent.ACTION_MOVE, 220, 200, 380, 200, 2);
        dispatchPointers(viewport, 160, MotionEvent.ACTION_MOVE, 150, 200, 450, 200, 2);
        dispatchPointers(viewport, 170, MotionEvent.ACTION_MOVE, 100, 200, 500, 200, 2);
        layoutViewport(viewport);
        assertTrue(viewport.currentScale() > 2f);
        dispatchPointers(viewport, 180, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            150, 200, 450, 200, 2);
        dispatchPointers(viewport, 200, MotionEvent.ACTION_UP, 150, 200, 0, 0, 1);

        float previousOffset = viewport.currentContentOffset();
        dispatchPointers(viewport, 300, MotionEvent.ACTION_DOWN, 300, 250, 0, 0, 1);
        dispatchPointers(viewport, 320, MotionEvent.ACTION_MOVE, 500, 250, 0, 0, 1);
        assertTrue(viewport.currentContentOffset() > previousOffset);
        assertTrue(viewport.currentContentOffset() <= 0f);
        dispatchPointers(viewport, 340, MotionEvent.ACTION_UP, 500, 250, 0, 0, 1);

        previousOffset = viewport.currentContentOffset();
        dispatchPointers(viewport, 400, MotionEvent.ACTION_DOWN, 300, 250, 0, 0, 1);
        dispatchPointers(viewport, 420, MotionEvent.ACTION_MOVE, 300, 100, 0, 0, 1);
        assertEquals(previousOffset, viewport.currentContentOffset(), 0.001f);
        dispatchPointers(viewport, 440, MotionEvent.ACTION_UP, 300, 100, 0, 0, 1);
    }

    private void dispatchPointers(View view, long time, int action, float x1, float y1,
                                  float x2, float y2, int count) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[count];
        for (int index = 0; index < count; index++) {
            properties[index] = new MotionEvent.PointerProperties();
            properties[index].id = index;
            properties[index].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[index] = new MotionEvent.PointerCoords();
            coordinates[index].x = index == 0 ? x1 : x2;
            coordinates[index].y = index == 0 ? y1 : y2;
            coordinates[index].pressure = 1f;
            coordinates[index].size = 1f;
        }
        MotionEvent event = MotionEvent.obtain(100, time, action, count, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, 0, 0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }

    private void layoutViewport(View viewport) {
        viewport.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
        viewport.layout(0, 0, 600, 500);
    }
}
