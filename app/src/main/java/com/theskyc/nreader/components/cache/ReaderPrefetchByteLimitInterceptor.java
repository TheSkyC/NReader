// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.cache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.theskyc.nreader.components.reader.ReaderPrefetchByteBudget;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import okio.Source;

final class ReaderPrefetchByteLimitInterceptor implements Interceptor {
    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        String token = request.header(ReaderPrefetchByteBudget.TOKEN_HEADER);
        ReaderPrefetchByteBudget budget = request.tag(ReaderPrefetchByteBudget.class);
        if (budget == null && token == null) return chain.proceed(request);

        if (budget == null) budget = ReaderPrefetchByteBudget.find(token);
        if (budget == null) throw new IOException("Reader prefetch session has ended");

        Request networkRequest = requestWithBudgetTag(request, budget);
        Response response = chain.proceed(networkRequest);
        ResponseBody body = response.body();
        if (body == null) return response;

        try {
            return response.newBuilder().body(limitBody(body, budget)).build();
        } catch (IOException error) {
            response.close();
            throw error;
        }
    }

    static ResponseBody limitBody(ResponseBody body, ReaderPrefetchByteBudget budget) throws IOException {
        long contentLength = body.contentLength();
        if (contentLength >= 0 && contentLength > budget.remainingBytes())
            throw new IOException("Reader prefetch byte budget exceeded");
        return new LimitedBody(body, budget, contentLength);
    }

    static Request requestWithBudgetTag(Request request, ReaderPrefetchByteBudget budget) {
        return request.newBuilder()
            .tag(ReaderPrefetchByteBudget.class, budget)
            .removeHeader(ReaderPrefetchByteBudget.TOKEN_HEADER)
            .build();
    }

    private static final class LimitedBody extends ResponseBody {
        private final ResponseBody body;
        private final ReaderPrefetchByteBudget budget;
        private final long expectedLength;
        private final BufferedSource source;
        private long bytesRead;

        LimitedBody(ResponseBody body, ReaderPrefetchByteBudget budget, long expectedLength) {
            this.body = body;
            this.budget = budget;
            this.expectedLength = expectedLength;
            Source limitedSource = new ForwardingSource(body.source()) {
                @Override
                public long read(@NonNull Buffer sink, long byteCount) throws IOException {
                    if (byteCount == 0) return 0;
                    synchronized (LimitedBody.this.budget) {
                        if (LimitedBody.this.expectedLength >= 0
                            && LimitedBody.this.bytesRead >= LimitedBody.this.expectedLength) return -1;
                        long remaining = LimitedBody.this.budget.remainingBytes();
                        if (remaining <= 0) throw new IOException("Reader prefetch byte budget exceeded");
                        long requested = Math.min(byteCount, remaining);
                        if (LimitedBody.this.expectedLength >= 0) {
                            requested = Math.min(requested,
                                LimitedBody.this.expectedLength - LimitedBody.this.bytesRead);
                        }
                        long read = super.read(sink, requested);
                        if (read > 0) {
                            LimitedBody.this.bytesRead += read;
                            LimitedBody.this.budget.recordConsumed(read);
                        }
                        return read;
                    }
                }
            };
            source = Okio.buffer(limitedSource);
        }

        @Nullable
        @Override
        public MediaType contentType() {
            return body.contentType();
        }

        @Override
        public long contentLength() {
            return expectedLength;
        }

        @NonNull
        @Override
        public BufferedSource source() {
            return source;
        }
    }
}
