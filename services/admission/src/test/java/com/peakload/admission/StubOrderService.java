package com.peakload.admission;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * A scripted stand-in for the order service, on a random local port. It answers with whatever reply is set for
 * "METHOD /path" and records every request it receives, so tests can check what admission sent upstream.
 * Built on the JDK HTTP server, so no extra test dependency is needed.
 */
final class StubOrderService implements AutoCloseable {

    /** One scripted answer. delayMillis makes it slow, to test timeouts. */
    record Reply(int status, Map<String, String> headers, String body, long delayMillis) {
        static Reply of(int status, String body) {
            return new Reply(status, Map.of(), body, 0);
        }
    }

    /** A request as the stub received it. */
    record Received(String method, String path, Map<String, String> headers, String body) {}

    private final HttpServer server;
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    /** Answers used in order, one per call, before the fixed answer in {@link #replies} applies. */
    private final Map<String, java.util.Deque<Reply>> sequences = new ConcurrentHashMap<>();

    /** Status for "close the connection without answering": the peer drops the connection. */
    static final int ABORT = -1;
    private final List<Received> received = new CopyOnWriteArrayList<>();

    StubOrderService() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        // A pool, not the default single thread: a deliberately slow reply must not block the other requests.
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            String body = readBody(exchange.getRequestBody());
            Map<String, String> headers = new java.util.HashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, values.get(0)));
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers, body));

            Reply reply = nextReply(key);
            if (reply.status() == ABORT) {
                exchange.close();
                return;
            }
            if (reply.delayMillis() > 0) {
                sleep(reply.delayMillis());
            }
            reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    /** Base URL, for example http://127.0.0.1:43123. */
    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The answers for "METHOD /path" in order, one per call; after they run out, the fixed answer applies. */
    void replySequence(String method, String path, Reply... answers) {
        sequences.put(method + " " + path, new java.util.concurrent.ConcurrentLinkedDeque<>(java.util.List.of(answers)));
    }

    private Reply nextReply(String key) {
        java.util.Deque<Reply> queue = sequences.get(key);
        if (queue != null) {
            Reply next = queue.pollFirst();
            if (next != null) {
                return next;
            }
        }
        return Optional.ofNullable(replies.get(key)).orElse(Reply.of(404, "{\"error\":\"stub_no_reply\"}"));
    }

    /** Sets the answer for "METHOD /path". Replaces any earlier answer for that key. */
    void reply(String method, String path, Reply reply) {
        replies.put(method + " " + path, reply);
    }

    List<Received> received() {
        return List.copyOf(received);
    }

    void clearReceived() {
        received.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static String readBody(InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
