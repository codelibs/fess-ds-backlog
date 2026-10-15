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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.codelibs.fess.entity.DataStoreParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import com.fasterxml.jackson.databind.JsonNode;

public class BacklogClientTest extends UnitDsTestCase {

    private static final String API_KEY = "test-api-key-0001";

    private BacklogApiMockServer server;

    @Override
    public void setUp(final TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        server = new BacklogApiMockServer();
        server.start();
    }

    @Override
    protected void tearDown(final TestInfo testInfo) throws Exception {
        try {
            if (server != null) {
                server.stop();
            }
        } finally {
            super.tearDown(testInfo);
        }
    }

    private BacklogClient newClient() {
        return new BacklogClient(BacklogApiMockServer.newParams(API_KEY));
    }

    private static String issuesJson(final int from, final int to) {
        final StringBuilder buf = new StringBuilder("[");
        for (int i = from; i <= to; i++) {
            if (i > from) {
                buf.append(',');
            }
            buf.append("{\"id\":").append(i).append(",\"issueKey\":\"PROJ-").append(i).append("\"}");
        }
        return buf.append(']').toString();
    }

    private static String commentsJson(final long from, final long to) {
        final StringBuilder buf = new StringBuilder("[");
        for (long i = from; i <= to; i++) {
            if (i > from) {
                buf.append(',');
            }
            buf.append("{\"id\":").append(i).append(",\"content\":\"c").append(i).append("\"}");
        }
        return buf.append(']').toString();
    }

    @Test
    public void test_constructor_requiresApiKey() {
        final DataStoreParams paramMap = BacklogApiMockServer.newParams(API_KEY);
        paramMap.put("api_key", " ");
        try {
            new BacklogClient(paramMap);
            fail("a missing api_key must be rejected");
        } catch (final BacklogDataStoreException e) {
            assertTrue(e.getMessage().contains("api_key"));
        }
    }

    @Test
    public void test_constructor_rejectsMalformedSpaceUrl() {
        for (final String value : new String[] { "", "example.backlog.com", "ftp://example.backlog.com", "http://example.backlog.com",
                "https://example.backlog.com/projects", "https://user:pass@example.backlog.com", "https://example.backlog.com?x=1" }) {
            final DataStoreParams paramMap = BacklogApiMockServer.newParams(API_KEY);
            paramMap.put("space_url", value);
            try {
                new BacklogClient(paramMap);
                fail("space_url must be rejected: " + value);
            } catch (final BacklogDataStoreException e) {
                assertFalse("the rejected value must not be echoed", e.getMessage().contains("pass@"));
            }
        }
    }

    @Test
    public void test_constructor_normalizesTrailingSlash() {
        final DataStoreParams paramMap = BacklogApiMockServer.newParams(API_KEY);
        paramMap.put("space_url", " https://example.backlog.com// ");
        assertEquals("https://example.backlog.com", new BacklogClient(paramMap).getSpaceUrl());
    }

    @Test
    public void test_apiKey_sentAsHeaderOnly() {
        server.enqueue("projects", BacklogApiMockServer.json("[{\"id\":1,\"projectKey\":\"PROJ\"}]"));

        final List<JsonNode> projects = newClient().getProjects(false);

        assertEquals(1, projects.size());
        assertEquals(List.of(API_KEY), server.getApiKeys("projects"));
        assertEquals("false", server.getQueries("projects").get(0).get("archived"));
        for (final String uri : server.getRawUris()) {
            assertFalse("the API key must never be in a URL: " + uri, uri.contains(API_KEY) || uri.contains("apiKey"));
        }
    }

    @Test
    public void test_getProjects_includeArchived_omitsArchivedParam() {
        server.enqueue("projects", BacklogApiMockServer.json("[]"));

        newClient().getProjects(true);

        assertNull("archived must not be sent", server.getQueries("projects").get(0).get("archived"));
    }

    @Test
    public void test_listIssues_offsetPaging() {
        server.enqueue("issues", BacklogApiMockServer.json(issuesJson(1, 100)));
        server.enqueue("issues", BacklogApiMockServer.json(issuesJson(101, 150)));

        final List<String> keys = new ArrayList<>();
        newClient().listIssues(42L, issue -> keys.add(issue.path("issueKey").asText()));

        assertEquals(150, keys.size());
        assertEquals("PROJ-150", keys.get(149));
        final List<Map<String, String>> queries = server.getQueries("issues");
        assertEquals(2, queries.size());
        assertEquals("0", queries.get(0).get("offset"));
        assertEquals("100", queries.get(1).get("offset"));
        assertEquals("100", queries.get(0).get("count"));
        assertEquals("42", queries.get(0).get("projectId[]"));
        assertEquals("created", queries.get(0).get("sort"));
        assertEquals("asc", queries.get(0).get("order"));
    }

    @Test
    public void test_listIssues_exactlyFullLastPage_requestsOneEmptyPage() {
        server.enqueue("issues", BacklogApiMockServer.json(issuesJson(1, 100)));
        server.enqueue("issues", BacklogApiMockServer.json("[]"));

        final List<String> keys = new ArrayList<>();
        newClient().listIssues(1L, issue -> keys.add(issue.path("issueKey").asText()));

        assertEquals(100, keys.size());
        assertEquals(2, server.getRequestCount("issues"));
    }

    @Test
    public void test_listComments_minIdPaging_inclusiveServer() {
        // A server treating minId as inclusive repeats id 100 on the second page; it must be dropped.
        server.enqueue("issues/PROJ-1/comments", BacklogApiMockServer.json(commentsJson(1, 100)));
        server.enqueue("issues/PROJ-1/comments", BacklogApiMockServer.json(commentsJson(100, 120)));

        final List<Long> ids = new ArrayList<>();
        newClient().listComments("PROJ-1", comment -> ids.add(comment.path("id").asLong()));

        assertEquals(120, ids.size());
        assertEquals(120L, ids.get(119).longValue());
        final List<Map<String, String>> queries = server.getQueries("issues/PROJ-1/comments");
        assertNull(queries.get(0).get("minId"));
        assertEquals("100", queries.get(1).get("minId"));
        assertEquals("asc", queries.get(0).get("order"));
        assertEquals("100", queries.get(0).get("count"));
    }

    @Test
    public void test_listComments_minIdPaging_exclusiveServer() {
        server.enqueue("issues/PROJ-1/comments", BacklogApiMockServer.json(commentsJson(1, 100)));
        server.enqueue("issues/PROJ-1/comments", BacklogApiMockServer.json(commentsJson(101, 200)));
        server.enqueue("issues/PROJ-1/comments", BacklogApiMockServer.json("[]"));

        final List<Long> ids = new ArrayList<>();
        newClient().listComments("PROJ-1", comment -> ids.add(comment.path("id").asLong()));

        assertEquals(200, ids.size());
        assertEquals(3, server.getRequestCount("issues/PROJ-1/comments"));
    }

    @Test
    public void test_listSharedFiles_encodesDirectory() {
        server.enqueue("projects/PROJ/files/metadata/specs v1/", BacklogApiMockServer.json("[{\"id\":5,\"type\":\"file\"}]"));

        final List<JsonNode> entries = new ArrayList<>();
        newClient().listSharedFiles("PROJ", "/specs v1/", entries::add);

        assertEquals(1, entries.size());
        assertTrue(server.getRawUris().get(0).startsWith("/api/v2/projects/PROJ/files/metadata/specs%20v1/?"));
    }

    @Test
    public void test_searchRequests_arePacedButReadRequestsAreNot() {
        final DataStoreParams paramMap = BacklogApiMockServer.newParams(API_KEY);
        paramMap.put("search_request_interval", "400");
        final BacklogClient client = new BacklogClient(paramMap);
        server.enqueue("wikis/1", BacklogApiMockServer.json("{\"id\":1}"));
        server.enqueue("wikis/1", BacklogApiMockServer.json("{\"id\":1}"));

        final long readStart = System.nanoTime();
        client.getWiki(1L);
        client.getWiki(1L);
        final long readMillis = (System.nanoTime() - readStart) / 1_000_000L;

        final long searchStart = System.nanoTime();
        client.getWikis("PROJ");
        client.getWikis("PROJ");
        final long searchMillis = (System.nanoTime() - searchStart) / 1_000_000L;

        assertTrue("read-type requests must not wait for search_request_interval: " + readMillis, readMillis < 350L);
        assertTrue("two search-type requests must be >= 400 ms apart: " + searchMillis, searchMillis >= 400L);
    }

    @Test
    public void test_unauthorized_isFatal_withoutRetry() {
        server.enqueue("projects", BacklogApiMockServer.error(401, 11));

        try {
            newClient().getProjects(false);
            fail("401 must throw");
        } catch (final BacklogApiException e) {
            assertTrue(e.isFatal());
            assertEquals(401, e.getStatus());
            assertEquals(11, e.getCode());
        }
        assertEquals(1, server.getRequestCount("projects"));
    }

    @Test
    public void test_requiredMfaCode_isFatalEvenOn403() {
        server.enqueue("projects", BacklogApiMockServer.error(403, 12));

        try {
            newClient().getProjects(false);
            fail("error code 12 must throw");
        } catch (final BacklogApiException e) {
            assertTrue("RequiredMFAError must be fatal", e.isFatal());
        }
    }

    @Test
    public void test_forbiddenAndNotFound_areNotFatal() {
        server.enqueue("wikis/1", BacklogApiMockServer.error(403, 5));
        server.enqueue("wikis/2", BacklogApiMockServer.error(404, 6));
        final BacklogClient client = newClient();

        try {
            client.getWiki(1L);
            fail("403 must throw");
        } catch (final BacklogApiException e) {
            assertFalse(e.isFatal());
            assertEquals(403, e.getStatus());
        }
        try {
            client.getWiki(2L);
            fail("404 must throw");
        } catch (final BacklogApiException e) {
            assertFalse(e.isFatal());
            assertEquals(6, e.getCode());
        }
        assertEquals(1, server.getRequestCount("wikis/1"));
    }

    @Test
    public void test_rateLimited_waitsUntilResetThenRetries() {
        final long pastReset = System.currentTimeMillis() / 1000L - 1L;
        server.enqueue("wikis/1", BacklogApiMockServer.rateLimited(Long.toString(pastReset)));
        server.enqueue("wikis/1", BacklogApiMockServer.json("{\"id\":1,\"name\":\"Home\"}"));

        final long start = System.nanoTime();
        final JsonNode wiki = newClient().getWiki(1L);
        final long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        assertEquals("Home", wiki.path("name").asText());
        assertEquals(2, server.getRequestCount("wikis/1"));
        assertTrue("a reset time in the past must not fall back to the 60 s default: " + elapsedMillis, elapsedMillis < 5_000L);
    }

    @Test
    public void test_rateLimitWaitMillis() {
        final long now = 1_800_000_000_000L;
        assertEquals(30_000L, BacklogClient.rateLimitWaitMillis(Long.toString(now / 1000L + 30L), now));
        assertEquals(1_000L, BacklogClient.rateLimitWaitMillis(Long.toString(now / 1000L - 5L), now));
        assertEquals(120_000L, BacklogClient.rateLimitWaitMillis(Long.toString(now / 1000L + 3600L), now));
        assertEquals(60_000L, BacklogClient.rateLimitWaitMillis(null, now));
        assertEquals(60_000L, BacklogClient.rateLimitWaitMillis("soon", now));
    }

    @Test
    public void test_backoffMillis() {
        assertEquals(1000L, BacklogClient.backoffMillis(1));
        assertEquals(2000L, BacklogClient.backoffMillis(2));
        assertEquals(32000L, BacklogClient.backoffMillis(6));
        assertEquals(60000L, BacklogClient.backoffMillis(7));
        assertEquals(60000L, BacklogClient.backoffMillis(40));
    }

    @Test
    public void test_serverError_retriedThenExhausted_isNotFatal() {
        final DataStoreParams paramMap = BacklogApiMockServer.newParams(API_KEY);
        paramMap.put("max_retry_count", "1");
        server.enqueue("wikis/1", BacklogApiMockServer.error(503, 0));
        server.enqueue("wikis/1", BacklogApiMockServer.error(503, 0));

        try {
            new BacklogClient(paramMap).getWiki(1L);
            fail("exhausted retries must throw");
        } catch (final BacklogApiException e) {
            assertFalse("exhausted retries must not be fatal", e.isFatal());
            assertEquals(503, e.getStatus());
        }
        assertEquals(2, server.getRequestCount("wikis/1"));
    }

    @Test
    public void test_transportError_retriedThenExhausted_isNotFatal() {
        BacklogClient.setEndpoint("http://127.0.0.1:1/api/v2/");
        try {
            final DataStoreParams paramMap = BacklogApiMockServer.newParams(API_KEY);
            paramMap.put("max_retry_count", "1");
            paramMap.put("connection_timeout", "1000");
            paramMap.put("read_timeout", "1000");
            try {
                new BacklogClient(paramMap).getWiki(1L);
                fail("a transport error must throw once retries are exhausted");
            } catch (final BacklogApiException e) {
                assertFalse(e.isFatal());
                assertEquals(0, e.getStatus());
                assertNotNull("the transport failure must stay reachable as the cause", e.getCause());
                assertTrue("the message must name the underlying failure: " + e.getMessage(), e.getMessage().contains("ConnectException"));
            }
        } finally {
            BacklogClient.setEndpoint(server.getEndpoint());
        }
    }

    /** A redirect target on another host name of the same stub server: the key must not reach it. */
    private String otherHostUrl(final String path) {
        return server.getEndpoint().replace("127.0.0.1", "localhost") + path;
    }

    @Test
    public void test_redirect_isNotFollowed_soTheApiKeyNeverLeavesTheSpaceHost() {
        server.enqueue("wikis/1", BacklogApiMockServer.redirect(otherHostUrl("stolen")));

        try {
            newClient().getWiki(1L);
            fail("a redirect must be an error, not followed");
        } catch (final BacklogApiException e) {
            assertFalse(e.isFatal());
            assertEquals(302, e.getStatus());
            assertTrue(e.getMessage(), e.getMessage().contains("redirect"));
        }
        assertEquals(0, server.getRequestCount("stolen"));
    }

    @Test
    public void test_getFile_redirect_isNotFollowed_soTheApiKeyNeverLeavesTheSpaceHost() {
        server.enqueue("issues/PROJ-1/attachments/10", BacklogApiMockServer.redirect(otherHostUrl("stolen")));

        try {
            newClient().getFile("issues/PROJ-1/attachments/10", 100L);
            fail("a redirect must be an error, not followed");
        } catch (final BacklogApiException e) {
            assertFalse(e.isFatal());
            assertEquals(302, e.getStatus());
        }
        assertEquals(0, server.getRequestCount("stolen"));
    }

    @Test
    public void test_listIssues_serverIgnoringOffset_failsInsteadOfLooping() {
        for (int i = 0; i < 3; i++) {
            server.enqueue("issues", BacklogApiMockServer.json(issuesJson(1, 100)));
        }
        final List<JsonNode> seen = new ArrayList<>();

        try {
            newClient().listIssues(1L, seen::add);
            fail("a repeated page means offset is not honored; the listing must fail");
        } catch (final BacklogApiException e) {
            assertFalse(e.isFatal());
        }
        assertEquals("the repeated page must not be delivered twice", 100, seen.size());
    }

    @Test
    public void test_getFile_returnsBytes() {
        server.enqueue("issues/PROJ-1/attachments/10", BacklogApiMockServer.file("hello".getBytes(StandardCharsets.UTF_8)));

        final byte[] content = newClient().getFile("issues/PROJ-1/attachments/10", 100L);

        assertEquals("hello", new String(content, StandardCharsets.UTF_8));
        assertEquals(List.of(API_KEY), server.getApiKeys("issues/PROJ-1/attachments/10"));
    }

    @Test
    public void test_getFile_overDeclaredLength_throwsTooLarge() {
        server.enqueue("issues/PROJ-1/attachments/10", BacklogApiMockServer.file(new byte[200]));

        try {
            newClient().getFile("issues/PROJ-1/attachments/10", 100L);
            fail("a file over the limit must be rejected");
        } catch (final BacklogClient.FileTooLargeException e) {
            assertEquals(200L, e.size());
        }
    }

    @Test
    public void test_getFile_chunkedOverLimit_throwsTooLarge() {
        server.enqueue("issues/PROJ-1/attachments/10", BacklogApiMockServer.chunkedFile(new byte[20000]));

        try {
            newClient().getFile("issues/PROJ-1/attachments/10", 100L);
            fail("a chunked file over the limit must be rejected");
        } catch (final BacklogClient.FileTooLargeException e) {
            assertTrue(e.size() > 100L);
        }
    }

    @Test
    public void test_getFile_forbidden_isNotFatal() {
        server.enqueue("projects/PROJ/files/5", BacklogApiMockServer.error(403, 5));

        try {
            newClient().getFile("projects/PROJ/files/5", 100L);
            fail("403 must throw");
        } catch (final BacklogApiException e) {
            assertFalse(e.isFatal());
            assertEquals(403, e.getStatus());
            assertEquals(5, e.getCode());
        }
    }

    @Test
    public void test_getFile_rateLimited_isRetried() {
        server.enqueue("wikis/1/attachments/2", BacklogApiMockServer.rateLimited(Long.toString(System.currentTimeMillis() / 1000L - 1L)));
        server.enqueue("wikis/1/attachments/2", BacklogApiMockServer.file("ok".getBytes(StandardCharsets.UTF_8)));

        assertEquals("ok", new String(newClient().getFile("wikis/1/attachments/2", 100L), StandardCharsets.UTF_8));
        assertEquals(2, server.getRequestCount("wikis/1/attachments/2"));
    }

    @Test
    public void test_invalidNumericParams_fallBackToDefaults() {
        final DataStoreParams paramMap = BacklogApiMockServer.newParams(API_KEY);
        paramMap.put("request_interval", "-1");
        paramMap.put("search_request_interval", "abc");
        paramMap.put("max_retry_count", "-3");
        paramMap.put("connection_timeout", "0");
        paramMap.put("read_timeout", "99999999999");
        final BacklogClient client = new BacklogClient(paramMap);
        assertEquals(BacklogClient.DEFAULT_REQUEST_INTERVAL, client.requestInterval);
        assertEquals(BacklogClient.DEFAULT_SEARCH_REQUEST_INTERVAL, client.searchRequestInterval);
        assertEquals(BacklogClient.DEFAULT_MAX_RETRY_COUNT, client.maxRetryCount);
        assertEquals(BacklogClient.DEFAULT_CONNECTION_TIMEOUT, client.connectionTimeout);
        assertEquals(BacklogClient.DEFAULT_READ_TIMEOUT, client.readTimeout);
    }

    @Test
    public void test_zeroIntervalsAndRetries_areAccepted() {
        final DataStoreParams paramMap = BacklogApiMockServer.newParams(API_KEY);
        paramMap.put("max_retry_count", "0");
        final BacklogClient client = new BacklogClient(paramMap);
        assertEquals(0L, client.requestInterval);
        assertEquals(0L, client.searchRequestInterval);
        assertEquals(0, client.maxRetryCount);
    }

    private void assertUnparseable(final Runnable call) {
        try {
            call.run();
            fail("an unusable 2xx body must throw");
        } catch (final BacklogApiException e) {
            assertFalse("must not abort the crawl", e.isFatal());
            assertEquals(200, e.getStatus());
            assertTrue(e.getMessage(), e.getMessage().contains("unparseable response"));
        }
    }

    @Test
    public void test_htmlBody_isNonFatalApiException() {
        server.enqueue("projects", BacklogApiMockServer.json("<html>maintenance</html>"));
        server.enqueue("projects/PROJ/users", BacklogApiMockServer.json("<html>maintenance</html>"));

        assertUnparseable(() -> newClient().getProjects(false));
        assertUnparseable(() -> newClient().getProjectUsers("PROJ"));
    }

    @Test
    public void test_htmlBodyOnSecondIssuePage_isNonFatalApiException() {
        server.enqueue("issues", BacklogApiMockServer.json(issuesJson(1, 100)));
        server.enqueue("issues", BacklogApiMockServer.json("<html>maintenance</html>"));

        assertUnparseable(() -> newClient().listIssues(1L, issue -> {}));
    }

    @Test
    public void test_emptyBody_isNotTreatedAsEmptyList() {
        server.enqueue("issues", BacklogApiMockServer.json(""));
        server.enqueue("wikis", BacklogApiMockServer.json("  \n"));

        assertUnparseable(() -> newClient().listIssues(1L, issue -> {}));
        assertUnparseable(() -> newClient().getWikis("PROJ"));
    }

    @Test
    public void test_wrongShape_isNonFatalApiException() {
        server.enqueue("projects", BacklogApiMockServer.json("{\"errors\":[]}"));
        server.enqueue("wikis/1", BacklogApiMockServer.json("[]"));
        server.enqueue("issues/PROJ-1/comments", BacklogApiMockServer.json("null"));

        assertUnparseable(() -> newClient().getProjects(false));
        assertUnparseable(() -> newClient().getWiki(1L));
        assertUnparseable(() -> newClient().listComments("PROJ-1", c -> {}));
    }
}
