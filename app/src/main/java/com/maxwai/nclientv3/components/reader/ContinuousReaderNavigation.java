// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.function.IntConsumer;

public final class ContinuousReaderNavigation implements ReaderNavigation {
    private final RecyclerView recycler;
    private final LinearLayoutManager layout;
    private final IntConsumer onPageChanged;
    private int page;
    private int pendingPage = RecyclerView.NO_POSITION;

    public ContinuousReaderNavigation(@NonNull RecyclerView recycler, @NonNull LinearLayoutManager layout,
                                      int initialPage, @NonNull IntConsumer onPageChanged) {
        this.recycler = recycler;
        this.layout = layout;
        this.page = initialPage;
        this.onPageChanged = onPageChanged;
        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView view, int state) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING || state == RecyclerView.SCROLL_STATE_SETTLING)
                    pendingPage = RecyclerView.NO_POSITION;
            }

            @Override
            public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
                if (pendingPage != RecyclerView.NO_POSITION) return;
                int visible = layout.findFirstVisibleItemPosition();
                if (visible != RecyclerView.NO_POSITION && visible != page) {
                    page = visible;
                    onPageChanged.accept(page);
                }
            }
        });
    }

    @Override
    public int currentPage() {
        return page;
    }

    @Override
    public int pageCount() {
        RecyclerView.Adapter<?> adapter = recycler.getAdapter();
        return adapter == null ? 0 : adapter.getItemCount();
    }

    @Override
    public void goToPage(int requestedPage, boolean smooth) {
        int count = pageCount();
        if (count == 0) return;
        page = Math.max(0, Math.min(requestedPage, count - 1));
        pendingPage = page;
        layout.scrollToPositionWithOffset(page, 0);
        onPageChanged.accept(page);
    }
}
