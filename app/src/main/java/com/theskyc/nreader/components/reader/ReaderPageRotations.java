// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/** Session-only rotation state, keyed by zero-based page number. */
public final class ReaderPageRotations {
    private static final int MAX_ENTRIES = 256;
    private static final String PAGES = "continuousRotationPages";
    private static final String ANGLES = "continuousRotationAngles";
    private final LinkedHashMap<Integer, Integer> angles = new LinkedHashMap<>();

    public int angle(int page) {
        Integer angle = angles.get(page);
        return angle == null ? 0 : angle;
    }

    public int rotate(int page) {
        if (page < 0) return -1;
        int angle = (angle(page) + 270) % 360;
        angles.remove(page);
        int evicted = -1;
        if (angle != 0) {
            if (angles.size() == MAX_ENTRIES) {
                evicted = angles.keySet().iterator().next();
                angles.remove(evicted);
            }
            angles.put(page, angle);
        }
        return evicted;
    }

    public void save(@NonNull Bundle state) {
        int[] pages = new int[angles.size()];
        int[] values = new int[angles.size()];
        int index = 0;
        for (Map.Entry<Integer, Integer> entry : angles.entrySet()) {
            pages[index] = entry.getKey();
            values[index++] = entry.getValue();
        }
        state.putIntArray(PAGES, pages);
        state.putIntArray(ANGLES, values);
    }

    public void restore(@Nullable Bundle state, int pageCount) {
        angles.clear();
        if (state == null) return;
        int[] pages = state.getIntArray(PAGES);
        int[] values = state.getIntArray(ANGLES);
        if (pages == null || values == null || pages.length != values.length || pages.length > MAX_ENTRIES) return;
        for (int index = 0; index < pages.length; index++) {
            int page = pages[index];
            int angle = values[index];
            if (page >= 0 && page < pageCount && (angle == 90 || angle == 180 || angle == 270)) {
                angles.remove(page);
                angles.put(page, angle);
            }
        }
    }
}
