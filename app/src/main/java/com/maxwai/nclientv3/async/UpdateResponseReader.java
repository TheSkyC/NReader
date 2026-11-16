// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okio.BufferedSource;

final class UpdateResponseReader {
    static final long RELEASE_LIMIT = 2L * 1024L * 1024L;
    static final long MANIFEST_LIMIT = 1024L * 1024L;

    private UpdateResponseReader() {}

    static String read(ResponseBody body, long limit) throws IOException {
        if (body.contentLength() > limit) throw new IOException("Update metadata exceeds size limit");
        BufferedSource source = body.source();
        if (source.request(limit + 1L)) throw new IOException("Update metadata exceeds size limit");
        MediaType contentType = body.contentType();
        Charset charset = contentType == null ? StandardCharsets.UTF_8 : contentType.charset(StandardCharsets.UTF_8);
        String text = source.readString(charset);
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }
}
