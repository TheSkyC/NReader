// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

public final class PagedReaderNavigation implements ReaderNavigation {
    private final ViewPager2 pager;

    public PagedReaderNavigation(@NonNull ViewPager2 pager) {
        this.pager = pager;
    }

    @Override
    public int currentPage() {
        return pager.getCurrentItem();
    }

    @Override
    public int pageCount() {
        RecyclerView.Adapter<?> adapter = pager.getAdapter();
        return adapter == null ? 0 : adapter.getItemCount();
    }

    @Override
    public void goToPage(int page, boolean smooth) {
        int count = pageCount();
        if (count == 0) return;
        pager.setCurrentItem(Math.max(0, Math.min(page, count - 1)), smooth);
    }
}
