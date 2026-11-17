// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import android.app.Activity;
import android.app.Application;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.local.LocalGallery;
import com.maxwai.nclientv3.settings.Global;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class ContinuousPageAdapterTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void missingPageShowsRetryAndDoesNotBlockOtherPageSelection() throws Exception {
        File folder = temporaryFolder.newFolder();
        assertTrue(new File(folder, "1.png").createNewFile());
        assertTrue(new File(folder, "3.png").createNewFile());
        LocalGallery gallery = new LocalGallery(folder, true);
        Global.initFromShared(RuntimeEnvironment.getApplication());
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RecyclerView recycler = new RecyclerView(activity);
        recycler.setLayoutManager(new LinearLayoutManager(activity));
        AtomicInteger tapped = new AtomicInteger(-1);
        recycler.setAdapter(new ContinuousPageAdapter(gallery, gallery.getGalleryFolder(), tapped::set));
        activity.setContentView(recycler);
        recycler.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY));
        recycler.layout(0, 0, 600, 1200);
        RecyclerView.ViewHolder first = recycler.findViewHolderForAdapterPosition(0);
        RecyclerView.ViewHolder missing = recycler.findViewHolderForAdapterPosition(1);
        assertNotNull(first);
        assertNotNull(missing);
        assertEquals(View.VISIBLE, missing.itemView.findViewById(R.id.continuous_page_error).getVisibility());
        assertEquals(View.VISIBLE, missing.itemView.findViewById(R.id.continuous_page_retry).getVisibility());
        missing.itemView.findViewById(R.id.continuous_page_retry).performClick();
        assertEquals(View.VISIBLE, missing.itemView.findViewById(R.id.continuous_page_error).getVisibility());
        first.itemView.performClick();
        assertEquals(0, tapped.get());
        recycler.setAdapter(null);
    }
}
