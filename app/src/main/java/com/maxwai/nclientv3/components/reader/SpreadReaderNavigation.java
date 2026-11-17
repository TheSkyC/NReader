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
        int first = firstPageForSpread(pager.getCurrentItem(), logicalPageCount);
        int second = secondPageForSpread(pager.getCurrentItem(), logicalPageCount);
        if (selectedPage != first && selectedPage != second) selectedPage = first;
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
        pager.setCurrentItem(spreadForPage(clamped, logicalPageCount), smooth);
    }

    public void selectPage(int page) {
        int first = firstPageForSpread(pager.getCurrentItem(), logicalPageCount);
        int second = secondPageForSpread(pager.getCurrentItem(), logicalPageCount);
        if (page == first || page == second) selectedPage = page;
    }

    public int spreadCount() {
        return spreadCount(logicalPageCount);
    }

    public static int spreadCount(int pageCount) {
        if (pageCount <= 0) return 0;
        return pageCount == 1 ? 1 : 1 + (pageCount - 1 + 1) / 2;
    }

    public static int firstPageForSpread(int spread, int pageCount) {
        if (pageCount <= 0) return 0;
        return spread <= 0 ? 0 : Math.min(pageCount - 1, 1 + (spread - 1) * 2);
    }

    public static int secondPageForSpread(int spread, int pageCount) {
        int first = firstPageForSpread(spread, pageCount);
        if (spread <= 0 || first + 1 >= pageCount) return -1;
        return first + 1;
    }

    public static int spreadForPage(int page, int pageCount) {
        if (pageCount <= 1 || page <= 0) return 0;
        return 1 + (page - 1) / 2;
    }
}
