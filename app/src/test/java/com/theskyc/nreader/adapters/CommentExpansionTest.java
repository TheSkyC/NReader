package com.theskyc.nreader.adapters;

import android.app.Application;
import android.os.Looper;
import android.util.JsonReader;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.theskyc.nreader.R;
import com.theskyc.nreader.api.comments.Comment;
import com.theskyc.nreader.settings.Global;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "w320dp-h640dp")
public class CommentExpansionTest {
    @Test
    public void onlyTruncatedCommentsOfferExpansionAndKeepTheirOwnState() throws Exception {
        ActivityController<AppCompatActivity> controller = Robolectric.buildActivity(AppCompatActivity.class);
        AppCompatActivity activity = controller.get();
        activity.setTheme(R.style.AppTheme_Material3);
        controller.setup();
        Global.initFromShared(activity);
        try {
            String longText = "A longer comment.\n".repeat(20);
            ArrayList<Comment> comments = new ArrayList<>(Arrays.asList(
                comment(1, longText), comment(2, "Short"), comment(3, "Line\n".repeat(6) + "Line")));
            CommentAdapter adapter = new CommentAdapter(activity, comments, true);
            RecyclerView list = new RecyclerView(activity);
            list.setLayoutManager(new LinearLayoutManager(activity));
            list.setAdapter(adapter);
            activity.setContentView(list);
            layout(list, activity, 320);

            View longRow = list.getLayoutManager().findViewByPosition(0);
            assertNotNull(longRow);
            Button more = longRow.findViewById(R.id.comment_expand);
            TextView body = longRow.findViewById(R.id.body);
            assertEquals("lines=" + body.getLayout().getLineCount() + " ellipsis="
                + body.getLayout().getEllipsisCount(body.getLayout().getLineCount() - 1)
                + " width=" + body.getWidth() + " end="
                + body.getLayout().getLineEnd(body.getLayout().getLineCount() - 1) + " length=" + body.length(),
                View.VISIBLE, more.getVisibility());
            assertEquals(activity.getString(R.string.comment_show_more), more.getText().toString());
            assertEquals(7, body.getMaxLines());
            more.performClick();
            layout(list, activity, 320);
            assertEquals(Integer.MAX_VALUE, body.getMaxLines());
            assertEquals(activity.getString(R.string.comment_show_less), more.getText().toString());

            java.util.Collections.swap(comments, 0, 1);
            adapter.notifyDataSetChanged();
            layout(list, activity, 320);
            longRow = list.getLayoutManager().findViewByPosition(1);
            body = longRow.findViewById(R.id.body);
            more = longRow.findViewById(R.id.comment_expand);
            assertEquals(Integer.MAX_VALUE, body.getMaxLines());
            longRow.performClick();
            layout(list, activity, 320);
            assertEquals(7, body.getMaxLines());

            list.scrollToPosition(0);
            layout(list, activity, 320);
            View shortRow = list.getLayoutManager().findViewByPosition(0);
            assertNotNull(shortRow);
            assertEquals(View.GONE, shortRow.findViewById(R.id.comment_expand).getVisibility());
            assertFalse(shortRow.isClickable());
            list.scrollToPosition(2);
            layout(list, activity, 320);
            View exactRow = list.getLayoutManager().findViewByPosition(2);
            assertNotNull(exactRow);
            assertEquals(View.GONE, exactRow.findViewById(R.id.comment_expand).getVisibility());
            list.scrollToPosition(1);
            layout(list, activity, 320);
            longRow = list.getLayoutManager().findViewByPosition(1);
            assertEquals(View.VISIBLE, longRow.findViewById(R.id.comment_expand).getVisibility());
            assertEquals(7, ((TextView) longRow.findViewById(R.id.body)).getMaxLines());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    private static Comment comment(int id, String text) throws Exception {
        String json = "{\"id\":" + id + ",\"post_date\":1,\"body\":" + JSONObject.quote(text)
            + ",\"poster\":{\"id\":2,\"username\":\"reader\",\"avatar_url\":\"\"}}";
        return new Comment(new JsonReader(new StringReader(json)));
    }

    private static void layout(RecyclerView list, AppCompatActivity activity, int widthDp) {
        int width = Math.round(activity.getResources().getDisplayMetrics().density * widthDp);
        int height = Math.round(activity.getResources().getDisplayMetrics().density * 640);
        for (int pass = 0; pass < 4; pass++) {
            list.requestLayout();
            list.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            list.layout(0, 0, width, height);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }
}
