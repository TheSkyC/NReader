package com.theskyc.nreader.components.cache;

import com.theskyc.nreader.components.reader.ReaderPrefetchByteBudget;

import org.junit.Test;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class ReaderPrefetchByteLimitInterceptorTest {
    @Test public void unknownLengthBodyCannotReadPastSessionBudget() throws Exception {
        ReaderPrefetchByteBudget budget = new ReaderPrefetchByteBudget(4);
        ResponseBody limited = ReaderPrefetchByteLimitInterceptor.limitBody(body("abcdefgh", -1), budget);
        Buffer sink = new Buffer();

        assertEquals(4, limited.source().read(sink, 8192));
        assertEquals("abcd", sink.readUtf8());
        try {
            limited.source().read(sink, 8192);
            fail("Expected the exhausted prefetch budget to stop the stream");
        } catch (IOException expected) {
            assertEquals(4, budget.consumedBytes());
        } finally {
            budget.close();
        }
    }

    @Test public void knownOversizedBodyIsRejectedBeforeReading() throws Exception {
        ReaderPrefetchByteBudget budget = new ReaderPrefetchByteBudget(4);
        try {
            ReaderPrefetchByteLimitInterceptor.limitBody(body("abcdef", 6), budget);
            fail("Expected the known oversized body to be rejected");
        } catch (IOException expected) {
            assertEquals(0, budget.consumedBytes());
        } finally {
            budget.close();
        }
    }

    @Test public void internalBudgetTokenIsRemovedAndCarriedAsRequestTag() {
        ReaderPrefetchByteBudget budget = new ReaderPrefetchByteBudget(4);
        Request request = new Request.Builder().url("https://example.test/image.jpg")
            .header(ReaderPrefetchByteBudget.TOKEN_HEADER, budget.token()).build();

        Request networkRequest = ReaderPrefetchByteLimitInterceptor.requestWithBudgetTag(request, budget);

        assertNull(networkRequest.header(ReaderPrefetchByteBudget.TOKEN_HEADER));
        assertEquals(budget, networkRequest.tag(ReaderPrefetchByteBudget.class));
        assertEquals(request.url(), networkRequest.url());
        budget.close();
    }

    private static ResponseBody body(String value, long contentLength) {
        BufferedSource source = new Buffer().writeUtf8(value);
        return new ResponseBody() {
            @Override public MediaType contentType() { return null; }
            @Override public long contentLength() { return contentLength; }
            @Override public BufferedSource source() { return source; }
        };
    }
}
