package com.theskyc.nreader.utility;

import android.util.JsonReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Rejects malformed UTF-8 and string escapes that Android's JsonReader otherwise accepts. */
public final class JsonStringGuard extends Reader {
    private final Reader source;
    private final int maxStringLength;
    private boolean inString;
    private boolean escaped;
    private int hexDigitsRemaining;
    private int stringLength;

    public static JsonReader open(InputStream source, int maxStringLength) {
        return new JsonReader(new JsonStringGuard(source, maxStringLength));
    }

    private JsonStringGuard(InputStream source, int maxStringLength) {
        this.source = new InputStreamReader(source, StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT));
        this.maxStringLength = maxStringLength;
    }

    @Override
    public int read(char[] buffer, int start, int length) throws IOException {
        int count = source.read(buffer, start, length);
        for (int index = start; index < start + count; index++) {
            char character = buffer[index];
            if (!inString) {
                inString = character == '"';
                stringLength = 0;
            } else if (hexDigitsRemaining > 0) {
                if ("0123456789abcdefABCDEF".indexOf(character) < 0) throw new IOException("Invalid JSON escape");
                hexDigitsRemaining--;
            } else if (escaped) {
                if ("\"\\/bfnrtu".indexOf(character) < 0) throw new IOException("Invalid JSON escape");
                if (character == 'u') hexDigitsRemaining = 4;
                escaped = false;
            } else if (character == '"') {
                inString = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character < 0x20) {
                throw new IOException("Unescaped JSON control character");
            }
            if (inString && ++stringLength > maxStringLength) throw new IOException("JSON string exceeds limit");
        }
        return count;
    }

    @Override
    public void close() throws IOException {
        source.close();
    }
}
