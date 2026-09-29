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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.codelibs.core.exception.InterruptedRuntimeException;
import org.codelibs.fess.entity.DataStoreParams;
import org.codelibs.fess.mylasta.direction.FessConfig;
import org.codelibs.fess.opensearch.config.exentity.DataConfig;
import org.codelibs.fess.util.ComponentUtil;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link BacklogDataStore#storeData} for projects, roles, issues, comments, issue
 * attachments, failure handling and parameters.
 */
public class BacklogDataStoreTest extends BacklogDataStoreTestBase {

    private static final String ISSUE_1 = """
            {"id":101,"issueKey":"PROJ-1","summary":"Login fails","description":"Steps to reproduce the login failure.",
             "issueType":{"id":1,"name":"Bug"},"status":{"id":1,"name":"Open"},"priority":{"id":2,"name":"High"},
             "resolution":null,"assignee":{"id":1,"name":"Alice"},
             "category":[{"id":1,"name":"Auth"}],"versions":[{"id":1,"name":"1.0"}],"milestone":[{"id":2,"name":"Sprint 5"}],
             "customFields":[{"id":1,"name":"Severity","value":{"id":3,"name":"Major"}}],
             "attachments":[],"created":"2026-01-01T00:00:00Z","updated":"2026-01-02T00:00:00Z",
             "createdUser":{"id":1,"name":"Alice"},"updatedUser":{"id":2,"name":"Bob"}}
            """;

    private static String issueJson(final int number, final String attachmentsJson) {
        return "{\"id\":" + (100 + number) + ",\"issueKey\":\"PROJ-" + number + "\",\"summary\":\"Issue " + number
                + "\",\"description\":\"Body " + number + "\",\"attachments\":" + attachmentsJson + "}";
    }

    @Test
    public void test_issue_indexedWithAttributesCustomFieldsCommentsAndRoles() {
        enqueueProject(false, false, false);
        server.enqueue("issues", BacklogApiMockServer.json("[" + ISSUE_1 + "]"));
        server.enqueue("issues/PROJ-1/comments", BacklogApiMockServer.json("""
                [{"id":1,"content":"Reproduced on staging.","createdUser":{"name":"Bob"}},
                 {"id":2,"content":null,"createdUser":{"name":"Bob"}}]
                """));

        final List<Map<String, Object>> docs = crawl(newParamMap());

        assertEquals(1, docs.size());
        final Map<String, Object> doc = docs.get(0);
        assertEquals("issue", doc.get("type"));
        assertEquals("[PROJ-1] Login fails", doc.get("title"));
        assertEquals(SPACE + "/view/PROJ-1", doc.get("url"));
        assertEquals("text/plain", doc.get("mimetype"));
        assertEquals("PROJ", doc.get("project_key"));
        assertEquals("PROJ Project", doc.get("project_name"));
        assertEquals("2026-01-01T00:00:00Z", doc.get("created"));
        assertEquals("2026-01-02T00:00:00Z", doc.get("updated"));
        assertEquals("Alice", doc.get("created_by"));
        assertEquals("Bob", doc.get("updated_by"));
        assertEquals("PROJ-1", doc.get("issue_key"));
        assertEquals("Bug", doc.get("issue_type"));
        assertEquals("Open", doc.get("status"));
        assertEquals("High", doc.get("priority"));
        assertEquals("Alice", doc.get("assignee"));
        assertEquals(List.of("Auth"), doc.get("categories"));
        assertEquals(List.of("Sprint 5"), doc.get("milestones"));
        assertEquals(List.of("1.0"), doc.get("versions"));
        assertEquals(Map.of("Severity", "Major"), doc.get("custom_fields"));
        assertEquals("Bob: Reproduced on staging.", doc.get("comments"));
        final String content = (String) doc.get("content");
        assertTrue(content, content.startsWith("Steps to reproduce the login failure."));
        assertTrue(content, content.contains("Status: Open"));
        assertTrue(content, content.contains("Severity: Major"));
        assertTrue(content, content.contains("Bob: Reproduced on staging."));
        assertEquals(List.of(userRole("alice@example.com"), userRole("alice")), doc.get("roles"));
        assertEquals(List.of(API_KEY), server.getApiKeys("projects"));
    }

    @Test
    public void test_roles_skipBlankIdentifiers() {
        server.enqueue("projects", BacklogApiMockServer.json("[" + projectJson(1, "PROJ", false, false, false) + "]"));
        server.enqueue("projects/PROJ/users", BacklogApiMockServer
                .json("[" + userJson(1, "bob", "") + "," + userJson(2, null, null) + "," + userJson(3, " ", "carol@example.com") + "]"));
        server.enqueue("issues", BacklogApiMockServer.json("[" + issueJson(1, "[]") + "]"));

        final List<Map<String, Object>> docs = crawl(newParamMap());

        assertEquals(1, docs.size());
        assertEquals(List.of(userRole("bob"), userRole("carol@example.com")), docs.get(0).get("roles"));
    }

    @Test
    public void test_roles_memberNamedGuest_neverGrantsTheGuestRole() {
        server.enqueue("projects", BacklogApiMockServer.json("[" + projectJson(1, "PROJ", false, false, false) + "]"));
        server.enqueue("projects/PROJ/users",
                BacklogApiMockServer.json("[" + userJson(1, "alice", null) + "," + userJson(2, "guest", "bob@example.com") + "]"));
        server.enqueue("issues", BacklogApiMockServer.json("[" + issueJson(1, "[]") + "]"));

        final List<Map<String, Object>> docs = crawl(newParamMap());

        assertEquals(List.of(userRole("alice"), userRole("bob@example.com")), docs.get(0).get("roles"));
    }

    @Test
    public void test_roles_identifierWithBackslash_isSkipped_soItCannotImpersonateAnotherLogin() {
        // Fess strips everything up to a backslash from a user name ("x\carol" becomes "carol").
        server.enqueue("projects", BacklogApiMockServer.json("[" + projectJson(1, "PROJ", false, false, false) + "]"));
        server.enqueue("projects/PROJ/users",
                BacklogApiMockServer.json("[" + userJson(1, "alice", null) + "," + userJson(2, "x\\\\carol", "x\\\\guest") + "]"));
        server.enqueue("issues", BacklogApiMockServer.json("[" + issueJson(1, "[]") + "]"));

        final List<Map<String, Object>> docs = crawl(newParamMap());

        assertEquals(List.of(userRole("alice")), docs.get(0).get("roles"));
    }

    @Test
    public void test_rolesMerge_defaultPermissionsAndDataConfigRoles() {
        enqueueProject(false, false, false);
        server.enqueue("issues", BacklogApiMockServer.json("[" + issueJson(1, "[]") + "]"));
        final FessConfig fessConfig = ComponentUtil.getFessConfig();
        final String adminRole = fessConfig.getRoleSearchRolePrefix() + "admin";
        final Map<String, Object> defaultDataMap = new HashMap<>();
        defaultDataMap.put(fessConfig.getIndexFieldRole(), new ArrayList<>(List.of(adminRole)));
        final DataStoreParams paramMap = newParamMap();
        paramMap.put("default_permissions", "{group}dev, ,{user}alice");

        final List<Map<String, Object>> docs = crawl(paramMap, defaultDataMap);

        assertEquals(List.of(userRole("alice@example.com"), userRole("alice"), fessConfig.getRoleSearchGroupPrefix() + "dev", adminRole),
                docs.get(0).get("roles"));
    }

    @Test
    public void test_permissionSyncFalse_usesOnlyDefaultPermissions_andNeverFetchesMembers() {
        server.enqueue("projects", BacklogApiMockServer.json("[" + projectJson(1, "PROJ", false, false, false) + "]"));
        server.enqueue("issues", BacklogApiMockServer.json("[" + issueJson(1, "[]") + "]"));
        final DataStoreParams paramMap = newParamMap();
        paramMap.put("permission_sync", "false");
        paramMap.put("default_permissions", "{group}dev");

        final List<Map<String, Object>> docs = crawl(paramMap);

        assertEquals(0, server.getRequestCount("projects/PROJ/users"));
        assertEquals(List.of(ComponentUtil.getFessConfig().getRoleSearchGroupPrefix() + "dev"), docs.get(0).get("roles"));
    }

    @Test
    public void test_membersFetchFailure_skipsProject_failClosed() {
        server.enqueue("projects", BacklogApiMockServer
                .json("[" + projectJson(1, "PROJ", false, false, false) + "," + projectJson(2, "OPEN", false, false, false) + "]"));
        server.enqueue("projects/PROJ/users", BacklogApiMockServer.error(403, 5));
        server.enqueue("projects/OPEN/users", BacklogApiMockServer.json("[" + userJson(1, "alice", null) + "]"));
        server.enqueue("issues", BacklogApiMockServer.json("[{\"id\":201,\"issueKey\":\"OPEN-1\",\"summary\":\"Other\"}]"));

        final List<Map<String, Object>> docs = crawl(newParamMap());

        assertEquals("only the second project is crawled", 1, docs.size());
        assertEquals("[OPEN-1] Other", docs.get(0).get("title"));
        assertEquals("issues of the skipped project must never be listed", "2", server.getQueries("issues").get(0).get("projectId[]"));
        assertEquals(1, server.getRequestCount("issues"));
        assertEquals(1, recordedFailures.size());
        assertEquals(SPACE + "/projects/PROJ", recordedFailures.get(0).url());
    }

    @Test
    public void test_noResolvableMember_skipsProject_evenWithDefaultPermissions() {
        server.enqueue("projects", BacklogApiMockServer.json("[" + projectJson(1, "PROJ", false, false, false) + "]"));
        server.enqueue("projects/PROJ/users", BacklogApiMockServer.json("[" + userJson(1, null, " ") + "]"));
        final DataStoreParams paramMap = newParamMap();
        paramMap.put("default_permissions", "{role}guest");

        final List<Map<String, Object>> docs = crawl(paramMap);

        assertEquals(0, docs.size());
        assertEquals(0, server.getRequestCount("issues"));
        assertEquals(1, recordedFailures.size());
        assertEquals(SPACE + "/projects/PROJ", recordedFailures.get(0).url());
    }

    @Test
    public void test_projectKeys_filterIsCaseInsensitive() {
        server.enqueue("projects", BacklogApiMockServer
                .json("[" + projectJson(1, "PROJ", false, false, false) + "," + projectJson(2, "OTHER", false, false, false) + "]"));
        server.enqueue("projects/OTHER/users", BacklogApiMockServer.json("[" + userJson(1, "alice", null) + "]"));
        final DataStoreParams paramMap = newParamMap();
        paramMap.put("project_keys", " other , MISSING");

        crawl(paramMap);

        assertEquals(0, server.getRequestCount("projects/PROJ/users"));
        assertEquals(1, server.getRequestCount("projects/OTHER/users"));
        assertEquals("2", server.getQueries("issues").get(0).get("projectId[]"));
    }

    @Test
    public void test_includeArchived_default_sendsArchivedFalse() {
        crawl(newParamMap());
        assertEquals("false", server.getQueries("projects").get(0).get("archived"));

        final DataStoreParams paramMap = newParamMap();
        paramMap.put("include_archived", "true");
        crawl(paramMap);
        assertNull(server.getQueries("projects").get(1).get("archived"));
    }

    @Test
    public void test_includeIssuesFalse_neverListsIssues() {
        enqueueProject(false, false, false);
        final DataStoreParams paramMap = newParamMap();
        paramMap.put("include_issues", "false");

        crawl(paramMap);

        assertEquals(0, server.getRequestCount("issues"));
    }

    @Test
    public void test_includeCommentsFalse_neverListsComments() {
        enqueueProject(false, false, false);
        server.enqueue("issues", BacklogApiMockServer.json("[" + issueJson(1, "[]") + "]"));
        final DataStoreParams paramMap = newParamMap();
        paramMap.put("include_comments", "false");

        final List<Map<String, Object>> docs = crawl(paramMap);

        assertEquals(1, docs.size());
        assertEquals(0, server.getRequestCount("issues/PROJ-1/comments"));
    }

    @Test
    public void test_commentsFailure_indexesIssueWithoutComments_andRecordsFailure() {
        enqueueProject(false, false, false);
        server.enqueue("issues", BacklogApiMockServer.json("[" + issueJson(1, "[]") + "]"));
        server.enqueue("issues/PROJ-1/comments", BacklogApiMockServer.error(404, 6));

        final List<Map<String, Object>> docs = crawl(newParamMap());

        assertEquals(1, docs.size());
        assertEquals("", docs.get(0).get("comments"));
        assertEquals(1, recordedFailures.size());
        assertEquals(SPACE + "/view/PROJ-1", recordedFailures.get(0).url());
    }

    @Test
    public void test_issuesListingFailure_isRecordedAgainstProject_andCrawlContinues() {
        enqueueProject(false, false, false);
        server.enqueue("issues", BacklogApiMockServer.error(500, 0));
        final DataStoreParams paramMap = newParamMap();
        paramMap.put("max_retry_count", "0");

        final List<Map<String, Object>> docs = crawl(paramMap);

        assertEquals(0, docs.size());
        assertEquals(1, recordedFailures.size());
        assertEquals(SPACE + "/projects/PROJ", recordedFailures.get(0).url());
    }

    @Test
    public void test_includePattern_appliesToIssueUrl() {
        enqueueProject(false, false, false);
        server.enqueue("issues", BacklogApiMockServer.json("[" + issueJson(1, "[]") + "," + issueJson(2, "[]") + "]"));
        final DataStoreParams paramMap = newParamMap();
        paramMap.put("include_pattern", ".*/view/PROJ-2");

        final List<Map<String, Object>> docs = crawl(paramMap);

        assertEquals(1, docs.size());
        assertEquals(SPACE + "/view/PROJ-2", docs.get(0).get("url"));
        assertEquals("comments of a filtered-out issue must not be fetched", 0, server.getRequestCount("issues/PROJ-1/comments"));
    }

    @Test
    public void test_issueAttachment_downloadedAndExtracted() throws Exception {
        registerRealExtraction();
        enqueueProject(false, false, false);
        server.enqueue("issues", BacklogApiMockServer.json("[" + issueJson(1, "[{\"id\":10,\"name\":\"notes.txt\",\"size\":40}]") + "]"));
        server.enqueue("issues/PROJ-1/attachments/10",
                BacklogApiMockServer.file("Attachment text for extraction.".getBytes(StandardCharsets.UTF_8)));

        final List<Map<String, Object>> docs = crawl(newParamMap());

        assertEquals(2, docs.size());
        final Map<String, Object> attachment = ofType(docs, "attachment").get(0);
        assertEquals("notes.txt", attachment.get("title"));
        assertEquals(SPACE + "/view/PROJ-1#attachment-10", attachment.get("url"));
        assertEquals("issue", attachment.get("parent_type"));
        assertEquals(SPACE + "/view/PROJ-1", attachment.get("parent_url"));
        assertEquals("[PROJ-1] Issue 1", attachment.get("parent_title"));
        assertEquals("text/plain", attachment.get("mimetype"));
        assertEquals(31L, attachment.get("size"));
        assertTrue((String) attachment.get("content"), ((String) attachment.get("content")).contains("Attachment text for extraction."));
        assertEquals(docs.get(0).get("roles"), attachment.get("roles"));
    }

    @Test
    public void test_includeAttachmentsFalse_neverDownloads() {
        enqueueProject(false, false, false);
        server.enqueue("issues", BacklogApiMockServer.json("[" + issueJson(1, "[{\"id\":10,\"name\":\"notes.txt\",\"size\":40}]") + "]"));
        final DataStoreParams paramMap = newParamMap();
        paramMap.put("include_attachments", "false");

        final List<Map<String, Object>> docs = crawl(paramMap);

        assertEquals(1, docs.size());
        assertEquals(0, server.getRequestCount("issues/PROJ-1/attachments/10"));
    }

    @Test
    public void test_attachmentOverMaxFileSize_isSkippedWithoutDownload() throws Exception {
        registerRealExtraction();
        enqueueProject(false, false, false);
        server.enqueue("issues", BacklogApiMockServer.json("[" + issueJson(1, "[{\"id\":10,\"name\":\"big.bin\",\"size\":5000}]") + "]"));
        final DataStoreParams paramMap = newParamMap();
        paramMap.put("max_file_size", "1000");

        final List<Map<String, Object>> docs = crawl(paramMap);

        assertEquals(1, docs.size());
        assertEquals(0, server.getRequestCount("issues/PROJ-1/attachments/10"));
        assertEquals(0, recordedFailures.size());
    }

    @Test
    public void test_attachmentForbidden_skipsIssueAttachmentsForProject_withOneWarn() throws Exception {
        registerRealExtraction();
        enqueueProject(false, false, false);
        server.enqueue("issues", BacklogApiMockServer.json("[" + issueJson(1, "[{\"id\":10,\"name\":\"a.txt\",\"size\":1}]") + ","
                + issueJson(2, "[{\"id\":20,\"name\":\"b.txt\",\"size\":1}]") + "]"));
        server.enqueue("issues/PROJ-1/attachments/10", BacklogApiMockServer.error(403, 5));

        final TestLogAppender log = TestLogAppender.attachTo(BacklogDataStore.class);
        try {
            final List<Map<String, Object>> docs = crawl(newParamMap());

            assertEquals("both issues are still indexed", 2, ofType(docs, "issue").size());
            assertEquals(0, ofType(docs, "attachment").size());
            assertEquals("no further download once the feature is disabled", 0, server.getRequestCount("issues/PROJ-2/attachments/20"));
            assertEquals("a feature skip is not a per-item failure", 0, recordedFailures.size());
            assertEquals(1L, log.messagesAt(Level.WARN).stream().filter(m -> m.contains("issue attachments")).count());
        } finally {
            log.detach();
        }
    }

    @Test
    public void test_attachmentNotFound_isRecordedAgainstAttachmentUrl() throws Exception {
        registerRealExtraction();
        enqueueProject(false, false, false);
        server.enqueue("issues", BacklogApiMockServer.json("[" + issueJson(1, "[{\"id\":10,\"name\":\"a.txt\",\"size\":1}]") + "]"));
        server.enqueue("issues/PROJ-1/attachments/10", BacklogApiMockServer.error(404, 6));

        final List<Map<String, Object>> docs = crawl(newParamMap());

        assertEquals(1, docs.size());
        assertEquals(1, recordedFailures.size());
        assertEquals(SPACE + "/view/PROJ-1#attachment-10", recordedFailures.get(0).url());
    }

    @Test
    public void test_unauthorized_abortsCrawl() {
        enqueueProject(false, false, false);
        server.enqueue("issues", BacklogApiMockServer.error(401, 11));

        try {
            crawl(newParamMap());
            fail("a 401 must abort the crawl");
        } catch (final BacklogApiException e) {
            assertTrue(e.isFatal());
        }
        assertEquals(0, recordedFailures.size());
    }

    @Test
    public void test_unauthorizedOnMembers_abortsCrawl_notFailClosedSkip() {
        server.enqueue("projects", BacklogApiMockServer.json("[" + projectJson(1, "PROJ", false, false, false) + "]"));
        server.enqueue("projects/PROJ/users", BacklogApiMockServer.error(401, 11));

        try {
            crawl(newParamMap());
            fail("a 401 while fetching members must abort the crawl");
        } catch (final BacklogApiException e) {
            assertTrue(e.isFatal());
        }
    }

    @Test
    public void test_missingSpaceUrl_isConfigurationError() {
        final DataStoreParams paramMap = newParamMap();
        paramMap.put("space_url", "");
        try {
            crawl(paramMap);
            fail("a missing space_url must abort");
        } catch (final BacklogDataStoreException e) {
            assertTrue(e.getMessage().contains("space_url"));
        }
        assertEquals(0, server.getRequestCount("projects"));
    }

    @Test
    public void test_stop_haltsCrawlMidListing() {
        enqueueProject(false, false, false);
        server.enqueue("issues", BacklogApiMockServer.json("[" + issueJson(1, "[]") + "," + issueJson(2, "[]") + "]"));

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback() {
            @Override
            public void store(final DataStoreParams p, final Map<String, Object> dataMap) {
                super.store(p, dataMap);
                dataStore.stop();
            }
        };
        dataStore.storeData(new DataConfig(), callback, newParamMap(), scriptMap(), new HashMap<>());

        assertEquals(1, callback.size());
        assertEquals(0, server.getRequestCount("issues/PROJ-2/comments"));
    }

    @Test
    public void test_interrupt_propagatesRatherThanRecordedAsFailure() {
        final DataStoreParams paramMap = newParamMap();
        paramMap.put("request_interval", "50");

        Thread.currentThread().interrupt();
        try {
            try {
                crawl(paramMap);
                fail("an interrupt during pacing must propagate");
            } catch (final InterruptedRuntimeException e) {
                // expected
            }
            assertEquals(0, recordedFailures.size());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void test_apiKey_neverInScriptMapFailuresOrLogs() throws Exception {
        registerRealExtraction();
        enqueueProject(false, false, false);
        server.enqueue("issues", BacklogApiMockServer.json("[" + issueJson(1, "[{\"id\":10,\"name\":\"a.txt\",\"size\":1}]") + "]"));
        server.enqueue("issues/PROJ-1/comments", BacklogApiMockServer.error(404, 6));
        server.enqueue("issues/PROJ-1/attachments/10", BacklogApiMockServer.error(500, 0));
        final DataStoreParams paramMap = newParamMap();
        paramMap.put("max_retry_count", "0");
        final Map<String, String> scriptMap = scriptMap();
        scriptMap.put("leak", "api_key");

        final TestLogAppender storeLog = TestLogAppender.attachTo(BacklogDataStore.class);
        final TestLogAppender clientLog = TestLogAppender.attachTo(BacklogClient.class);
        try {
            final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
            dataStore.storeData(new DataConfig(), callback, paramMap, scriptMap, new HashMap<>());

            assertEquals(1, callback.size());
            for (final Map<String, Object> doc : callback.getDataMaps()) {
                assertNull("api_key must not be reachable from scripts", doc.get("leak"));
                assertFalse(doc.values().stream().anyMatch(v -> String.valueOf(v).contains(API_KEY)));
            }
            assertEquals(2, recordedFailures.size());
            for (final FailureRecord failure : recordedFailures) {
                assertFalse(failure.url().contains(API_KEY));
                assertFalse(containsInChain(failure.throwable(), API_KEY));
            }
            assertFalse(storeLog.containsAnywhere(API_KEY));
            assertFalse(clientLog.containsAnywhere(API_KEY));
        } finally {
            storeLog.detach();
            clientLog.detach();
        }
    }

    @Test
    public void test_booleanParam_garbageFallsBackToDefault() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("include_comments", "maybe");
        paramMap.put("include_archived", "FALSE");
        assertTrue(dataStore.getBooleanParam(paramMap, "include_comments", true));
        assertFalse(dataStore.getBooleanParam(paramMap, "include_archived", true));
    }

    @Test
    public void test_maxFileSize_invalidFallsBackToDefault() {
        for (final String value : new String[] { "0", "-5", "ten" }) {
            final DataStoreParams paramMap = new DataStoreParams();
            paramMap.put("max_file_size", value);
            assertEquals(BacklogDataStore.DEFAULT_MAX_FILE_SIZE, dataStore.getMaxFileSize(paramMap));
        }
    }

    @Test
    public void test_unparseableMembers_skipsProject_andOtherProjectsStillCrawl() {
        server.enqueue("projects", BacklogApiMockServer
                .json("[" + projectJson(1, "PROJ", false, false, false) + "," + projectJson(2, "OPEN", false, false, false) + "]"));
        server.enqueue("projects/PROJ/users", BacklogApiMockServer.json("<html>maintenance</html>"));
        server.enqueue("projects/OPEN/users", BacklogApiMockServer.json("[" + userJson(1, "alice", null) + "]"));
        server.enqueue("issues", BacklogApiMockServer.json("[{\"id\":201,\"issueKey\":\"OPEN-1\",\"summary\":\"Other\"}]"));

        final List<Map<String, Object>> docs = crawl(newParamMap());

        assertEquals(1, docs.size());
        assertEquals("[OPEN-1] Other", docs.get(0).get("title"));
        assertEquals(1, recordedFailures.size());
        assertEquals(SPACE + "/projects/PROJ", recordedFailures.get(0).url());
    }

    @Test
    public void test_unparseableIssueListing_isRecordedAgainstProject_andOtherProjectsStillCrawl() {
        server.enqueue("projects", BacklogApiMockServer
                .json("[" + projectJson(1, "PROJ", false, false, false) + "," + projectJson(2, "OPEN", false, false, false) + "]"));
        server.enqueue("projects/PROJ/users", BacklogApiMockServer.json("[" + userJson(1, "alice", null) + "]"));
        server.enqueue("projects/OPEN/users", BacklogApiMockServer.json("[" + userJson(1, "alice", null) + "]"));
        server.enqueue("issues", BacklogApiMockServer.json("<html>maintenance</html>"));
        server.enqueue("issues", BacklogApiMockServer.json("[{\"id\":201,\"issueKey\":\"OPEN-1\",\"summary\":\"Other\"}]"));

        final List<Map<String, Object>> docs = crawl(newParamMap());

        assertEquals(1, docs.size());
        assertEquals(1, recordedFailures.size());
        assertEquals(SPACE + "/projects/PROJ", recordedFailures.get(0).url());
    }

    @Test
    public void test_emptyIssueListingBody_isRecordedAsFailure_notSilentEmptyList() {
        enqueueProject(false, false, false);
        server.enqueue("issues", BacklogApiMockServer.json(""));

        final List<Map<String, Object>> docs = crawl(newParamMap());

        assertEquals(0, docs.size());
        assertEquals(1, recordedFailures.size());
        assertEquals(SPACE + "/projects/PROJ", recordedFailures.get(0).url());
    }

    @Test
    public void test_unparseableComments_indexesIssueWithoutComments_andRecordsFailure() {
        enqueueProject(false, false, false);
        server.enqueue("issues", BacklogApiMockServer.json("[" + issueJson(1, "[]") + "]"));
        server.enqueue("issues/PROJ-1/comments", BacklogApiMockServer.json("<html>maintenance</html>"));

        final List<Map<String, Object>> docs = crawl(newParamMap());

        assertEquals(1, docs.size());
        assertEquals(1, recordedFailures.size());
        assertEquals(SPACE + "/view/PROJ-1", recordedFailures.get(0).url());
    }

    @Test
    public void test_unparseableProjectList_abortsCrawl() {
        server.enqueue("projects", BacklogApiMockServer.json("<html>maintenance</html>"));

        try {
            crawl(newParamMap());
            fail("a failure to list projects must abort the crawl");
        } catch (final BacklogApiException e) {
            assertFalse(e.isFatal());
        }
    }

    @Test
    public void test_stop_skipsRemainingAttachmentDownloads() {
        enqueueProject(false, false, false);
        server.enqueue("issues", BacklogApiMockServer
                .json("[" + issueJson(1, "[{\"id\":10,\"name\":\"a.txt\",\"size\":3},{\"id\":11,\"name\":\"b.txt\",\"size\":3}]") + "]"));
        server.enqueue("issues/PROJ-1/attachments/10", BacklogApiMockServer.file("abc".getBytes(StandardCharsets.UTF_8)));
        server.enqueue("issues/PROJ-1/attachments/11", BacklogApiMockServer.file("abc".getBytes(StandardCharsets.UTF_8)));

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback() {
            @Override
            public void store(final DataStoreParams p, final Map<String, Object> dataMap) {
                super.store(p, dataMap);
                dataStore.stop();
            }
        };
        dataStore.storeData(new DataConfig(), callback, newParamMap(), scriptMap(), new HashMap<>());

        assertEquals("only the issue itself was stored", 1, callback.size());
        assertEquals(0, server.getRequestCount("issues/PROJ-1/attachments/10"));
        assertEquals(0, server.getRequestCount("issues/PROJ-1/attachments/11"));
    }

    @Test
    public void test_guestRoleInDataConfig_warnsOncePerProject_butStillIndexes() {
        server.enqueue("projects", BacklogApiMockServer
                .json("[" + projectJson(1, "PROJ", false, false, false) + "," + projectJson(2, "OPEN", false, false, false) + "]"));
        server.enqueue("projects/PROJ/users", BacklogApiMockServer.json("[" + userJson(1, "alice", null) + "]"));
        server.enqueue("projects/OPEN/users", BacklogApiMockServer.json("[" + userJson(1, "alice", null) + "]"));
        server.enqueue("issues", BacklogApiMockServer.json("[" + issueJson(1, "[]") + "]"));
        final FessConfig fessConfig = ComponentUtil.getFessConfig();
        final String guestRole = fessConfig.getRoleSearchRolePrefix() + "guest";
        final Map<String, Object> defaultDataMap = new HashMap<>();
        defaultDataMap.put(fessConfig.getIndexFieldRole(), new ArrayList<>(List.of(guestRole)));

        final TestLogAppender log = TestLogAppender.attachTo(BacklogDataStore.class);
        try {
            final List<Map<String, Object>> docs = crawl(newParamMap(), defaultDataMap);

            assertTrue("the documents are still indexed", docs.size() > 0);
            assertTrue(docs.get(0).get("roles").toString(), ((List<?>) docs.get(0).get("roles")).contains(guestRole));
            for (final String key : new String[] { "PROJ", "OPEN" }) {
                assertEquals(key, 1L,
                        log.messagesAt(Level.WARN).stream().filter(m -> m.contains(key) && m.contains("searchable by anyone")).count());
            }
        } finally {
            log.detach();
        }
    }

    @Test
    public void test_noGuestRole_noPublicWarning() {
        enqueueProject(false, false, false);
        server.enqueue("issues", BacklogApiMockServer.json("[" + issueJson(1, "[]") + "]"));

        final TestLogAppender log = TestLogAppender.attachTo(BacklogDataStore.class);
        try {
            crawl(newParamMap());

            assertEquals(0L, log.messagesAt(Level.WARN).stream().filter(m -> m.contains("searchable by anyone")).count());
        } finally {
            log.detach();
        }
    }

    @Test
    public void test_emptyMergedRoles_warnsOncePerProject() {
        server.enqueue("projects", BacklogApiMockServer
                .json("[" + projectJson(1, "PROJ", false, false, false) + "," + projectJson(2, "OPEN", false, false, false) + "]"));
        server.enqueue("issues", BacklogApiMockServer.json("[" + issueJson(1, "[]") + "," + issueJson(2, "[]") + "]"));
        final DataStoreParams paramMap = newParamMap();
        paramMap.put("permission_sync", "false");

        final TestLogAppender log = TestLogAppender.attachTo(BacklogDataStore.class);
        try {
            crawl(paramMap);

            assertEquals(1L, log.messagesAt(Level.WARN).stream().filter(m -> m.contains("PROJ") && m.contains("unsearchable")).count());
            assertEquals(1L, log.messagesAt(Level.WARN).stream().filter(m -> m.contains("OPEN") && m.contains("unsearchable")).count());
        } finally {
            log.detach();
        }
    }
}
