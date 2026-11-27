// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import androidx.annotation.Nullable;

import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.utility.network.NetworkUtil;

import java.util.ArrayList;
import java.util.List;

public final class ReaderPrefetchPlan {
    public static final int DEFAULT_PAGE_COUNT = 4;
    public static final int MAX_PAGE_COUNT = 8;

    private ReaderPrefetchPlan() {}

    public static int pageCountForValue(@Nullable String value) {
        if ("0".equals(value)) return 0;
        if ("2".equals(value)) return 2;
        if ("8".equals(value)) return 8;
        return DEFAULT_PAGE_COUNT;
    }

    public static boolean isAllowed(int pageCount, boolean allowMetered,
                                    @Nullable Global.DataUsageType imagePolicy,
                                    @Nullable NetworkUtil.ConnectionType connectionType) {
        if (pageCount <= 0 || imagePolicy == null || imagePolicy == Global.DataUsageType.NONE
            || connectionType == null || connectionType == NetworkUtil.ConnectionType.UNKNOWN) return false;
        if (connectionType == NetworkUtil.ConnectionType.WIFI) return true;
        return allowMetered && (connectionType == NetworkUtil.ConnectionType.CELLULAR
            || connectionType == NetworkUtil.ConnectionType.METERED);
    }

    public static List<Integer> pageWindow(int pageCount, int currentPage, int direction,
                                           int requestedCount, boolean doublePage) {
        List<Integer> pages = new ArrayList<>();
        if (pageCount <= 0 || currentPage < 0 || currentPage >= pageCount || requestedCount <= 0) return pages;

        int step = direction < 0 ? -1 : 1;
        int edge = currentPage;
        if (doublePage) {
            int spread = SpreadReaderNavigation.spreadForPage(currentPage, pageCount);
            int first = SpreadReaderNavigation.firstPageForSpread(spread, pageCount);
            int second = SpreadReaderNavigation.secondPageForSpread(spread, pageCount);
            edge = step > 0 && second >= 0 ? second : first;
        }

        int count = Math.min(requestedCount, MAX_PAGE_COUNT);
        for (int page = edge + step; pages.size() < count && page >= 0 && page < pageCount; page += step)
            pages.add(page);
        return pages;
    }
}
