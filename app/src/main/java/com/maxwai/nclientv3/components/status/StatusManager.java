// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.status;

import android.graphics.Color;

import androidx.annotation.Nullable;

import com.maxwai.nclientv3.async.database.Queries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

public class StatusManager {
    public static final String DEFAULT_STATUS = "None";
    private static volatile Map<String, Status> statusMap = Collections.singletonMap(
        DEFAULT_STATUS, new Status(Color.BLACK, DEFAULT_STATUS));

    public static synchronized void replaceAll(List<Status> statuses) {
        Map<String, Status> replacement = new HashMap<>();
        replacement.put(DEFAULT_STATUS, new Status(Color.BLACK, DEFAULT_STATUS));
        for (Status status : statuses) replacement.put(status.name, status);
        statusMap = replacement;
    }

    @Nullable
    public static Status getByName(String name) {
        return statusMap.get(name);
    }

    public static Status add(String name, int color) {
        return add(new Status(color, name));
    }

    static Status add(Status status) {
        Queries.StatusTable.insert(status);
        changeCachedStatus(null, status);
        return status;
    }

    public static void remove(Status status) {
        Queries.StatusTable.remove(status.name);
        changeCachedStatus(status.name, null);
    }

    public static List<String> getNames() {
        List<String> st = new ArrayList<>(statusMap.keySet());
        st.sort(String::compareToIgnoreCase);
        st.remove(DEFAULT_STATUS);
        //st.add(0,DEFAULT_STATUS);
        return st;
    }

    public static List<Status> toList() {
        ArrayList<Status> statuses = new ArrayList<>(statusMap.values());
        statuses.sort((o1, o2) -> o1.name.compareToIgnoreCase(o2.name));
        statuses.remove(getByName(DEFAULT_STATUS));
        return statuses;
    }

    public static Status updateStatus(@Nullable Status oldStatus, String newName, int newColor) {
        if (oldStatus == null)
            return add(newName, newColor);
        Status newStatus = new Status(newColor, newName);
        Queries.StatusTable.update(oldStatus, newStatus);
        changeCachedStatus(oldStatus.name, newStatus);
        return newStatus;
    }

    private static synchronized void changeCachedStatus(String oldName, Status status) {
        Map<String, Status> replacement = new HashMap<>(statusMap);
        if (oldName != null && !DEFAULT_STATUS.equals(oldName)) replacement.remove(oldName);
        if (status != null) replacement.put(status.name, status);
        statusMap = replacement;
    }
}
