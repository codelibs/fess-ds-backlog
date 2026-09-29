/*
 * Copyright 2012-2025 CodeLibs Project and the Others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.codelibs.fess.ds.backlog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.codelibs.fess.entity.DataStoreParams;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A local stand-in for the Backlog API v2, backed by the JDK's built-in HTTP server. Responses
 * are queued per decoded path (e.g. {@code /api/v2/issues/PROJ-1/comments}) and consumed in
 * order; query strings and the {@code Backlog-API-Key} header are recorded but not matched on.
 *
 * <p>
 * A path with no queued response answers {@code []}, which satisfies every list endpoint. Object
 * endpoints ({@code GET /wikis/:id}) and downloads must always be enqueued explicitly. {@link
 * #setStrict(boolean)} makes an unscripted request answer HTTP 400 instead.
 * </p>
 */
public class BacklogApiMockServer implements AutoCloseable {

    /** The API context path, matching {@code space_url + "/api/v2/"}. */
    static final String API_PREFIX = "/api/v2/";

    private HttpServer server;

    private volatile boolean strict;

    private final Map<String, List<MockResponse>> queued = new ConcurrentHashMap<>();

    private final Map<String, List<Map<String, String>>> receivedQueries = new ConcurrentHashMap<>();

    private final Map<String, List<String>> receivedApiKeys = new ConcurrentHashMap<>();

    private final List<String> receivedRawUris = Collections.synchronizedList(new ArrayList<>());

    /** A canned HTTP response. {@code chunked} omits {@code Content-Length}. */
    public record MockResponse(int statusCode, byte[] body, String contentType, Map<String, String> headers, boolean chunked) {
    }

    /**
     * Builds an HTTP 200 JSON response.
     *
     * @param body the JSON body
     * @return the response
     */
    public static MockResponse json(final String body) {
        return new MockResponse(200, body.getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8", Map.of(), false);
    }

    /**
     * Builds a Backlog-shaped error response: {@code {"errors":[{"message","code","moreInfo"}]}}.
     *
     * @param status the HTTP status code
     * @param code the Backlog error code
     * @return the response
     */
    public static MockResponse error(final int status, final int code) {
        final String body = "{\"errors\":[{\"message\":\"error " + code + "\",\"code\":" + code + ",\"moreInfo\":\"\"}]}";
        return new MockResponse(status, body.getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8", Map.of(), false);
    }

    /**
     * Builds an HTTP 429 (error code 13) carrying {@code X-RateLimit-Reset}.
     *
     * @param resetEpochSeconds the header value; {@code null} omits the header
     * @return the response
     */
    public static MockResponse rateLimited(final String resetEpochSeconds) {
        final String body = "{\"errors\":[{\"message\":\"Too many requests\",\"code\":13,\"moreInfo\":\"\"}]}";
        final Map<String, String> headers = resetEpochSeconds == null ? Map.of() : Map.of("X-RateLimit-Reset", resetEpochSeconds);
        return new MockResponse(429, body.getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8", headers, false);
    }

    /**
     * Builds an HTTP 302 redirect.
     *
     * @param location the {@code Location} header value
     * @return the response
     */
    public static MockResponse redirect(final String location) {
        return new MockResponse(302, new byte[0], "text/plain", Map.of("Location", location), false);
    }

    /**
     * Builds a binary download response with {@code Content-Length}.
     *
     * @param content the file content
     * @return the response
     */
    public static MockResponse file(final byte[] content) {
        return new MockResponse(200, content, "application/octet-stream", Map.of(), false);
    }

    /**
     * Builds a binary download response without {@code Content-Length} (chunked).
     *
     * @param content the file content
     * @return the response
     */
    public static MockResponse chunkedFile(final byte[] content) {
        return new MockResponse(200, content, "application/octet-stream", Map.of(), true);
    }

    /**
     * Starts the server on an ephemeral port and points {@link BacklogClient} at it.
     *
     * @throws IOException if the server cannot bind
     */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(API_PREFIX, this::handle);
        server.setExecutor(null);
        server.start();
        BacklogClient.setEndpoint(getEndpoint());
    }

    /**
     * Stops the server and restores the production endpoint. Safe to call more than once.
     */
    public void stop() {
        BacklogClient.resetEndpoint();
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Returns the API base URL this server listens on.
     *
     * @return the endpoint, ending with {@code /api/v2/}
     */
    public String getEndpoint() {
        final HttpServer current = server;
        if (current == null) {
            throw new IllegalStateException("BacklogApiMockServer is not running");
        }
        return "http://127.0.0.1:" + current.getAddress().getPort() + API_PREFIX;
    }

    /**
     * Builds the parameters for a client against this server: {@code space_url} is the
     * documentation host (the endpoint override routes API calls here) and both pacing intervals
     * are 0.
     *
     * @param apiKey the API key
     * @return the parameters
     */
    public static DataStoreParams newParams(final String apiKey) {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("api_key", apiKey);
        paramMap.put("space_url", "https://example.backlog.com");
        paramMap.put("request_interval", "0");
        paramMap.put("search_request_interval", "0");
        return paramMap;
    }

    /**
     * Queues one response for a path relative to {@code /api/v2/}, e.g. {@code "projects"}.
     *
     * @param path the path without the {@code /api/v2/} prefix, decoded
     * @param response the response
     */
    public void enqueue(final String path, final MockResponse response) {
        queued.computeIfAbsent(API_PREFIX + path, k -> Collections.synchronizedList(new ArrayList<>())).add(response);
    }

    /**
     * Returns how many times a path was requested.
     *
     * @param path the path without the {@code /api/v2/} prefix
     * @return the request count
     */
    public int getRequestCount(final String path) {
        return getQueries(path).size();
    }

    /**
     * Returns the decoded query parameters of each request to a path, in arrival order.
     *
     * @param path the path without the {@code /api/v2/} prefix
     * @return the query maps
     */
    public List<Map<String, String>> getQueries(final String path) {
        return snapshot(receivedQueries, API_PREFIX + path);
    }

    /**
     * Returns the {@code Backlog-API-Key} header of each request to a path.
     *
     * @param path the path without the {@code /api/v2/} prefix
     * @return the header values ({@code null} where absent)
     */
    public List<String> getApiKeys(final String path) {
        return snapshot(receivedApiKeys, API_PREFIX + path);
    }

    /**
     * Returns every raw request URI (path and query, undecoded) received so far.
     *
     * @return the raw URIs, in arrival order
     */
    public List<String> getRawUris() {
        synchronized (receivedRawUris) {
            return new ArrayList<>(receivedRawUris);
        }
    }

    /**
     * Asserts that every queued response was consumed.
     */
    public void assertAllConsumed() {
        final StringBuilder message = new StringBuilder();
        queued.forEach((path, responses) -> {
            synchronized (responses) {
                if (!responses.isEmpty()) {
                    message.append(path).append(" (").append(responses.size()).append(" left) ");
                }
            }
        });
        if (message.length() > 0) {
            throw new AssertionError("Unconsumed queued responses: " + message);
        }
    }

    /**
     * Sets whether an unscripted request answers HTTP 400 instead of {@code []}.
     *
     * @param strict whether unscripted requests fail loudly
     */
    public void setStrict(final boolean strict) {
        this.strict = strict;
    }

    private <T> List<T> snapshot(final Map<String, List<T>> store, final String path) {
        final List<T> values = store.get(path);
        if (values == null) {
            return Collections.emptyList();
        }
        synchronized (values) {
            return new ArrayList<>(values);
        }
    }

    private void handle(final HttpExchange exchange) throws IOException {
        final String path = exchange.getRequestURI().getPath();
        receivedRawUris.add(exchange.getRequestURI().getRawPath()
                + (exchange.getRequestURI().getRawQuery() != null ? "?" + exchange.getRequestURI().getRawQuery() : ""));
        receivedQueries.computeIfAbsent(path, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(parseQuery(exchange.getRequestURI().getRawQuery()));
        receivedApiKeys.computeIfAbsent(path, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(exchange.getRequestHeaders().getFirst("Backlog-API-Key"));
        drain(exchange.getRequestBody());

        MockResponse response = null;
        final List<MockResponse> responses = queued.get(path);
        if (responses != null) {
            synchronized (responses) {
                if (!responses.isEmpty()) {
                    response = responses.remove(0);
                }
            }
        }
        if (response == null) {
            response = strict ? error(400, 7) : json("[]");
        }
        response.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        exchange.getResponseHeaders().add("Content-Type", response.contentType());
        exchange.sendResponseHeaders(response.statusCode(), response.chunked() ? 0 : response.body().length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response.body());
        }
    }

    private void drain(final InputStream in) throws IOException {
        final ByteArrayOutputStream sink = new ByteArrayOutputStream();
        in.transferTo(sink);
    }

    private Map<String, String> parseQuery(final String rawQuery) {
        final Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }
        for (final String pair : rawQuery.split("&")) {
            final int eq = pair.indexOf('=');
            if (eq < 0) {
                params.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
            } else {
                params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }
}
