package com.maxwai.nclientv3.ui.main;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.maxwai.nclientv3.StatusViewerActivity;
import com.maxwai.nclientv3.components.status.StatusManager;

import java.util.List;
import java.util.Locale;
import java.util.Arrays;

/**
 * A [FragmentPagerAdapter] that returns a fragment corresponding to
 * one of the sections/tabs/pages.
 */
public class SectionsPagerAdapter extends FragmentStateAdapter {
    final List<String> statuses;
    private final int[] counts;

    public SectionsPagerAdapter(StatusViewerActivity context) {
        super(context.getSupportFragmentManager(), context.getLifecycle());
        statuses = StatusManager.getNames();
        counts = new int[statuses.size()];
        Arrays.fill(counts, -1);
    }

    @Nullable
    public CharSequence getPageTitle(int position) {
        String status = statuses.get(position);
        int count = counts[position];
        return count < 0 ? status : String.format(Locale.US, "%s - %d", status, count);
    }

    public void setCount(int position, int count) {
        if (position >= 0 && position < counts.length) counts[position] = count;
    }

    public String getStatus(int position) {
        return statuses.get(position);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        return PlaceholderFragment.newInstance(statuses.get(position));
    }

    @Override
    public int getItemCount() {
        return statuses.size();
    }
}
