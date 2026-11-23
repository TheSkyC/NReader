package com.theskyc.nreader.async.database;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.ibm.icu.lang.UCharacter;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TagUnicodeDeviceTest {
    @Test public void packagedUnicodeDataWorksOnAndroid() {
        assertEquals("16.0.0.0", UCharacter.getUnicodeVersion().toString());
        assertEquals("Caf\u00e9", TagUnicode.normalize("Cafe\u0301"));
        assertEquals("\uac00", TagUnicode.normalize("\u1100\u1161"));
        assertEquals("\u1c8a", TagUnicode.searchKey("\u1c89"));
        assertEquals("\u03bf\u03c3", TagUnicode.searchKey("\u039f\u03a3"));
        assertEquals("i\u0307", TagUnicode.searchKey("\u0130"));
        assertEquals("\ud801\udc28", TagUnicode.searchKey("\ud801\udc00"));
    }
}
