// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.adapters;

import android.app.Application;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.theskyc.nreader.LocalActivity;
import com.theskyc.nreader.R;
import com.theskyc.nreader.components.classes.MultichoiceAdapter;
import com.theskyc.nreader.components.widgets.CustomGridLayoutManager;
import com.theskyc.nreader.settings.Global;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class LocalAdapterGridTest {
    @Test
    public void localAndTaskRowsUseTheSameCoverCardForWidthFallback() {
        LocalActivity activity = Robolectric.buildActivity(LocalActivity.class).get();
        activity.setTheme(R.style.AppTheme_Material3);
        Global.initFromShared(activity);
        LocalAdapter adapter = new LocalAdapter(activity, new ArrayList<>());
        try {
            RecyclerView parent = new RecyclerView(activity);
            parent.setLayoutManager(new CustomGridLayoutManager(activity, 2));
            for (int type : new int[]{0, 1}) {
                MultichoiceAdapter.MultichoiceViewHolder<LocalAdapter.ViewHolder> holder = adapter.onCreateViewHolder(parent, type);
                View card = holder.itemView;
                assertTrue(card.findViewById(R.id.gallery_cover) instanceof MaterialCardView);
                assertNotNull(card.findViewById(R.id.gallery_favorite));
                assertNotNull(card.findViewById(R.id.gallery_local_files));
                assertNotNull(card.findViewById(R.id.gallery_download_progress));
                assertNotNull(card.findViewById(R.id.censor));
                assertNull(card.findViewById(R.id.cancelButton));
            }
        } finally {
            adapter.removeObserver();
        }
    }
}
