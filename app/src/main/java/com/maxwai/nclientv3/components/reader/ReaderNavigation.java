// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

public interface ReaderNavigation {
    int currentPage();

    int pageCount();

    void goToPage(int page, boolean smooth);
}
