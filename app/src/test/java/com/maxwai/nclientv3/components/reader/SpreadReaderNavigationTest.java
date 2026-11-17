// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import androidx.fragment.app.Fragment;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class SpreadReaderNavigationTest {
    @Test
    public void logicalPagesMapToTwoPageSpreadsAndOddFinalPage() {
        FragmentActivity activity = Robolectric.buildActivity(FragmentActivity.class).setup().get();
        ViewPager2 pager = new ViewPager2(activity);
        pager.setAdapter(new FragmentStateAdapter(activity) {
            @NonNull
            @Override public Fragment createFragment(int position) { return new Fragment(); }
            @Override public int getItemCount() { return 2; }
        });
        SpreadReaderNavigation navigation = new SpreadReaderNavigation(pager, 3);
        navigation.goToPage(2, false);
        assertEquals(1, pager.getCurrentItem());
        assertEquals(2, navigation.currentPage());
        assertEquals(3, navigation.pageCount());
        navigation.goToPage(1, false);
        assertEquals(0, pager.getCurrentItem());
        assertEquals(1, navigation.currentPage());
        navigation.selectPage(0);
        assertEquals(0, navigation.currentPage());
    }
}
