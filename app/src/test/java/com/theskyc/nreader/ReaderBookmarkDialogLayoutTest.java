package com.theskyc.nreader;

import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ContextThemeWrapper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "w560dp-h320dp")
public class ReaderBookmarkDialogLayoutTest {
    @Test
    public void noteCanBeRevealedAtLargeTextOnShortScreens() {
        AppCompatActivity activity = Robolectric.buildActivity(AppCompatActivity.class).get();
        Configuration configuration = new Configuration(activity.getResources().getConfiguration());
        configuration.fontScale = 1.5f;
        Context context = new ContextThemeWrapper(activity.createConfigurationContext(configuration),
            R.style.AppTheme_Material3);
        ScrollView fields = (ScrollView) LayoutInflater.from(context).inflate(R.layout.dialog_reader_bookmark, null);
        EditText note = fields.findViewById(R.id.reader_bookmark_note);
        note.setText("A note that should remain visible");
        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.round(320 * density);
        int height = Math.round(140 * density);
        fields.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        fields.layout(0, 0, width, height);

        assertTrue(fields.getChildAt(0).getHeight() > fields.getHeight());
        Rect noteBounds = new Rect();
        note.getDrawingRect(noteBounds);
        fields.offsetDescendantRectToMyCoords(note, noteBounds);
        fields.scrollTo(0, noteBounds.bottom);
        assertTrue(fields.getScrollY() > 0);
        assertTrue(noteBounds.bottom - fields.getScrollY() <= fields.getHeight());
        assertTrue(note.getHeight() >= note.getLayout().getHeight());
    }
}
