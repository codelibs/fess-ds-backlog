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
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.codelibs.fess.entity.DataStoreParams;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link BacklogDataStore#storeData} for Wiki pages, Documents, shared files, their
 * attachments, the project feature flags and the 403 feature skip.
 */
public class BacklogDataStoreContentTypesTest extends BacklogDataStoreTestBase {

    private DataStoreParams paramsWithoutIssues() {
        final DataStoreParams paramMap = newParamMap();
        paramMap.put("include_issues", "false");
        return paramMap;
    }

    @Test
    public void test_wiki_indexedFromDetail() {
        enqueueProject(true, false, false);
        server.enqueue("wikis", BacklogApiMockServer.json("[{\"id\":5,\"projectId\":1,\"name\":\"Home\"}]"));
        server.enqueue("wikis/5", BacklogApiMockServer.json("""
                {"id":5,"projectId":1,"name":"Home","content":"Welcome to the project wiki.",
                 "tags":[{"id":1,"name":"guide"}],"attachments":[],
                 "created":"2026-02-01T00:00:00Z","updated":"2026-02-02T00:00:00Z",
                 "createdUser":{"name":"Alice"},"updatedUser":{"name":"Bob"}}
                """));

        final List<Map<String, Object>> docs = crawl(paramsWithoutIssues());

        assertEquals(1, docs.size());
        final Map<String, Object> wiki = docs.get(0);
        assertEquals("wiki", wiki.get("type"));
        assertEquals("5", wiki.get("id"));
        assertEquals("Home", wiki.get("title"));
        assertEquals("Welcome to the project wiki.", wiki.get("content"));
        assertEquals(SPACE + "/alias/wiki/5", wiki.get("url"));
        assertEquals("text/plain", wiki.get("mimetype"));
        assertEquals(List.of("guide"), wiki.get("tags"));
        assertEquals("Alice", wiki.get("created_by"));
        assertEquals("Bob", wiki.get("updated_by"));
        assertEquals(List.of(userRole("alice@example.com"), userRole("alice")), wiki.get("roles"));
        assertEquals("PROJ", server.getQueries("wikis").get(0).get("projectIdOrKey"));
    }

    @Test
    public void test_wikiAttachment_downloaded() throws Exception {
        registerRealExtraction();
        enqueueProject(true, false, false);
        server.enqueue("wikis", BacklogApiMockServer.json("[{\"id\":5,\"name\":\"Home\"}]"));
        server.enqueue("wikis/5", BacklogApiMockServer.json("""
                {"id":5,"name":"Home","content":"x","attachments":[{"id":7,"name":"guide.txt","size":20}]}
                """));
        server.enqueue("wikis/5/attachments/7", BacklogApiMockServer.file("Wiki attachment text.".getBytes(StandardCharsets.UTF_8)));

        final List<Map<String, Object>> docs = crawl(paramsWithoutIssues());

        final Map<String, Object> attachment = ofType(docs, "attachment").get(0);
        assertEquals(SPACE + "/alias/wiki/5#attachment-7", attachment.get("url"));
        assertEquals("wiki", attachment.get("parent_type"));
        assertEquals("Home", attachment.get("parent_title"));
        assertTrue(((String) attachment.get("content")).contains("Wiki attachment text."));
    }

    @Test
    public void test_wikiDetailFailure_isRecordedAgainstWikiUrl() {
        enqueueProject(true, false, false);
        server.enqueue("wikis", BacklogApiMockServer.json("[{\"id\":5,\"name\":\"Home\"},{\"id\":6,\"name\":\"Next\"}]"));
        server.enqueue("wikis/5", BacklogApiMockServer.error(404, 6));
        server.enqueue("wikis/6", BacklogApiMockServer.json("{\"id\":6,\"name\":\"Next\",\"content\":\"ok\"}"));

        final List<Map<String, Object>> docs = crawl(paramsWithoutIssues());

        assertEquals(1, docs.size());
        assertEquals(1, recordedFailures.size());
        assertEquals(SPACE + "/alias/wiki/5", recordedFailures.get(0).url());
    }

    @Test
    public void test_wikis_skippedWhenProjectOrParamDisablesThem() {
        enqueueProject(false, false, false);
        crawl(paramsWithoutIssues());
        assertEquals("useWiki=false must skip wikis", 0, server.getRequestCount("wikis"));

        enqueueProject(true, false, false);
        final DataStoreParams paramMap = paramsWithoutIssues();
        paramMap.put("include_wikis", "false");
        crawl(paramMap);
        assertEquals("include_wikis=false must skip wikis", 0, server.getRequestCount("wikis"));
    }

    @Test
    public void test_document_indexedWithAttachment() throws Exception {
        registerRealExtraction();
        enqueueProject(false, true, false);
        server.enqueue("documents", BacklogApiMockServer.json("""
                [{"id":"0123abcd","projectId":1,"title":"Design","plain":"Architecture overview.",
                  "tags":[{"id":1,"name":"arch"}],"attachments":[{"id":3,"name":"diagram.txt","size":10}],
                  "created":"2026-03-01T00:00:00Z","updated":"2026-03-02T00:00:00Z"}]
                """));
        server.enqueue("documents/0123abcd/attachments/3", BacklogApiMockServer.file("Diagram notes.".getBytes(StandardCharsets.UTF_8)));

        final List<Map<String, Object>> docs = crawl(paramsWithoutIssues());

        final Map<String, Object> document = ofType(docs, "document").get(0);
        assertEquals("Design", document.get("title"));
        assertEquals("Architecture overview.", document.get("content"));
        assertEquals(SPACE + "/document/PROJ/0123abcd", document.get("url"));
        assertEquals(List.of("arch"), document.get("tags"));
        assertEquals("1", server.getQueries("documents").get(0).get("projectId[]"));
        final Map<String, Object> attachment = ofType(docs, "attachment").get(0);
        assertEquals(SPACE + "/document/PROJ/0123abcd#attachment-3", attachment.get("url"));
        assertEquals("document", attachment.get("parent_type"));
    }

    @Test
    public void test_documents_skippedWhenProjectDisablesThem() {
        enqueueProject(false, false, false);
        crawl(paramsWithoutIssues());
        assertEquals(0, server.getRequestCount("documents"));
    }

    @Test
    public void test_sharedFiles_walkedRecursively() throws Exception {
        registerRealExtraction();
        enqueueProject(false, false, true);
        server.enqueue("projects/PROJ/files/metadata/", BacklogApiMockServer.json("""
                [{"id":11,"type":"file","dir":"/","name":"readme.txt","size":12},
                 {"id":12,"type":"directory","dir":"/","name":"docs","size":null}]
                """));
        server.enqueue("projects/PROJ/files/metadata/docs/", BacklogApiMockServer.json("""
                [{"id":13,"type":"file","dir":"/docs/","name":"spec.txt","size":9,
                  "created":"2026-04-01T00:00:00Z","createdUser":{"name":"Alice"}}]
                """));
        server.enqueue("projects/PROJ/files/11", BacklogApiMockServer.file("Readme body.".getBytes(StandardCharsets.UTF_8)));
        server.enqueue("projects/PROJ/files/13", BacklogApiMockServer.file("Spec body".getBytes(StandardCharsets.UTF_8)));

        final List<Map<String, Object>> docs = crawl(paramsWithoutIssues());

        final List<Map<String, Object>> files = ofType(docs, "file");
        assertEquals(2, files.size());
        assertEquals(SPACE + "/file/PROJ/readme.txt", files.get(0).get("url"));
        assertEquals("/readme.txt", files.get(0).get("path"));
        assertEquals(SPACE + "/file/PROJ/docs/spec.txt", files.get(1).get("url"));
        assertEquals("/docs/spec.txt", files.get(1).get("path"));
        assertEquals("Alice", files.get(1).get("created_by"));
        assertTrue(((String) files.get(1).get("content")).contains("Spec body"));
        assertEquals(List.of(userRole("alice@example.com"), userRole("alice")), files.get(1).get("roles"));
        server.assertAllConsumed();
    }

    @Test
    public void test_sharedFilesListingForbidden_skipsFeature_withOneWarn_andNoFailure() {
        enqueueProject(true, false, true);
        server.enqueue("projects/PROJ/files/metadata/", BacklogApiMockServer.error(403, 5));
        server.enqueue("wikis", BacklogApiMockServer.json("[{\"id\":5,\"name\":\"Home\"}]"));
        server.enqueue("wikis/5", BacklogApiMockServer.json("{\"id\":5,\"name\":\"Home\",\"content\":\"ok\"}"));

        final TestLogAppender log = TestLogAppender.attachTo(BacklogDataStore.class);
        try {
            final List<Map<String, Object>> docs = crawl(paramsWithoutIssues());

            assertEquals("other features are unaffected", 1, ofType(docs, "wiki").size());
            assertEquals(0, recordedFailures.size());
            assertEquals(1L, log.messagesAt(Level.WARN).stream().filter(m -> m.contains("shared files")).count());
        } finally {
            log.detach();
        }
    }

    @Test
    public void test_sharedFileDownloadForbidden_stopsFurtherDownloadsAndWalking() throws Exception {
        registerRealExtraction();
        enqueueProject(false, false, true);
        server.enqueue("projects/PROJ/files/metadata/", BacklogApiMockServer.json("""
                [{"id":11,"type":"file","dir":"/","name":"a.txt","size":1},
                 {"id":12,"type":"file","dir":"/","name":"b.txt","size":1},
                 {"id":13,"type":"directory","dir":"/","name":"docs"}]
                """));
        server.enqueue("projects/PROJ/files/11", BacklogApiMockServer.error(403, 5));

        final List<Map<String, Object>> docs = crawl(paramsWithoutIssues());

        assertEquals(0, docs.size());
        assertEquals("the first file was attempted", 1, server.getRequestCount("projects/PROJ/files/11"));
        assertEquals(0, server.getRequestCount("projects/PROJ/files/12"));
        assertEquals(0, server.getRequestCount("projects/PROJ/files/metadata/docs/"));
        assertEquals(0, recordedFailures.size());
    }

    @Test
    public void test_sharedFiles_skippedWhenProjectOrParamDisablesThem() {
        enqueueProject(false, false, false);
        crawl(paramsWithoutIssues());
        assertEquals(0, server.getRequestCount("projects/PROJ/files/metadata/"));

        enqueueProject(false, false, true);
        final DataStoreParams paramMap = paramsWithoutIssues();
        paramMap.put("include_shared_files", "false");
        crawl(paramMap);
        assertEquals(0, server.getRequestCount("projects/PROJ/files/metadata/"));
    }

    @Test
    public void test_unparseableWikiDetail_isRecordedAgainstWikiUrl() {
        enqueueProject(true, false, false);
        server.enqueue("wikis", BacklogApiMockServer.json("[{\"id\":5,\"name\":\"Home\"},{\"id\":6,\"name\":\"Next\"}]"));
        server.enqueue("wikis/5", BacklogApiMockServer.json("<html>maintenance</html>"));
        server.enqueue("wikis/6", BacklogApiMockServer.json("{\"id\":6,\"name\":\"Next\",\"content\":\"ok\"}"));

        final List<Map<String, Object>> docs = crawl(paramsWithoutIssues());

        assertEquals(1, docs.size());
        assertEquals(1, recordedFailures.size());
        assertEquals(SPACE + "/alias/wiki/5", recordedFailures.get(0).url());
    }

    @Test
    public void test_unparseableWikiListing_isRecordedAgainstProject() {
        enqueueProject(true, false, false);
        server.enqueue("wikis", BacklogApiMockServer.json("<html>maintenance</html>"));

        final List<Map<String, Object>> docs = crawl(paramsWithoutIssues());

        assertEquals(0, docs.size());
        assertEquals(1, recordedFailures.size());
        assertEquals(SPACE + "/projects/PROJ", recordedFailures.get(0).url());
    }
}
