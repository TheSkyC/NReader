package com.theskyc.nreader.adapters;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.theskyc.nreader.GalleryActivity;
import com.theskyc.nreader.R;
import com.theskyc.nreader.api.components.Gallery;
import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.settings.Global;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class GalleryTagLoadingTest {
    @Test public void firstLabelsWaitForLookupAndRefreshKeepsExistingLabels() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        Global.initFromShared(application);
        GalleryActivity activity = Robolectric.buildActivity(GalleryActivity.class).get();
        activity.setTheme(R.style.AppTheme_Material3);
        Gallery gallery = new Gallery(application,
            "{\"id\":117,\"media_id\":1,\"num_pages\":1,"
                + "\"title\":{\"pretty\":\"Test\"},\"pages\":[]}", null, false);
        gallery.getGalleryData().getTags().addTag(new Tag("glasses", 0, 12, TagType.TAG, TagStatus.DEFAULT));
        GalleryAdapter adapter = new GalleryAdapter(activity, gallery, 1, true);
        RecyclerView parent = new RecyclerView(activity);
        parent.setLayoutManager(new LinearLayoutManager(activity));
        GalleryAdapter.ViewHolder holder = adapter.onCreateViewHolder(parent, GalleryAdapter.Type.TAG.ordinal());

        adapter.beginTagDisplayNames();
        bind(adapter, holder);
        assertEquals(View.VISIBLE, holder.itemView.findViewById(R.id.gallery_tag_loading).getVisibility());
        assertEquals(View.GONE, holder.itemView.findViewById(R.id.tag_master).getVisibility());
        assertEquals(View.VISIBLE, holder.itemView.findViewById(R.id.gallery_metadata).getVisibility());

        adapter.setTagDisplayNames(Collections.singletonMap(12, "眼镜"));
        bind(adapter, holder);
        assertEquals(View.GONE, holder.itemView.findViewById(R.id.gallery_tag_loading).getVisibility());
        assertEquals(View.VISIBLE, holder.itemView.findViewById(R.id.tag_master).getVisibility());
        assertEquals("眼镜", label(holder).getText().toString());

        adapter.beginTagDisplayNames();
        bind(adapter, holder);
        assertEquals(View.VISIBLE, holder.itemView.findViewById(R.id.tag_master).getVisibility());
        assertEquals("眼镜", label(holder).getText().toString());

        adapter.setTagDisplayNames(Collections.emptyMap());
        bind(adapter, holder);
        assertEquals("glasses", label(holder).getText().toString());
    }

    private static void bind(GalleryAdapter adapter, GalleryAdapter.ViewHolder holder) {
        ReflectionHelpers.callInstanceMethod(adapter, "loadTagLayout",
            ReflectionHelpers.ClassParameter.from(GalleryAdapter.ViewHolder.class, holder));
    }

    private static TextView label(GalleryAdapter.ViewHolder holder) {
        ViewGroup categories = holder.itemView.findViewById(R.id.tag_master);
        ViewGroup chips = categories.getChildAt(TagType.TAG.getId()).findViewById(R.id.chip_group);
        return (TextView) chips.getChildAt(0);
    }
}
