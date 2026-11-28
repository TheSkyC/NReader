package com.theskyc.nreader.adapters;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

public class ListAdapterTest {
    @Test
    public void replaceLastItemsKeepsPrefixAndReplacesCachedTail() {
        List<String> existing = Arrays.asList("first", "cached-one", "cached-two");

        ArrayList<String> result = ListAdapter.replaceLastItems(existing, 2,
            Arrays.asList("fresh-one", "fresh-two", "fresh-three"));

        assertEquals(Arrays.asList("first", "fresh-one", "fresh-two", "fresh-three"), result);
        assertEquals(Arrays.asList("first", "cached-one", "cached-two"), existing);
        assertNotSame(existing, result);
    }

    @Test
    public void replaceLastItemsAppendsWhenThereIsNoCachedTail() {
        List<String> existing = Arrays.asList("first", "second");

        ArrayList<String> result = ListAdapter.replaceLastItems(existing, 0,
            Arrays.asList("third", "fourth"));

        assertEquals(Arrays.asList("first", "second", "third", "fourth"), result);
    }

    @Test
    public void replaceLastItemsClampsTailCountToAvailableItems() {
        List<String> existing = Arrays.asList("first", "second");

        ArrayList<String> result = ListAdapter.replaceLastItems(existing, 5,
            Arrays.asList("replacement"));

        assertEquals(Arrays.asList("replacement"), result);
    }
}
