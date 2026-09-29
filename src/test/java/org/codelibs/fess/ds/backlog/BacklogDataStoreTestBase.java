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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.codelibs.fess.app.service.FailureUrlService;
import org.codelibs.fess.crawler.container.CrawlerContainer;
import org.codelibs.fess.crawler.extractor.ExtractorFactory;
import org.codelibs.fess.crawler.extractor.impl.TikaExtractor;
import org.codelibs.fess.crawler.filter.UrlFilter;
import org.codelibs.fess.crawler.helper.MimeTypeHelper;
import org.codelibs.fess.crawler.helper.impl.MimeTypeHelperImpl;
import org.codelibs.fess.entity.DataStoreParams;
import org.codelibs.fess.helper.CrawlerStatsHelper;
import org.codelibs.fess.helper.PermissionHelper;
import org.codelibs.fess.helper.SystemHelper;
import org.codelibs.fess.opensearch.config.exentity.CrawlingConfig;
import org.codelibs.fess.opensearch.config.exentity.DataConfig;
import org.codelibs.fess.opensearch.config.exentity.FailureUrl;
import org.codelibs.fess.script.ScriptEngineFactory;
import org.codelibs.fess.util.ComponentUtil;
import org.junit.jupiter.api.TestInfo;

/**
 * Shared fixture for {@link BacklogDataStore} tests: a started {@link BacklogApiMockServer}, the
 * Fess components {@code storeData} looks up, a recording {@link FailureUrlService}, a dot-path
 * script engine standing in for Groovy/JavaScript, and JSON builders.
 */
public abstract class BacklogDataStoreTestBase extends UnitDsTestCase {

    protected static final String API_KEY = "test-api-key-0001";

    protected static final String SPACE = "https://example.backlog.com";

    protected BacklogApiMockServer server;

    protected BacklogDataStore dataStore;

    /** One {@link FailureUrlService#store} call. */
    protected record FailureRecord(String errorName, String url, Throwable throwable) {
    }

    protected final List<FailureRecord> recordedFailures = new ArrayList<>();

    @Override
    public void setUp(final TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        server = new BacklogApiMockServer();
        server.start();
        dataStore = new BacklogDataStore();

        // PermissionHelper's systemHelper is @Resource-injected in production; a component handed
        // to ComponentUtil.register is not injected, so it is wired by hand (see fess-ds-slack's
        // SlackDataStorePermissionSyncTest). The local is not named systemHelper on purpose.
        final SystemHelper helper = new SystemHelper();
        ComponentUtil.register(helper, "systemHelper");
        ComponentUtil.register(new PermissionHelper() {
            {
                this.systemHelper = helper;
            }
        }, "permissionHelper");

        final CrawlerStatsHelper crawlerStatsHelper = new CrawlerStatsHelper();
        crawlerStatsHelper.init();
        ComponentUtil.register(crawlerStatsHelper, "crawlerStatsHelper");

        recordedFailures.clear();
        ComponentUtil.register(new FailureUrlService() {
            @Override
            public FailureUrl store(final CrawlingConfig crawlingConfig, final String errorName, final String url, final Throwable e) {
                recordedFailures.add(new FailureRecord(errorName, url, e));
                return null;
            }
        }, FailureUrlService.class.getCanonicalName());

        final ScriptEngineFactory scriptEngineFactory = new ScriptEngineFactory();
        scriptEngineFactory.add("groovy", (template, resultMap) -> {
            Object value = resultMap;
            for (final String part : template.split("\\.")) {
                if (!(value instanceof Map)) {
                    return null;
                }
                value = ((Map<?, ?>) value).get(part);
            }
            return value;
        });
        ComponentUtil.register(scriptEngineFactory, "scriptEngineFactory");

        // A fresh filter per test: ComponentUtil's registry outlives a test method.
        ComponentUtil.register(new FakeUrlFilter(), UrlFilter.class.getCanonicalName());
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

    /** Parameters for a crawl against the mock server, with no pacing. */
    protected DataStoreParams newParamMap() {
        return BacklogApiMockServer.newParams(API_KEY);
    }

    /** A script map exposing the fields most tests assert on. */
    protected Map<String, String> scriptMap() {
        final Map<String, String> scriptMap = new HashMap<>();
        for (final String field : new String[] { "type", "id", "project_key", "project_name", "title", "content", "url", "mimetype",
                "created", "updated", "created_by", "updated_by", "roles", "issue_key", "issue_type", "status", "priority", "assignee",
                "categories", "milestones", "versions", "custom_fields", "comments", "tags", "parent_type", "parent_url", "parent_title",
                "size", "path" }) {
            scriptMap.put(field, "backlog." + field);
        }
        return scriptMap;
    }

    /** Runs {@code storeData} and returns the stored documents. */
    protected List<Map<String, Object>> crawl(final DataStoreParams paramMap) {
        return crawl(paramMap, new HashMap<>());
    }

    /** Runs {@code storeData} with a given default data map and returns the stored documents. */
    protected List<Map<String, Object>> crawl(final DataStoreParams paramMap, final Map<String, Object> defaultDataMap) {
        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, paramMap, scriptMap(), defaultDataMap);
        return callback.getDataMaps();
    }

    /** Returns the stored documents of one type. */
    protected static List<Map<String, Object>> ofType(final List<Map<String, Object>> docs, final String type) {
        return docs.stream().filter(d -> type.equals(d.get("type"))).toList();
    }

    protected static String projectJson(final long id, final String key, final boolean useWiki, final boolean useDocument,
            final boolean useFileSharing) {
        return "{\"id\":" + id + ",\"projectKey\":\"" + key + "\",\"name\":\"" + key + " Project\",\"useWiki\":" + useWiki
                + ",\"useDocument\":" + useDocument + ",\"useFileSharing\":" + useFileSharing + ",\"archived\":false}";
    }

    protected static String userJson(final long id, final String userId, final String mailAddress) {
        return "{\"id\":" + id + ",\"userId\":" + quoteOrNull(userId) + ",\"name\":\"User " + id + "\",\"mailAddress\":"
                + quoteOrNull(mailAddress) + "}";
    }

    protected static String quoteOrNull(final String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    /** Enqueues one project (issues-only unless flags say otherwise) with one member, alice. */
    protected void enqueueProject(final boolean useWiki, final boolean useDocument, final boolean useFileSharing) {
        server.enqueue("projects", BacklogApiMockServer.json("[" + projectJson(1, "PROJ", useWiki, useDocument, useFileSharing) + "]"));
        server.enqueue("projects/PROJ/users", BacklogApiMockServer.json("[" + userJson(1, "alice", "alice@example.com") + "]"));
    }

    /** The {@code {user}} search role Fess derives from a user name. */
    protected static String userRole(final String name) {
        return ComponentUtil.getFessConfig().getRoleSearchUserPrefix() + name;
    }

    /** Wires a real Tika extraction pipeline: an ExtractorFactory whose container serves TikaExtractor. */
    protected void registerRealExtraction() throws Exception {
        final MimeTypeHelperImpl mimeTypeHelper = new MimeTypeHelperImpl();
        ComponentUtil.register(mimeTypeHelper, MimeTypeHelper.class.getCanonicalName());

        final TikaExtractor tikaExtractor = new TikaExtractor();
        tikaExtractor.init();

        final ExtractorFactory extractorFactory = new ExtractorFactory();
        final Map<String, Object> components = new HashMap<>();
        components.put("extractorFactory", extractorFactory);
        components.put("tikaExtractor", tikaExtractor);
        components.put("mimeTypeHelper", mimeTypeHelper);
        final CrawlerContainer crawlerContainer = new CrawlerContainer() {
            @SuppressWarnings("unchecked")
            @Override
            public <T> T getComponent(final String name) {
                return (T) components.get(name);
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public void destroy() {
                // nothing
            }
        };
        final Field field = ExtractorFactory.class.getDeclaredField("crawlerContainer");
        field.setAccessible(true);
        field.set(extractorFactory, crawlerContainer);
        ComponentUtil.register(extractorFactory, "extractorFactory");
    }

    protected static boolean containsInChain(final Throwable throwable, final String text) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(text)) {
                return true;
            }
        }
        return false;
    }

    /** A minimal {@link UrlFilter}: full-match include/exclude regular expressions. */
    private static final class FakeUrlFilter implements UrlFilter {
        private final List<Pattern> includes = new ArrayList<>();
        private final List<Pattern> excludes = new ArrayList<>();

        @Override
        public void init(final String sessionId) {
            // nothing to do
        }

        @Override
        public boolean match(final String url) {
            if (!includes.isEmpty() && includes.stream().noneMatch(p -> p.matcher(url).matches())) {
                return false;
            }
            return excludes.stream().noneMatch(p -> p.matcher(url).matches());
        }

        @Override
        public void addInclude(final String urlPattern) {
            includes.add(Pattern.compile(urlPattern));
        }

        @Override
        public void addExclude(final String urlPattern) {
            excludes.add(Pattern.compile(urlPattern));
        }

        @Override
        public void processUrl(final String url) {
            // not used
        }

        @Override
        public void clear() {
            includes.clear();
            excludes.clear();
        }
    }
}
