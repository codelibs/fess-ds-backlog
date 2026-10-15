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
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.core.exception.InterruptedRuntimeException;
import org.codelibs.core.lang.StringUtil;
import org.codelibs.curl.Curl;
import org.codelibs.curl.CurlException;
import org.codelibs.curl.CurlRequest;
import org.codelibs.curl.CurlResponse;
import org.codelibs.fess.entity.DataStoreParams;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

/**
 * A thin HTTP client for the subset of the Backlog API v2 this data store needs: API key header
 * authentication, offset and {@code minId} pagination, request pacing (general and Search-type),
 * retry, and size-capped file downloads.
 *
 * <p>
 * The API key is sent only in the {@code Backlog-API-Key} header, never as the {@code apiKey}
 * query parameter, so it never appears in a request URL, an exception message, or a log line.
 * </p>
 *
 * <p>
 * {@link #setEndpoint(String)} overrides the API base URL process-wide, for tests to point this
 * class at a local mock server while {@code space_url} keeps producing realistic web URLs.
 * Production code must never call it, and a test must reset it (see {@link #resetEndpoint()}).
 * </p>
 */
public class BacklogClient implements Closeable {

    private static final Logger logger = LogManager.getLogger(BacklogClient.class);

    /** Default milliseconds to sleep before each API request. */
    public static final long DEFAULT_REQUEST_INTERVAL = 200L;

    /** Default minimum milliseconds between two Search-type requests (issue list/count, wiki list/count). */
    public static final long DEFAULT_SEARCH_REQUEST_INTERVAL = 1000L;

    /** Default maximum number of retries for a 429, 5xx or transport failure. */
    public static final int DEFAULT_MAX_RETRY_COUNT = 5;

    /** Default connection timeout in milliseconds. */
    public static final int DEFAULT_CONNECTION_TIMEOUT = 10000;

    /** Default read timeout in milliseconds. */
    public static final int DEFAULT_READ_TIMEOUT = 30000;

    /** Page size for every paginated call; the maximum Backlog allows. */
    public static final int PAGE_SIZE = 100;

    /** Parameter name for the Backlog API key. */
    public static final String API_KEY_PARAM = "api_key";

    /** Parameter name for the space URL, e.g. {@code https://example.backlog.com}. */
    public static final String SPACE_URL_PARAM = "space_url";

    /** Parameter name for the pacing delay applied before each request. */
    protected static final String REQUEST_INTERVAL_PARAM = "request_interval";

    /** Parameter name for the minimum delay between Search-type requests. */
    protected static final String SEARCH_REQUEST_INTERVAL_PARAM = "search_request_interval";

    /** Parameter name for the maximum number of retries. */
    protected static final String MAX_RETRY_COUNT_PARAM = "max_retry_count";

    /** Parameter name for the connection timeout. */
    protected static final String CONNECTION_TIMEOUT_PARAM = "connection_timeout";

    /** Parameter name for the read timeout. */
    protected static final String READ_TIMEOUT_PARAM = "read_timeout";

    /** Header carrying the API key. */
    protected static final String API_KEY_HEADER = "Backlog-API-Key";

    /** Header carrying the UTC epoch second at which the rate limit resets. */
    protected static final String RATE_LIMIT_RESET_HEADER = "X-RateLimit-Reset";

    /** Backlog error code: AuthenticationError. */
    protected static final int ERROR_AUTHENTICATION = 11;

    /** Backlog error code: RequiredMFAError. */
    protected static final int ERROR_REQUIRED_MFA = 12;

    /** Backlog error code: TooManyRequestsError. */
    protected static final int ERROR_TOO_MANY_REQUESTS = 13;

    /** Base wait of the exponential backoff for 5xx and transport failures. */
    protected static final long RETRY_BASE_MILLIS = 1000L;

    /** Upper bound of the exponential backoff. */
    protected static final long MAX_BACKOFF_MILLIS = 60000L;

    /** Wait used for a 429 whose {@code X-RateLimit-Reset} is missing or unparseable. */
    protected static final long DEFAULT_RATE_LIMIT_WAIT_MILLIS = 60000L;

    /** Lower bound of the wait for a 429, so a reset time already in the past (clock skew) does not burn the retries at once. */
    protected static final long MIN_RATE_LIMIT_WAIT_MILLIS = 1000L;

    /** Upper bound of the wait for a 429. */
    protected static final long MAX_RATE_LIMIT_WAIT_MILLIS = 120000L;

    /** Upper bound on the bytes read from an error response body of a download. */
    protected static final long MAX_ERROR_BODY_BYTES = 65536L;

    /** The API base URL override for tests; {@code null} means {@code space_url + "/api/v2/"}. */
    private static volatile String endpointOverride;

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Overrides the API base URL. Intended for tests against a local stub server only, hence
     * package-private: no other code in the JVM may redirect the calls, API key included.
     *
     * @param endpoint the endpoint URL, which must end with a slash
     * @throws IllegalArgumentException if {@code endpoint} is {@code null} or does not end with a slash
     */
    static void setEndpoint(final String endpoint) {
        if (endpoint == null || !endpoint.endsWith("/")) {
            throw new IllegalArgumentException("endpoint must be non-null and end with '/': " + endpoint);
        }
        endpointOverride = endpoint;
    }

    /**
     * Restores the API base URL to {@code space_url + "/api/v2/"}.
     */
    static void resetEndpoint() {
        endpointOverride = null;
    }

    /** The API key. */
    protected final String apiKey;

    /** The normalized space URL, without a trailing slash. */
    protected final String spaceUrl;

    /** Milliseconds to sleep before each request's first attempt. */
    protected final long requestInterval;

    /** Minimum milliseconds between the starts of two Search-type requests. */
    protected final long searchRequestInterval;

    /** Maximum number of retries. */
    protected final int maxRetryCount;

    /** Connection timeout in milliseconds. */
    protected final int connectionTimeout;

    /** Read timeout in milliseconds. */
    protected final int readTimeout;

    /** {@link System#nanoTime()} at the start of the previous Search-type request, or {@code null} before the first. */
    private Long lastSearchNanos;

    /**
     * Creates a new client from the data store's configuration parameters.
     *
     * @param paramMap the configuration parameters
     * @throws BacklogDataStoreException if {@code api_key} is missing or {@code space_url} is missing or malformed
     */
    public BacklogClient(final DataStoreParams paramMap) {
        apiKey = paramMap.getAsString(API_KEY_PARAM, StringUtil.EMPTY).trim();
        if (apiKey.isEmpty()) {
            throw new BacklogDataStoreException("Parameter " + API_KEY_PARAM + " is required.");
        }
        spaceUrl = normalizeSpaceUrl(paramMap.getAsString(SPACE_URL_PARAM));
        requestInterval = getNonNegativeLongParam(paramMap, REQUEST_INTERVAL_PARAM, DEFAULT_REQUEST_INTERVAL);
        searchRequestInterval = getNonNegativeLongParam(paramMap, SEARCH_REQUEST_INTERVAL_PARAM, DEFAULT_SEARCH_REQUEST_INTERVAL);
        maxRetryCount = getNonNegativeIntParam(paramMap, MAX_RETRY_COUNT_PARAM, DEFAULT_MAX_RETRY_COUNT);
        connectionTimeout = getPositiveIntParam(paramMap, CONNECTION_TIMEOUT_PARAM, DEFAULT_CONNECTION_TIMEOUT);
        readTimeout = getPositiveIntParam(paramMap, READ_TIMEOUT_PARAM, DEFAULT_READ_TIMEOUT);
    }

    /**
     * Validates and normalizes {@code space_url}: an {@code https} URL with a host and nothing
     * after it but optional trailing slashes. {@code http} is refused because every request
     * carries the API key. The value itself is not echoed in the exception message, since a
     * malformed value may carry credentials.
     *
     * @param value the raw parameter value
     * @return the URL without a trailing slash
     * @throws BacklogDataStoreException if the value is blank or malformed
     */
    protected static String normalizeSpaceUrl(final String value) {
        if (StringUtil.isBlank(value)) {
            throw new BacklogDataStoreException("Parameter " + SPACE_URL_PARAM + " is required.");
        }
        String url = value.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        final URI uri;
        try {
            uri = new URI(url);
        } catch (final URISyntaxException e) {
            throw new BacklogDataStoreException("Parameter " + SPACE_URL_PARAM + " is not a valid URL.", e);
        }
        final String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) || StringUtil.isBlank(uri.getHost()) || StringUtil.isNotEmpty(uri.getRawPath())
                || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            throw new BacklogDataStoreException(
                    "Parameter " + SPACE_URL_PARAM + " must be an https URL with only a host, e.g. https://example.backlog.com");
        }
        return url;
    }

    /**
     * Returns the normalized space URL, without a trailing slash; the base of every web URL.
     *
     * @return the space URL
     */
    public String getSpaceUrl() {
        return spaceUrl;
    }

    /**
     * Returns the API base URL in effect, always ending with a slash.
     *
     * @return the API base URL
     */
    protected String getApiBase() {
        final String override = endpointOverride;
        return override != null ? override : spaceUrl + "/api/v2/";
    }

    /**
     * Lists the projects visible to the API key's user ({@code GET /projects}).
     *
     * @param includeArchived whether archived projects are included
     * @return the project objects
     */
    public List<JsonNode> getProjects(final boolean includeArchived) {
        final Map<String, String> query = new LinkedHashMap<>();
        if (!includeArchived) {
            query.put("archived", "false");
        }
        return toList(getJson("projects", query, false, true));
    }

    /**
     * Lists the members of a project ({@code GET /projects/:key/users}, unpaginated).
     *
     * @param projectKey the project key
     * @return the user objects
     */
    public List<JsonNode> getProjectUsers(final String projectKey) {
        return toList(getJson("projects/" + encodeSegment(projectKey) + "/users", Map.of(), false, true));
    }

    /**
     * Pages through every issue of a project in creation order ({@code GET /issues}, Search-type).
     *
     * @param projectId the numeric project id
     * @param consumer called once per issue, in order
     */
    public void listIssues(final long projectId, final Consumer<JsonNode> consumer) {
        final Map<String, String> query = new LinkedHashMap<>();
        query.put("projectId[]", Long.toString(projectId));
        query.put("sort", "created");
        query.put("order", "asc");
        paginateOffset("issues", query, true, consumer);
    }

    /**
     * Pages through every comment of an issue in ascending id order ({@code GET
     * /issues/:key/comments}), using {@code minId} paging.
     *
     * <p>
     * Whether {@code minId} is inclusive is not documented, so the next page is requested with
     * {@code minId} equal to the last id seen and any id not greater than it is dropped: correct
     * either way, at the cost of at most one extra request.
     * </p>
     *
     * @param issueKey the issue key, e.g. {@code PROJ-1}
     * @param consumer called once per comment, in order
     */
    public void listComments(final String issueKey, final Consumer<JsonNode> consumer) {
        final String path = "issues/" + encodeSegment(issueKey) + "/comments";
        long lastId = -1L;
        while (true) {
            final Map<String, String> query = new LinkedHashMap<>();
            query.put("count", Integer.toString(PAGE_SIZE));
            query.put("order", "asc");
            if (lastId >= 0) {
                query.put("minId", Long.toString(lastId));
            }
            final JsonNode page = getJson(path, query, false, true);
            boolean advanced = false;
            for (final JsonNode comment : page) {
                final long id = comment.path("id").asLong(-1L);
                if (id <= lastId) {
                    continue;
                }
                consumer.accept(comment);
                lastId = id;
                advanced = true;
            }
            if (!advanced || page.size() < PAGE_SIZE) {
                return;
            }
        }
    }

    /**
     * Lists every Wiki page of a project, without content ({@code GET /wikis}, Search-type,
     * unpaginated).
     *
     * @param projectKey the project key
     * @return the Wiki page summaries
     */
    public List<JsonNode> getWikis(final String projectKey) {
        return toList(getJson("wikis", Map.of("projectIdOrKey", projectKey), true, true));
    }

    /**
     * Retrieves one Wiki page with its content, attachments and tags ({@code GET /wikis/:id}).
     *
     * @param wikiId the Wiki page id
     * @return the Wiki page
     */
    public JsonNode getWiki(final long wikiId) {
        return getJson("wikis/" + wikiId, Map.of(), false, false);
    }

    /**
     * Pages through every Document of a project ({@code GET /documents}).
     *
     * @param projectId the numeric project id
     * @param consumer called once per document, in order
     */
    public void listDocuments(final long projectId, final Consumer<JsonNode> consumer) {
        paginateOffset("documents", Map.of("projectId[]", Long.toString(projectId)), false, consumer);
    }

    /**
     * Pages through the direct children of one shared-file directory ({@code GET
     * /projects/:key/files/metadata/:path}); not recursive.
     *
     * @param projectKey the project key
     * @param directory the directory, starting and ending with {@code /} ({@code /} for the root)
     * @param consumer called once per file or directory entry
     */
    public void listSharedFiles(final String projectKey, final String directory, final Consumer<JsonNode> consumer) {
        paginateOffset("projects/" + encodeSegment(projectKey) + "/files/metadata" + encodePath(directory), Map.of(), false, consumer);
    }

    /**
     * Thrown by {@link #getFile(String, long)} when a download is abandoned for exceeding the
     * size limit. Carries only the observed size.
     */
    public static final class FileTooLargeException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** The observed size in bytes. */
        private final long size;

        FileTooLargeException(final long size) {
            super("File exceeds the configured max_file_size (observed " + size + " bytes).");
            this.size = size;
        }

        /**
         * Returns the observed size: the declared {@code Content-Length}, or the bytes read before
         * the cap was exceeded.
         *
         * @return the observed size in bytes
         */
        public long size() {
            return size;
        }
    }

    /**
     * Downloads a file from an API path, e.g. {@code issues/PROJ-1/attachments/10}, stopping as
     * soon as it is known to exceed {@code maxFileSize}. Retries and error classification are the
     * same as for JSON calls.
     *
     * @param path the API path relative to {@code /api/v2/}
     * @param maxFileSize the maximum number of bytes to download
     * @return the file content
     * @throws FileTooLargeException if the file exceeds {@code maxFileSize}
     * @throws BacklogApiException for a non-2xx response or exhausted retries
     */
    public byte[] getFile(final String path, final long maxFileSize) {
        return request(path, Map.of(), false, maxFileSize);
    }

    /**
     * Releases this client. Every call opens and closes its own response, so this is a no-op kept
     * for try-with-resources.
     */
    @Override
    public void close() {
        // nothing to release
    }

    /**
     * Encodes one URL path segment (UTF-8, space as {@code %20}).
     *
     * @param segment the raw segment
     * @return the encoded segment
     */
    public static String encodeSegment(final String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Encodes each segment of a {@code /}-separated path, keeping the separators, including a
     * leading and trailing one.
     *
     * @param path the raw path, e.g. {@code /docs/spec v1/}
     * @return the encoded path, e.g. {@code /docs/spec%20v1/}
     */
    public static String encodePath(final String path) {
        final StringBuilder buf = new StringBuilder();
        int start = 0;
        for (int i = 0; i <= path.length(); i++) {
            if (i == path.length() || path.charAt(i) == '/') {
                if (i > start) {
                    buf.append(encodeSegment(path.substring(start, i)));
                }
                if (i < path.length()) {
                    buf.append('/');
                }
                start = i + 1;
            }
        }
        return buf.toString();
    }

    private void paginateOffset(final String path, final Map<String, String> baseQuery, final boolean search,
            final Consumer<JsonNode> consumer) {
        int offset = 0;
        JsonNode previousFirst = null;
        while (true) {
            final Map<String, String> query = new LinkedHashMap<>(baseQuery);
            query.put("offset", Integer.toString(offset));
            query.put("count", Integer.toString(PAGE_SIZE));
            final JsonNode page = getJson(path, query, search, true);
            if (page.size() > 0 && page.get(0).equals(previousFirst)) {
                // A server or proxy that ignores offset would otherwise be paged forever.
                throw new BacklogApiException(path, 200, 0, "the same page was returned twice; offset is not honored", false);
            }
            page.forEach(consumer);
            if (page.size() < PAGE_SIZE) {
                return;
            }
            previousFirst = page.get(0);
            offset += page.size();
        }
    }

    private List<JsonNode> toList(final JsonNode array) {
        final List<JsonNode> list = new ArrayList<>();
        array.forEach(list::add);
        return list;
    }

    /**
     * Fetches and parses a JSON response, requiring an array or an object as the caller expects.
     * A body that is not parseable (an HTML maintenance page, say), is empty, or has the wrong
     * shape would otherwise read as an empty list, so it becomes a non-fatal
     * {@link BacklogApiException} recorded per item like any other failed call.
     */
    private JsonNode getJson(final String path, final Map<String, String> query, final boolean search, final boolean array) {
        final byte[] body = request(path, query, search, -1L);
        final JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (final IOException e) {
            throw unparseable(path, "not valid JSON (" + body.length + " bytes)");
        }
        if (root == null || !(array ? root.isArray() : root.isObject())) {
            throw unparseable(path, "not a JSON " + (array ? "array" : "object") + " (" + body.length + " bytes)");
        }
        return root;
    }

    private BacklogApiException unparseable(final String path, final String detail) {
        return new BacklogApiException(path, 200, 0, "unparseable response: " + detail, false);
    }

    /** One HTTP exchange's outcome. */
    private record RawResponse(int status, byte[] body, String rateLimitReset) {
    }

    /**
     * Sends a GET request, retrying 429 (until {@code X-RateLimit-Reset}), 5xx and transport
     * failures (exponential backoff), and classifies the final failure.
     *
     * @param maxFileSize the download cap, or a negative value for a JSON call
     */
    private byte[] request(final String path, final Map<String, String> query, final boolean search, final long maxFileSize) {
        for (int attempt = 0;; attempt++) {
            if (attempt == 0) {
                pace(search);
            }
            final RawResponse response;
            try {
                response = maxFileSize < 0 ? sendJson(path, query) : sendFile(path, maxFileSize);
            } catch (final CurlException e) {
                for (Throwable t = e; t != null; t = t.getCause()) {
                    if (t instanceof final FileTooLargeException tooLarge) {
                        throw tooLarge;
                    }
                }
                if (attempt < maxRetryCount) {
                    final long waitMillis = backoffMillis(attempt + 1);
                    logger.warn("Retrying Backlog API request '{}' (attempt {}) after a transport error: {}; waiting {} ms.", path,
                            attempt + 1, describe(e), waitMillis);
                    sleep(waitMillis);
                    continue;
                }
                logger.warn("Exhausted {} retries on Backlog API request '{}' after transport errors.", maxRetryCount, path);
                throw new BacklogApiException(path, 0, 0, "transport error: " + describe(e), false, e);
            }
            final int status = response.status();
            if (status >= 200 && status < 300) {
                return response.body();
            }
            final JsonNode error = firstError(response.body());
            final int code = error.path("code").asInt(0);
            final String message = status >= 300 && status < 400 ? "unexpected redirect (not followed, so the API key stays on this host)"
                    : error.hasNonNull("message") ? error.path("message").asText() : "(no error message)";
            if (status == 401 || code == ERROR_AUTHENTICATION || code == ERROR_REQUIRED_MFA) {
                throw new BacklogApiException(path, status, code, message, true);
            }
            final boolean rateLimited = status == 429 || code == ERROR_TOO_MANY_REQUESTS;
            if (rateLimited || status >= 500 && status < 600) {
                if (attempt < maxRetryCount) {
                    final long waitMillis = rateLimited ? rateLimitWaitMillis(response.rateLimitReset(), System.currentTimeMillis())
                            : backoffMillis(attempt + 1);
                    logger.warn("Retrying Backlog API request '{}' (attempt {}) after HTTP {}; waiting {} ms.", path, attempt + 1, status,
                            waitMillis);
                    sleep(waitMillis);
                    continue;
                }
                logger.warn("Exhausted {} retries on Backlog API request '{}'; the last response was HTTP {}.", maxRetryCount, path,
                        status);
            }
            throw new BacklogApiException(path, status, code, message, false);
        }
    }

    private RawResponse sendJson(final String path, final Map<String, String> query) {
        final CurlRequest request = Curl.get(getApiBase() + path).header(API_KEY_HEADER, apiKey).timeout(connectionTimeout, readTimeout);
        // The JDK would forward Backlog-API-Key to the host a 3xx points at; only Authorization and Cookie are stripped.
        request.onConnect((req, con) -> con.setInstanceFollowRedirects(false));
        query.forEach(request::param);
        try (CurlResponse response = request.execute()) {
            return new RawResponse(response.getHttpStatusCode(), response.getContentAsString().getBytes(StandardCharsets.UTF_8),
                    response.getHeaderValue(RATE_LIMIT_RESET_HEADER));
        } catch (final IOException e) {
            throw new BacklogApiException(path, 0, 0, "unparseable response: failed to close the response", false);
        }
    }

    /**
     * Downloads via {@link CurlRequest#onConnect} so the size cap applies before curl4j would read
     * the whole body itself (curl4j reads the whole body of a normal response into memory, so a cap enforced afterwards would be too late).
     */
    private RawResponse sendFile(final String path, final long maxFileSize) {
        final RawResponse[] holder = new RawResponse[1];
        final CurlRequest request = Curl.get(getApiBase() + path).header(API_KEY_HEADER, apiKey).timeout(connectionTimeout, readTimeout);
        request.onConnect((req, con) -> {
            con.setInstanceFollowRedirects(false);
            try {
                final int status = con.getResponseCode();
                final boolean success = status >= 200 && status < 300;
                if (success && con.getContentLengthLong() > maxFileSize) {
                    throw new FileTooLargeException(con.getContentLengthLong());
                }
                final InputStream body = status >= 400 ? con.getErrorStream() : con.getInputStream();
                // Not closed here: curl4j's own response processing reads and closes it afterwards.
                final byte[] content = body == null ? new byte[0] : readCapped(body, success ? maxFileSize : MAX_ERROR_BODY_BYTES, success);
                holder[0] = new RawResponse(status, content, con.getHeaderField(RATE_LIMIT_RESET_HEADER));
            } catch (final IOException e) {
                throw new CurlException("Failed to read a Backlog file response.", e);
            }
        });
        CurlResponse response = null;
        try {
            response = request.execute();
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (final IOException e) {
                    // the content was already read in onConnect; nothing useful left to report
                }
            }
        }
        return holder[0];
    }

    private byte[] readCapped(final InputStream in, final long limit, final boolean failOnOverflow) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > limit) {
                if (failOnOverflow) {
                    throw new FileTooLargeException(total);
                }
                break;
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * Names the innermost cause of a transport failure. curl4j wraps every failure in a generic
     * "Failed to process a request." exception, which says nothing about a mistyped host, a TLS
     * problem or a refused connection. The messages involved carry a host name at most, never
     * the API key, which travels in a header.
     */
    private static String describe(final Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + (root.getMessage() != null ? ": " + root.getMessage() : "");
    }

    private JsonNode firstError(final byte[] body) {
        try {
            final JsonNode root = mapper.readTree(body);
            return root == null ? MissingNode.getInstance() : root.path("errors").path(0);
        } catch (final IOException e) {
            return MissingNode.getInstance();
        }
    }

    /**
     * Computes the wait before retrying a 429: until {@code X-RateLimit-Reset} (UTC epoch
     * seconds), {@link #DEFAULT_RATE_LIMIT_WAIT_MILLIS} when the header is missing or
     * unparseable, at least {@link #MIN_RATE_LIMIT_WAIT_MILLIS}, capped at {@link #MAX_RATE_LIMIT_WAIT_MILLIS}.
     *
     * @param resetHeader the raw header value, may be {@code null}
     * @param nowMillis the current time in epoch milliseconds
     * @return the wait in milliseconds
     */
    protected static long rateLimitWaitMillis(final String resetHeader, final long nowMillis) {
        long waitMillis = DEFAULT_RATE_LIMIT_WAIT_MILLIS;
        if (StringUtil.isNotBlank(resetHeader)) {
            try {
                waitMillis = Math.max(MIN_RATE_LIMIT_WAIT_MILLIS, Long.parseLong(resetHeader.trim()) * 1000L - nowMillis);
            } catch (final NumberFormatException e) {
                // fall back to the default wait
            }
        }
        return Math.min(waitMillis, MAX_RATE_LIMIT_WAIT_MILLIS);
    }

    /**
     * The exponential backoff for a 5xx or transport retry: {@code 1000ms << (attempt - 1)},
     * capped at {@link #MAX_BACKOFF_MILLIS}.
     *
     * @param attempt the 1-based retry attempt
     * @return the wait in milliseconds
     */
    protected static long backoffMillis(final int attempt) {
        final int shift = Math.min(Math.max(attempt - 1, 0), 20);
        return Math.min(RETRY_BASE_MILLIS << shift, MAX_BACKOFF_MILLIS);
    }

    private void pace(final boolean search) {
        if (requestInterval > 0) {
            sleep(requestInterval);
        }
        if (search) {
            if (lastSearchNanos != null && searchRequestInterval > 0) {
                final long elapsedMillis = (System.nanoTime() - lastSearchNanos) / 1_000_000L;
                if (elapsedMillis < searchRequestInterval) {
                    sleep(searchRequestInterval - elapsedMillis);
                }
            }
            lastSearchNanos = System.nanoTime();
        }
    }

    private void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedRuntimeException(e);
        }
    }

    private long getLongParam(final DataStoreParams paramMap, final String paramName, final long defaultValue) {
        final String value = paramMap.getAsString(paramName);
        if (StringUtil.isBlank(value)) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (final NumberFormatException e) {
            logger.warn("Parameter '{}' is not a number: {}. Falling back to {}.", paramName, value, defaultValue);
            return defaultValue;
        }
    }

    private int getNonNegativeIntParam(final DataStoreParams paramMap, final String paramName, final int defaultValue) {
        final long value = getLongParam(paramMap, paramName, defaultValue);
        if (value < 0 || value > Integer.MAX_VALUE) {
            logger.warn("Parameter '{}' is out of range: {}. Falling back to {}.", paramName, value, defaultValue);
            return defaultValue;
        }
        return (int) value;
    }

    private long getNonNegativeLongParam(final DataStoreParams paramMap, final String paramName, final long defaultValue) {
        final long value = getLongParam(paramMap, paramName, defaultValue);
        if (value < 0) {
            logger.warn("Parameter '{}' must not be negative: {}. Falling back to {}.", paramName, value, defaultValue);
            return defaultValue;
        }
        return value;
    }

    private int getPositiveIntParam(final DataStoreParams paramMap, final String paramName, final int defaultValue) {
        final long value = getLongParam(paramMap, paramName, defaultValue);
        if (value <= 0 || value > Integer.MAX_VALUE) {
            logger.warn("Parameter '{}' must be a positive int: {}. Falling back to {}.", paramName, value, defaultValue);
            return defaultValue;
        }
        return (int) value;
    }

}
