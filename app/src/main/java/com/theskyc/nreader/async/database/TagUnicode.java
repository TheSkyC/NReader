package com.theskyc.nreader.async.database;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.text.Normalizer2;

import java.util.Locale;

/** Unicode 16 operations for the next translation format. */
final class TagUnicode {
    private static final Normalizer2 NFC = Normalizer2.getNFCInstance();

    private TagUnicode() {}

    static String normalize(String value) {
        return NFC.normalize(value);
    }

    static String searchKey(String value) {
        return UCharacter.toLowerCase(Locale.ROOT, normalize(value)).replace('\u03c2', '\u03c3');
    }
}
