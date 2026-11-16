// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class UpdateRequestClientTest {
    @Test
    public void httpsRequestsDoNotFollowProtocolChanges() {
        Request request = new Request.Builder().url("https://example.invalid/releases").build();
        OkHttpClient client = UpdateRequestClient.clientForRequest(new OkHttpClient(), request);
        assertFalse(client.followSslRedirects());
        assertTrue(client.followRedirects());
    }

    @Test
    public void loopbackHttpRedirectDoesNotContactDestination() throws Exception {
        try (ServerSocket origin = new ServerSocket(0); ServerSocket destination = new ServerSocket(0)) {
            origin.setSoTimeout(5000);
            destination.setSoTimeout(1000);
            AtomicReference<Exception> failure = new AtomicReference<>();
            Thread server = new Thread(() -> {
                try (Socket socket = origin.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line;
                    while ((line = input.readLine()) != null && !line.isEmpty()) {
                        // Consume the complete request before closing the socket.
                    }
                    String response = "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:"
                        + destination.getLocalPort() + "/manifest\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                } catch (Exception exception) {
                    failure.set(exception);
                }
            });
            server.start();
            try {
                Request request = new Request.Builder()
                    .url("http://127.0.0.1:" + origin.getLocalPort() + "/releases").build();
                try (Response response = UpdateRequestClient.newCall(new OkHttpClient(), request).execute()) {
                    assertEquals(302, response.code());
                }
                try (Socket ignored = destination.accept()) {
                    fail("Redirect destination was contacted");
                } catch (SocketTimeoutException expected) {
                    // No request should reach the destination.
                }
            } finally {
                server.join(5000L);
            }
            assertFalse(server.isAlive());
            if (failure.get() != null) throw failure.get();
        }
    }
}
