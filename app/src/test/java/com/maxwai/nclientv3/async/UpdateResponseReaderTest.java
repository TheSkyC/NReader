// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import org.junit.Test;

public class UpdateResponseReaderTest {
    @Test
    public void acceptsUnknownLengthAtLimitAndUtf8Bom() throws IOException {
        ResponseBody body = body("\uFEFFabc".getBytes(StandardCharsets.UTF_8), -1L);
        assertEquals("abc", UpdateResponseReader.read(body, 6L));
    }

    @Test
    public void rejectsUnknownLengthAndMisreportedLengthOverLimit() {
        byte[] data = "1234567".getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> UpdateResponseReader.read(body(data, -1L), 6L));
        assertThrows(IOException.class, () -> UpdateResponseReader.read(body(data, 2L), 6L));
    }

    @Test
    public void rejectsDeclaredOversizeBeforeReading() {
        assertThrows(IOException.class, () -> UpdateResponseReader.read(body(new byte[0], 7L), 6L));
    }

    private static ResponseBody body(byte[] data, long declaredLength) {
        Buffer buffer = new Buffer().write(data);
        return new ResponseBody() {
            @Override public MediaType contentType() { return null; }
            @Override public long contentLength() { return declaredLength; }
            @Override public BufferedSource source() { return buffer; }
        };
    }
}
