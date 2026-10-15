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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.core.exception.InterruptedRuntimeException;
import org.codelibs.core.lang.StringUtil;
import org.codelibs.core.stream.StreamUtil;
import org.codelibs.fess.Constants;
import org.codelibs.fess.app.service.FailureUrlService;
import org.codelibs.fess.crawler.exception.CrawlingAccessException;
import org.codelibs.fess.crawler.exception.MultipleCrawlingAccessException;
import org.codelibs.fess.crawler.filter.UrlFilter;
import org.codelibs.fess.crawler.helper.MimeTypeHelper;
import org.codelibs.fess.ds.AbstractDataStore;
import org.codelibs.fess.ds.callback.IndexUpdateCallback;
import org.codelibs.fess.entity.DataStoreParams;
import org.codelibs.fess.exception.DataStoreCrawlingException;
import org.codelibs.fess.helper.CrawlerStatsHelper;
import org.codelibs.fess.helper.CrawlerStatsHelper.StatsAction;
import org.codelibs.fess.helper.CrawlerStatsHelper.StatsKeyObject;
import org.codelibs.fess.helper.PermissionHelper;
import org.codelibs.fess.helper.SystemHelper;
import org.codelibs.fess.opensearch.config.exentity.DataConfig;
import org.codelibs.fess.util.ComponentUtil;
import org.lastaflute.di.core.exception.ComponentNotFoundException;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Backlog data store: crawls every project visible to an API key's user and indexes issues (with
 * comments), Wiki pages, Documents, their attachments and project shared files, restricting each
 * document to the project's members.
 *
 * <p>
 * Every run is a full crawl. Documents gone from Backlog are removed by Fess core, not by this
 * class: by default when they expire ({@code day.for.cleanup}, 3 days), or at the end of the
 * crawl when the data config sets {@code keep_expires_docs=false}. A fatal {@link
 * BacklogApiException} (401, error code 11/12) aborts the crawl;
 * every other failure is recorded per item via {@link FailureUrlService} and the crawl continues.
 * A project whose member roles cannot be resolved is skipped entirely (fail-closed).
 * </p>
 */
public class BacklogDataStore extends AbstractDataStore {

    private static final Logger logger = LogManager.getLogger(BacklogDataStore.class);

    /** Default maximum size, in bytes, of a downloaded file. */
    protected static final long DEFAULT_MAX_FILE_SIZE = 10485760L; // 10MB

    // parameters
    /** Comma-separated project keys to crawl; empty means every visible project. */
    protected static final String PROJECT_KEYS_PARAM = "project_keys";

    /** Whether archived projects are crawled. */
    protected static final String INCLUDE_ARCHIVED_PARAM = "include_archived";

    /** Whether issues are crawled. */
    protected static final String INCLUDE_ISSUES_PARAM = "include_issues";

    /** Whether issue comments are folded into the issue document. */
    protected static final String INCLUDE_COMMENTS_PARAM = "include_comments";

    /** Whether Wiki pages are crawled. */
    protected static final String INCLUDE_WIKIS_PARAM = "include_wikis";

    /** Whether Documents are crawled. */
    protected static final String INCLUDE_DOCUMENTS_PARAM = "include_documents";

    /** Whether issue/Wiki/Document attachments are downloaded and indexed. */
    protected static final String INCLUDE_ATTACHMENTS_PARAM = "include_attachments";

    /** Whether project shared files are downloaded and indexed. */
    protected static final String INCLUDE_SHARED_FILES_PARAM = "include_shared_files";

    /** Whether project members become each document's {@code {user}} roles. */
    protected static final String PERMISSION_SYNC_PARAM = "permission_sync";

    /** Extra {@code {user}}/{@code {group}}/{@code {role}}-prefixed permissions, comma-separated. */
    protected static final String DEFAULT_PERMISSIONS_PARAM = "default_permissions";

    /** Maximum size of a downloaded file. */
    protected static final String MAX_FILE_SIZE_PARAM = "max_file_size";

    /** URL include pattern, matched against {@code backlog.url}. */
    protected static final String INCLUDE_PATTERN_PARAM = "include_pattern";

    /** URL exclude pattern, matched against {@code backlog.url}. */
    protected static final String EXCLUDE_PATTERN_PARAM = "exclude_pattern";

    /** Whether an extraction failure indexes the file with empty content instead of failing it. */
    protected static final String IGNORE_ERROR_PARAM = "ignore_error";

    /**
     * Parameter keys withheld from crawl scripts: {@code convertValue} returns a parameter value
     * verbatim when a script template names it, so {@code content=api_key} would index the key.
     */
    protected static final Set<String> SECRET_PARAMS = Set.of(BacklogClient.API_KEY_PARAM);

    // script root and fields
    /** Script root under which every document's fields are exposed. */
    protected static final String BACKLOG = "backlog";

    private static final String FIELD_TYPE = "type";
    private static final String FIELD_ID = "id";
    private static final String FIELD_PROJECT_KEY = "project_key";
    private static final String FIELD_PROJECT_NAME = "project_name";
    private static final String FIELD_TITLE = "title";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_URL = "url";
    private static final String FIELD_CREATED = "created";
    private static final String FIELD_UPDATED = "updated";
    private static final String FIELD_CREATED_BY = "created_by";
    private static final String FIELD_UPDATED_BY = "updated_by";
    private static final String FIELD_ROLES = "roles";
    private static final String FIELD_MIMETYPE = "mimetype";
    private static final String FIELD_ISSUE_KEY = "issue_key";
    private static final String FIELD_ISSUE_TYPE = "issue_type";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_PRIORITY = "priority";
    private static final String FIELD_ASSIGNEE = "assignee";
    private static final String FIELD_CATEGORIES = "categories";
    private static final String FIELD_MILESTONES = "milestones";
    private static final String FIELD_VERSIONS = "versions";
    private static final String FIELD_CUSTOM_FIELDS = "custom_fields";
    private static final String FIELD_COMMENTS = "comments";
    private static final String FIELD_TAGS = "tags";
    private static final String FIELD_PARENT_TYPE = "parent_type";
    private static final String FIELD_PARENT_URL = "parent_url";
    private static final String FIELD_PARENT_TITLE = "parent_title";
    private static final String FIELD_SIZE = "size";
    private static final String FIELD_PATH = "path";

    /** Document type of an issue. */
    protected static final String TYPE_ISSUE = "issue";
    /** Document type of a Wiki page. */
    protected static final String TYPE_WIKI = "wiki";
    /** Document type of a Document. */
    protected static final String TYPE_DOCUMENT = "document";
    /** Document type of an issue/Wiki/Document attachment. */
    protected static final String TYPE_ATTACHMENT = "attachment";
    /** Document type of a project shared file. */
    protected static final String TYPE_FILE = "file";

    private static final String TEXT_PLAIN = "text/plain";

    /** Feature name for issue attachments, used in the 403 skip warning. */
    protected static final String FEATURE_ISSUE_ATTACHMENTS = "issue attachments";
    /** Feature name for Wiki attachments. */
    protected static final String FEATURE_WIKI_ATTACHMENTS = "wiki attachments";
    /** Feature name for Document attachments. */
    protected static final String FEATURE_DOCUMENT_ATTACHMENTS = "document attachments";
    /** Feature name for shared files. */
    protected static final String FEATURE_SHARED_FILES = "shared files";

    /** Name of the content extractor used for file text extraction. */
    protected String extractorName = "tikaExtractor";

    /**
     * Default constructor.
     */
    public BacklogDataStore() {
        super();
    }

    @Override
    protected String getName() {
        return this.getClass().getSimpleName();
    }

    /**
     * Sets the name of the content extractor used for file text extraction.
     *
     * @param extractorName the extractor name
     */
    public void setExtractorName(final String extractorName) {
        this.extractorName = extractorName;
    }

    /** Thrown from inside a listing callback when the admin stop button was pressed. */
    private static final class CrawlStoppedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        CrawlStoppedException() {
            super("The Backlog crawl was stopped.", null, false, false);
        }
    }

    /** Settings and collaborators shared by one {@link #storeData} run. */
    private record Crawl(DataConfig dataConfig, IndexUpdateCallback callback, DataStoreParams paramMap, Map<String, String> scriptMap,
            Map<String, Object> defaultDataMap, String scriptType, BacklogClient client, UrlFilter urlFilter, String spaceUrl,
            boolean includeIssues, boolean includeComments, boolean includeWikis, boolean includeDocuments, boolean includeAttachments,
            boolean includeSharedFiles, boolean permissionSync, long maxFileSize, boolean ignoreError) {
    }

    /** One project being crawled: its identity, the roles every one of its documents gets, and the features a 403 disabled. */
    private record Project(long id, String key, String name, String url, List<String> roles, Set<String> disabledFeatures) {
    }

    /** One file to download: an attachment of a parent document, or a shared file. */
    private record FileRef(String feature, String apiPath, String url, JsonNode node) {
    }

    @Override
    protected void storeData(final DataConfig dataConfig, final IndexUpdateCallback callback, final DataStoreParams paramMap,
            final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap) {
        try (final BacklogClient client = new BacklogClient(paramMap)) {
            final Crawl crawl = new Crawl(dataConfig, callback, paramMap, scriptMap, defaultDataMap, getScriptType(paramMap), client,
                    getUrlFilter(paramMap), client.getSpaceUrl(), getBooleanParam(paramMap, INCLUDE_ISSUES_PARAM, true),
                    getBooleanParam(paramMap, INCLUDE_COMMENTS_PARAM, true), getBooleanParam(paramMap, INCLUDE_WIKIS_PARAM, true),
                    getBooleanParam(paramMap, INCLUDE_DOCUMENTS_PARAM, true), getBooleanParam(paramMap, INCLUDE_ATTACHMENTS_PARAM, true),
                    getBooleanParam(paramMap, INCLUDE_SHARED_FILES_PARAM, true), getBooleanParam(paramMap, PERMISSION_SYNC_PARAM, true),
                    getMaxFileSize(paramMap), getBooleanParam(paramMap, IGNORE_ERROR_PARAM, true));
            final Set<String> projectKeys = getProjectKeys(paramMap);
            final Set<String> seenKeys = new HashSet<>();
            // A failure to list projects propagates: there is nothing smaller to skip.
            for (final JsonNode project : client.getProjects(getBooleanParam(paramMap, INCLUDE_ARCHIVED_PARAM, false))) {
                checkAlive();
                final String key = project.path("projectKey").asText("");
                if (!projectKeys.isEmpty() && !projectKeys.contains(key.toUpperCase(Locale.ROOT))) {
                    continue;
                }
                seenKeys.add(key.toUpperCase(Locale.ROOT));
                crawlProject(crawl, project);
            }
            for (final String key : projectKeys) {
                if (!seenKeys.contains(key)) {
                    logger.warn("Backlog project {} in {} is not visible to the API key's user; skipped.", key, PROJECT_KEYS_PARAM);
                }
            }
        } catch (final CrawlStoppedException e) {
            logger.info("The Backlog crawl was stopped before it finished.");
        }
    }

    private void checkAlive() {
        if (!alive) {
            throw new CrawlStoppedException();
        }
    }

    /**
     * Crawls one project: resolves its roles (skipping the project when that fails), then each
     * enabled feature in turn.
     */
    private void crawlProject(final Crawl crawl, final JsonNode projectNode) {
        final String key = projectNode.path("projectKey").asText("");
        final String projectUrl = crawl.spaceUrl() + "/projects/" + key;
        final List<String> roles;
        try {
            roles = buildRoles(crawl, key);
        } catch (final BacklogApiException e) {
            if (e.isFatal()) {
                throw e;
            }
            skipProject(crawl, key, projectUrl, "its members could not be fetched: " + e.getMessage(), e);
            return;
        }
        if (roles == null) {
            skipProject(crawl, key, projectUrl, "none of its members resolved to a search role", null);
            return;
        }
        final Project project =
                new Project(projectNode.path("id").asLong(), key, projectNode.path("name").asText(key), projectUrl, roles, new HashSet<>());
        if (roles.isEmpty()) {
            logger.warn(
                    "Backlog project {} has no roles (permission_sync is off and neither default_permissions nor the DataConfig permissions are set); its documents will be unsearchable by anyone.",
                    key);
        } else if (crawl.permissionSync()) {
            final List<String> publicRoles = new ArrayList<>(roles);
            publicRoles.retainAll(ComponentUtil.getFessConfig().getSearchGuestRoleList());
            if (!publicRoles.isEmpty()) {
                logger.warn(
                        "Backlog project {} gives its documents the guest role(s) {}, so they are searchable by anyone, anonymous users included, despite permission_sync. Clear the Permissions field of the crawl config (and default_permissions) to restrict them to project members.",
                        key, publicRoles);
            }
        }
        logger.info("Crawling Backlog project {}.", key);
        if (crawl.includeIssues()) {
            runListing(crawl, project, "issues", null, () -> crawl.client().listIssues(project.id(), issue -> {
                checkAlive();
                crawlIssue(crawl, project, issue);
            }));
        }
        if (crawl.includeWikis() && projectNode.path("useWiki").asBoolean(false)) {
            runListing(crawl, project, "wikis", null, () -> {
                for (final JsonNode wiki : crawl.client().getWikis(key)) {
                    checkAlive();
                    crawlWiki(crawl, project, wiki);
                }
            });
        }
        if (crawl.includeDocuments() && projectNode.path("useDocument").asBoolean(false)) {
            runListing(crawl, project, "documents", null, () -> crawl.client().listDocuments(project.id(), document -> {
                checkAlive();
                crawlDocument(crawl, project, document);
            }));
        }
        if (crawl.includeSharedFiles() && projectNode.path("useFileSharing").asBoolean(false)) {
            crawlSharedFiles(crawl, project);
        }
    }

    /**
     * Computes the roles every document of a project gets. With {@code permission_sync}, each
     * member's {@code mailAddress} and {@code userId} (blanks skipped) become {@code {user}} roles;
     * {@code default_permissions} and the DataConfig roles are merged in either way.
     *
     * @return the roles, or {@code null} when {@code permission_sync} resolved no member role
     *         (the caller then skips the project, fail-closed)
     */
    private List<String> buildRoles(final Crawl crawl, final String projectKey) {
        final Set<String> roles = new LinkedHashSet<>();
        if (crawl.permissionSync()) {
            final SystemHelper systemHelper = ComponentUtil.getSystemHelper();
            final List<String> guestRoles = ComponentUtil.getFessConfig().getSearchGuestRoleList();
            for (final JsonNode user : crawl.client().getProjectUsers(projectKey)) {
                for (final String name : new String[] { text(user, "mailAddress"), text(user, "userId") }) {
                    if (StringUtil.isBlank(name)) {
                        continue;
                    }
                    // Fess drops everything up to a backslash from a user name, so the identifier
                    // x\guest would become the role of the login guest.
                    final String role = name.contains("\\") ? null : systemHelper.getSearchRoleByUser(name.trim());
                    if (role == null || guestRoles.contains(role)) {
                        logger.warn(
                                "Backlog project {}: ignoring the identifier of member {} because it would map to the guest role or to another login.",
                                projectKey, user.path("id").asText());
                        continue;
                    }
                    roles.add(role);
                }
            }
            if (roles.isEmpty()) {
                return null;
            }
        }
        final List<String> merged = new ArrayList<>(roles);
        mergeAdditionalRoles(crawl.paramMap(), crawl.defaultDataMap(), merged);
        return new ArrayList<>(new LinkedHashSet<>(merged));
    }

    /**
     * Merges {@code default_permissions} (encoded via {@link PermissionHelper#encode}) and the
     * DataConfig roles already on {@code defaultDataMap} (already encoded, merged as-is) into
     * {@code roles}. Mirrors {@code SlackDataStore#mergeAdditionalRoles} in fess-ds-slack.
     *
     * @param paramMap the parameters, for {@code default_permissions}
     * @param defaultDataMap the default data map, for the DataConfig roles
     * @param roles the role list to merge into, in place
     */
    protected void mergeAdditionalRoles(final DataStoreParams paramMap, final Map<String, Object> defaultDataMap,
            final List<String> roles) {
        final PermissionHelper permissionHelper = ComponentUtil.getPermissionHelper();
        StreamUtil.split(paramMap.getAsString(DEFAULT_PERMISSIONS_PARAM, StringUtil.EMPTY), ",")
                .of(stream -> stream.filter(StringUtil::isNotBlank)
                        .map(permissionHelper::encode)
                        .filter(StringUtil::isNotBlank)
                        .forEach(roles::add));
        if (defaultDataMap.get(ComponentUtil.getFessConfig().getIndexFieldRole()) instanceof final List<?> roleTypeList) {
            roleTypeList.stream().map(s -> (String) s).forEach(roles::add);
        }
    }

    private void skipProject(final Crawl crawl, final String key, final String projectUrl, final String reason, final Throwable cause) {
        final String message = "Skipping Backlog project " + key + " because " + reason;
        logger.warn(message);
        final BacklogDataStoreException e = new BacklogDataStoreException(message, cause);
        ComponentUtil.getComponent(FailureUrlService.class).store(crawl.dataConfig(), e.getClass().getCanonicalName(), projectUrl, e);
    }

    /**
     * Runs one listing (issues, Wiki pages, Documents, a shared-file directory). A fatal failure
     * propagates; a 403 with {@code featureOn403} set disables that feature for the project with
     * a single WARN; any other failure is recorded against the project URL.
     */
    private void runListing(final Crawl crawl, final Project project, final String what, final String featureOn403,
            final Runnable listing) {
        try {
            listing.run();
        } catch (final BacklogApiException e) {
            if (e.isFatal()) {
                throw e;
            }
            if (featureOn403 != null && e.getStatus() == 403) {
                disableFeature(project, featureOn403, e);
                return;
            }
            logger.warn("Failed to list Backlog {} of project {}: {}", what, project.key(), e.getMessage());
            ComponentUtil.getComponent(FailureUrlService.class)
                    .store(crawl.dataConfig(), e.getClass().getCanonicalName(), project.url(), e);
        }
    }

    private void disableFeature(final Project project, final String feature, final BacklogApiException e) {
        if (project.disabledFeatures().add(feature)) {
            logger.warn("The API key's user may not access {} of Backlog project {} (HTTP 403); skipping them for this project. ({})",
                    feature, project.key(), e.getMessage());
        }
    }

    private void crawlIssue(final Crawl crawl, final Project project, final JsonNode issue) {
        final String issueKey = text(issue, "issueKey");
        if (StringUtil.isBlank(issueKey)) {
            return;
        }
        final String url = crawl.spaceUrl() + "/view/" + issueKey;
        if (!matches(crawl, url)) {
            return;
        }
        final String title = "[" + issueKey + "] " + StringUtil.defaultString(text(issue, "summary"));
        crawlItem(crawl, url, statsKey -> {
            final String comments = crawl.includeComments() ? fetchComments(crawl, issueKey, url) : StringUtil.EMPTY;
            final Map<String, String> customFields = ContentFormatter.formatCustomFields(issue.path("customFields"));
            final Map<String, Object> map = newDocument(project, TYPE_ISSUE, issue.path("id").asText(), title, url, issue);
            map.put(FIELD_CONTENT, joinSections(text(issue, "description"), ContentFormatter.formatIssueAttributes(issue),
                    ContentFormatter.formatCustomFieldLines(customFields), comments));
            map.put(FIELD_MIMETYPE, TEXT_PLAIN);
            map.put(FIELD_ISSUE_KEY, issueKey);
            map.put(FIELD_ISSUE_TYPE, ContentFormatter.name(issue.path("issueType")));
            map.put(FIELD_STATUS, ContentFormatter.name(issue.path("status")));
            map.put(FIELD_PRIORITY, ContentFormatter.name(issue.path("priority")));
            map.put(FIELD_ASSIGNEE, ContentFormatter.name(issue.path("assignee")));
            map.put(FIELD_CATEGORIES, ContentFormatter.names(issue.path("category")));
            map.put(FIELD_MILESTONES, ContentFormatter.names(issue.path("milestone")));
            map.put(FIELD_VERSIONS, ContentFormatter.names(issue.path("versions")));
            map.put(FIELD_CUSTOM_FIELDS, customFields);
            map.put(FIELD_COMMENTS, comments);
            storeDocument(crawl, statsKey, map);
        });
        if (crawl.includeAttachments()) {
            for (final JsonNode attachment : issue.path("attachments")) {
                checkAlive();
                final String id = attachment.path("id").asText();
                crawlFile(crawl, project, new FileRef(FEATURE_ISSUE_ATTACHMENTS,
                        "issues/" + BacklogClient.encodeSegment(issueKey) + "/attachments/" + id, url + "#attachment-" + id, attachment),
                        TYPE_ISSUE, url, title);
            }
        }
    }

    /**
     * Fetches every comment of an issue as text. A non-fatal failure is warned and recorded, and
     * the issue is still indexed without comments.
     */
    private String fetchComments(final Crawl crawl, final String issueKey, final String issueUrl) {
        final List<String> comments = new ArrayList<>();
        try {
            crawl.client().listComments(issueKey, comment -> {
                final String text = ContentFormatter.formatComment(comment);
                if (StringUtil.isNotBlank(text)) {
                    comments.add(text);
                }
            });
        } catch (final BacklogApiException e) {
            if (e.isFatal()) {
                throw e;
            }
            logger.warn("Failed to get comments of Backlog issue {}: {}", issueKey, e.getMessage());
            ComponentUtil.getComponent(FailureUrlService.class).store(crawl.dataConfig(), e.getClass().getCanonicalName(), issueUrl, e);
        }
        return String.join("\n\n", comments);
    }

    private void crawlWiki(final Crawl crawl, final Project project, final JsonNode summary) {
        final long wikiId = summary.path("id").asLong(-1L);
        if (wikiId < 0) {
            return;
        }
        final String url = crawl.spaceUrl() + "/alias/wiki/" + wikiId;
        if (!matches(crawl, url)) {
            return;
        }
        final JsonNode[] detail = new JsonNode[1];
        crawlItem(crawl, url, statsKey -> {
            final JsonNode wiki = crawl.client().getWiki(wikiId);
            detail[0] = wiki;
            final Map<String, Object> map = newDocument(project, TYPE_WIKI, Long.toString(wikiId), text(wiki, "name"), url, wiki);
            map.put(FIELD_CONTENT, StringUtil.defaultString(text(wiki, "content")));
            map.put(FIELD_MIMETYPE, TEXT_PLAIN);
            map.put(FIELD_TAGS, ContentFormatter.names(wiki.path("tags")));
            storeDocument(crawl, statsKey, map);
        });
        if (detail[0] != null && crawl.includeAttachments()) {
            final String title = text(detail[0], "name");
            for (final JsonNode attachment : detail[0].path("attachments")) {
                checkAlive();
                final String id = attachment.path("id").asText();
                crawlFile(crawl, project, new FileRef(FEATURE_WIKI_ATTACHMENTS, "wikis/" + wikiId + "/attachments/" + id,
                        url + "#attachment-" + id, attachment), TYPE_WIKI, url, title);
            }
        }
    }

    private void crawlDocument(final Crawl crawl, final Project project, final JsonNode document) {
        final String documentId = text(document, "id");
        if (StringUtil.isBlank(documentId)) {
            return;
        }
        final String url = crawl.spaceUrl() + "/document/" + project.key() + "/" + documentId;
        if (!matches(crawl, url)) {
            return;
        }
        final String title = text(document, "title");
        crawlItem(crawl, url, statsKey -> {
            final Map<String, Object> map = newDocument(project, TYPE_DOCUMENT, documentId, title, url, document);
            map.put(FIELD_CONTENT, StringUtil.defaultString(text(document, "plain")));
            map.put(FIELD_MIMETYPE, TEXT_PLAIN);
            map.put(FIELD_TAGS, ContentFormatter.names(document.path("tags")));
            storeDocument(crawl, statsKey, map);
        });
        if (crawl.includeAttachments()) {
            for (final JsonNode attachment : document.path("attachments")) {
                checkAlive();
                final String id = attachment.path("id").asText();
                crawlFile(crawl, project,
                        new FileRef(FEATURE_DOCUMENT_ATTACHMENTS,
                                "documents/" + BacklogClient.encodeSegment(documentId) + "/attachments/" + id, url + "#attachment-" + id,
                                attachment),
                        TYPE_DOCUMENT, url, title);
            }
        }
    }

    /**
     * Walks the project's shared files breadth-first from {@code /}. A 403 on any listing or
     * download disables shared files for the rest of this project.
     */
    private void crawlSharedFiles(final Crawl crawl, final Project project) {
        final Deque<String> directories = new ArrayDeque<>();
        final Set<String> visited = new HashSet<>();
        directories.add("/");
        visited.add("/");
        while (!directories.isEmpty() && !project.disabledFeatures().contains(FEATURE_SHARED_FILES)) {
            checkAlive();
            final String directory = directories.poll();
            runListing(crawl, project, "shared files in " + directory, FEATURE_SHARED_FILES,
                    () -> crawl.client().listSharedFiles(project.key(), directory, entry -> {
                        checkAlive();
                        final String name = text(entry, "name");
                        if (StringUtil.isBlank(name)) {
                            return;
                        }
                        final String dir = StringUtil.isNotBlank(text(entry, "dir")) ? text(entry, "dir") : directory;
                        if ("directory".equals(text(entry, "type"))) {
                            final String child = dir + name + "/";
                            if (visited.add(child)) {
                                directories.add(child);
                            }
                        } else if ("file".equals(text(entry, "type"))) {
                            final String url = crawl.spaceUrl() + "/file/" + project.key() + BacklogClient.encodePath(dir + name);
                            if (matches(crawl, url)) {
                                crawlFile(crawl, project, new FileRef(FEATURE_SHARED_FILES,
                                        "projects/" + BacklogClient.encodeSegment(project.key()) + "/files/" + entry.path("id").asText(),
                                        url, entry), null, null, null);
                            }
                        } else {
                            logger.warn("Skipping the shared file entry {}{} of project {}: unknown type {}", dir, name, project.key(),
                                    text(entry, "type"));
                        }
                    }));
        }
    }

    /**
     * Downloads, extracts and indexes one attachment ({@code parentType} set) or shared file
     * ({@code parentType} {@code null}). A 403 disables {@code ref.feature()} for the project; a
     * file over {@code max_file_size} is skipped; any other download failure is recorded. Never
     * logs or records the API path with credentials: authentication is header-only.
     */
    private void crawlFile(final Crawl crawl, final Project project, final FileRef ref, final String parentType, final String parentUrl,
            final String parentTitle) {
        checkAlive();
        if (project.disabledFeatures().contains(ref.feature())) {
            return;
        }
        if (parentType != null && !matches(crawl, ref.url())) {
            return;
        }
        final String name = text(ref.node(), "name");
        final long declaredSize = ref.node().path("size").asLong(-1L);
        crawlItem(crawl, ref.url(), statsKey -> {
            final CrawlerStatsHelper crawlerStatsHelper = ComponentUtil.getCrawlerStatsHelper();
            if (declaredSize > crawl.maxFileSize()) {
                logger.info("Skipping the Backlog file \"{}\" because its size ({} bytes) is over max_file_size ({} bytes).", name,
                        declaredSize, crawl.maxFileSize());
                crawlerStatsHelper.discard(statsKey);
                return;
            }
            final byte[] content;
            try {
                content = crawl.client().getFile(ref.apiPath(), crawl.maxFileSize());
            } catch (final BacklogClient.FileTooLargeException e) {
                logger.info("Skipping the Backlog file \"{}\" because its size ({} bytes) is over max_file_size ({} bytes).", name,
                        e.size(), crawl.maxFileSize());
                crawlerStatsHelper.discard(statsKey);
                return;
            } catch (final BacklogApiException e) {
                if (!e.isFatal() && e.getStatus() == 403) {
                    disableFeature(project, ref.feature(), e);
                    crawlerStatsHelper.discard(statsKey);
                    return;
                }
                throw e;
            }
            final String mimeType = ComponentUtil.getComponent(MimeTypeHelper.class).getContentType(null, name);
            final Map<String, Object> map = newDocument(project, parentType != null ? TYPE_ATTACHMENT : TYPE_FILE,
                    ref.node().path("id").asText(), name, ref.url(), ref.node());
            map.put(FIELD_CONTENT, extractText(content, name, mimeType, ref.url(), crawl));
            map.put(FIELD_MIMETYPE, mimeType);
            map.put(FIELD_SIZE, (long) content.length);
            if (parentType != null) {
                map.put(FIELD_PARENT_TYPE, parentType);
                map.put(FIELD_PARENT_URL, parentUrl);
                map.put(FIELD_PARENT_TITLE, parentTitle);
            } else {
                map.put(FIELD_PATH, StringUtil.defaultString(text(ref.node(), "dir")) + name);
            }
            storeDocument(crawl, statsKey, map);
        });
    }

    private String extractText(final byte[] content, final String name, final String mimeType, final String url, final Crawl crawl) {
        try (final InputStream in = new ByteArrayInputStream(content)) {
            return ComponentUtil.getExtractorFactory()
                    .builder(in, null)
                    .filename(name)
                    .mimeType(mimeType)
                    .extractorName(extractorName)
                    .maxContentLength(crawl.maxFileSize())
                    .extract()
                    .getContent();
        } catch (final Exception e) {
            if (!crawl.ignoreError()) {
                throw new DataStoreCrawlingException(url, "Failed to get contents: " + name, e);
            }
            logger.warn("Failed to get contents: {}. {}", name, e.getMessage());
            return StringUtil.EMPTY;
        }
    }

    /**
     * Runs one document's work under its own stats key, recording any non-fatal failure against
     * {@code url} and letting fatal errors, interrupts and stop requests propagate.
     */
    private void crawlItem(final Crawl crawl, final String url, final Consumer<StatsKeyObject> task) {
        final CrawlerStatsHelper crawlerStatsHelper = ComponentUtil.getCrawlerStatsHelper();
        final StatsKeyObject statsKey = new StatsKeyObject(url);
        crawl.paramMap().put(Constants.CRAWLER_STATS_KEY, statsKey);
        try {
            crawlerStatsHelper.begin(statsKey);
            logger.info("Crawling URL: {}", url);
            task.accept(statsKey);
            crawlerStatsHelper.record(statsKey, StatsAction.FINISHED);
        } catch (final InterruptedRuntimeException | CrawlStoppedException e) {
            throw e;
        } catch (final BacklogApiException e) {
            if (e.isFatal()) {
                throw e;
            }
            logger.warn("Failed to crawl {}: {}", url, e.getMessage());
            ComponentUtil.getComponent(FailureUrlService.class).store(crawl.dataConfig(), e.getClass().getCanonicalName(), url, e);
            crawlerStatsHelper.record(statsKey, StatsAction.ACCESS_EXCEPTION);
        } catch (final CrawlingAccessException e) {
            recordAccessFailure(crawl.dataConfig(), statsKey, url, e, crawlerStatsHelper);
        } catch (final Exception e) {
            logger.warn("Unexpected exception at : {}", url, e);
            ComponentUtil.getComponent(FailureUrlService.class).store(crawl.dataConfig(), e.getClass().getCanonicalName(), url, e);
            crawlerStatsHelper.record(statsKey, StatsAction.EXCEPTION);
        } finally {
            crawlerStatsHelper.done(statsKey);
        }
    }

    private void recordAccessFailure(final DataConfig dataConfig, final StatsKeyObject statsKey, final String fallbackUrl,
            final CrawlingAccessException e, final CrawlerStatsHelper crawlerStatsHelper) {
        logger.warn("Crawling Access Exception at : {}", fallbackUrl, e);
        Throwable target = e;
        if (target instanceof final MultipleCrawlingAccessException ex) {
            final Throwable[] causes = ex.getCauses();
            if (causes.length > 0) {
                target = causes[causes.length - 1];
            }
        }
        final Throwable cause = target.getCause();
        final String errorName = (cause != null ? cause : target).getClass().getCanonicalName();
        String url = fallbackUrl;
        if (target instanceof final DataStoreCrawlingException dce) {
            url = dce.getUrl();
        }
        ComponentUtil.getComponent(FailureUrlService.class).store(dataConfig, errorName, url, target);
        crawlerStatsHelper.record(statsKey, StatsAction.ACCESS_EXCEPTION);
    }

    /** Builds the common fields every document type shares. */
    private Map<String, Object> newDocument(final Project project, final String type, final String id, final String title, final String url,
            final JsonNode source) {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put(FIELD_TYPE, type);
        map.put(FIELD_ID, id);
        map.put(FIELD_PROJECT_KEY, project.key());
        map.put(FIELD_PROJECT_NAME, project.name());
        map.put(FIELD_TITLE, StringUtil.defaultString(title));
        map.put(FIELD_URL, url);
        map.put(FIELD_CREATED, text(source, "created"));
        map.put(FIELD_UPDATED, text(source, "updated"));
        map.put(FIELD_CREATED_BY, ContentFormatter.name(source.path("createdUser")));
        map.put(FIELD_UPDATED_BY, ContentFormatter.name(source.path("updatedUser")));
        map.put(FIELD_ROLES, new ArrayList<>(project.roles()));
        return map;
    }

    private void storeDocument(final Crawl crawl, final StatsKeyObject statsKey, final Map<String, Object> backlogMap) {
        ComponentUtil.getCrawlerStatsHelper().record(statsKey, StatsAction.PREPARED);
        final Map<String, Object> dataMap = new HashMap<>(crawl.defaultDataMap());
        final Map<String, Object> resultMap = newResultMap(crawl.paramMap());
        resultMap.put(BACKLOG, backlogMap);
        for (final Map.Entry<String, String> entry : crawl.scriptMap().entrySet()) {
            final Object convertValue = convertValue(crawl.scriptType(), entry.getValue(), resultMap);
            if (convertValue != null) {
                dataMap.put(entry.getKey(), convertValue);
            }
        }
        crawl.callback().store(crawl.paramMap(), dataMap);
    }

    /**
     * Builds the map exposed to crawl scripts, withholding {@link #SECRET_PARAMS}.
     *
     * @param paramMap the data store parameters
     * @return a new map safe to expose to scripts
     */
    protected Map<String, Object> newResultMap(final DataStoreParams paramMap) {
        final Map<String, Object> resultMap = new LinkedHashMap<>();
        paramMap.asMap().forEach((k, v) -> {
            if (!SECRET_PARAMS.contains(k)) {
                resultMap.put(k, v);
            }
        });
        return resultMap;
    }

    private boolean matches(final Crawl crawl, final String url) {
        if (crawl.urlFilter() != null && !crawl.urlFilter().match(url)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Not matched: {}", url);
            }
            return false;
        }
        return true;
    }

    private String joinSections(final String... sections) {
        return Arrays.stream(sections).filter(StringUtil::isNotBlank).collect(Collectors.joining("\n\n"));
    }

    private String text(final JsonNode node, final String field) {
        return node.hasNonNull(field) ? node.path(field).asText() : null;
    }

    /**
     * Parses {@code project_keys} into an upper-cased set.
     *
     * @param paramMap the configuration parameters
     * @return the project keys, empty for every visible project
     */
    protected Set<String> getProjectKeys(final DataStoreParams paramMap) {
        final String value = paramMap.getAsString(PROJECT_KEYS_PARAM);
        if (StringUtil.isBlank(value)) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StringUtil::isNotBlank)
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Creates the URL filter from {@code include_pattern}/{@code exclude_pattern}, applied to
     * {@code backlog.url}.
     *
     * @param paramMap the configuration parameters
     * @return the URL filter, or {@code null} if the component is not registered
     */
    protected UrlFilter getUrlFilter(final DataStoreParams paramMap) {
        final UrlFilter urlFilter;
        try {
            urlFilter = ComponentUtil.getComponent(UrlFilter.class);
        } catch (final ComponentNotFoundException e) {
            logger.warn("UrlFilter component is not registered; {} and {} will not be applied.", INCLUDE_PATTERN_PARAM,
                    EXCLUDE_PATTERN_PARAM, e);
            return null;
        }
        final String include = paramMap.getAsString(INCLUDE_PATTERN_PARAM);
        if (StringUtil.isNotBlank(include)) {
            urlFilter.addInclude(include);
        }
        final String exclude = paramMap.getAsString(EXCLUDE_PATTERN_PARAM);
        if (StringUtil.isNotBlank(exclude)) {
            urlFilter.addExclude(exclude);
        }
        urlFilter.init(paramMap.getAsString(Constants.CRAWLING_INFO_ID));
        return urlFilter;
    }

    /**
     * Parses a boolean parameter, falling back to the default with a WARN for a value other than
     * {@code true}/{@code false} (case-insensitive).
     *
     * @param paramMap the configuration parameters
     * @param paramName the parameter name
     * @param defaultValue the fallback
     * @return the parsed value
     */
    protected boolean getBooleanParam(final DataStoreParams paramMap, final String paramName, final boolean defaultValue) {
        final String value = paramMap.getAsString(paramName);
        if (StringUtil.isBlank(value)) {
            return defaultValue;
        }
        final String trimmed = value.trim();
        if (Constants.TRUE.equalsIgnoreCase(trimmed)) {
            return true;
        }
        if (Constants.FALSE.equalsIgnoreCase(trimmed)) {
            return false;
        }
        logger.warn("Parameter '{}' is not 'true' or 'false': {}. Falling back to {}.", paramName, value, defaultValue);
        return defaultValue;
    }

    /**
     * Returns {@code max_file_size}, falling back to {@link #DEFAULT_MAX_FILE_SIZE} with a WARN for
     * a non-numeric or non-positive value.
     *
     * @param paramMap the configuration parameters
     * @return the maximum file size in bytes
     */
    protected long getMaxFileSize(final DataStoreParams paramMap) {
        final String value = paramMap.getAsString(MAX_FILE_SIZE_PARAM);
        if (StringUtil.isBlank(value)) {
            return DEFAULT_MAX_FILE_SIZE;
        }
        final long parsed;
        try {
            parsed = Long.parseLong(value.trim());
        } catch (final NumberFormatException e) {
            logger.warn("Parameter '{}' is not a number: {}. Falling back to {}.", MAX_FILE_SIZE_PARAM, value, DEFAULT_MAX_FILE_SIZE);
            return DEFAULT_MAX_FILE_SIZE;
        }
        if (parsed <= 0) {
            logger.warn("Parameter '{}' must be positive: {}. Falling back to {}.", MAX_FILE_SIZE_PARAM, parsed, DEFAULT_MAX_FILE_SIZE);
            return DEFAULT_MAX_FILE_SIZE;
        }
        return parsed;
    }

}
