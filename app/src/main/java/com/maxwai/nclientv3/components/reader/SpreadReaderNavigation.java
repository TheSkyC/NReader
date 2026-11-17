// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import androidx.annotation.NonNull;
import androidx.viewpager2.widget.ViewPager2;

public final class SpreadReaderNavigation implements ReaderNavigation {
    private final ViewPager2 pager;
    private final int logicalPageCount;
    private int selectedPage;

    public SpreadReaderNavigation(@NonNull ViewPager2 pager, int logicalPageCount) {
        this.pager = pager;
        this.logicalPageCount = Math.max(0, logicalPageCount);
        selectedPage = 0;
    }

    @Override
    public int currentPage() {
        if (logicalPageCount == 0) return 0;
        int first = pager.getCurrentItem() * 2;
        if (selectedPage < first || selectedPage >= Math.min(logicalPageCount, first + 2)) selectedPage = first;
        return Math.min(logicalPageCount - 1, selectedPage);
    }

    @Override
    public int pageCount() {
        return logicalPageCount;
    }

    @Override
    public void goToPage(int page, boolean smooth) {
        if (logicalPageCount == 0) return;
        int clamped = Math.max(0, Math.min(page, logicalPageCount - 1));
        selectedPage = clamped;
        pager.setCurrentItem(clamped / 2, smooth);
    }

    public void selectPage(int page) {
        int first = pager.getCurrentItem() * 2;
        if (page >= first && page < Math.min(logicalPageCount, first + 2)) selectedPage = page;
    }

    public int spreadCount() {
        return (logicalPageCount + 1) / 2;
    }
}
