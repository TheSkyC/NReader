// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

public interface ReaderNavigation {
    int currentPage();

    int pageCount();

    void goToPage(int page, boolean smooth);
}
