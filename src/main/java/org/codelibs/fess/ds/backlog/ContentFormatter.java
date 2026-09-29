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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.codelibs.core.lang.StringUtil;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Renders Backlog issue attributes, custom fields and comments as plain text. Reads the Jackson
 * tree defensively with {@code path(...)}, so a missing or differently shaped field yields an
 * empty value instead of an exception.
 */
public final class ContentFormatter {

    private ContentFormatter() {
    }

    /**
     * Returns {@code node.name}, or an empty string.
     *
     * @param node an object such as {@code status} or {@code assignee}
     * @return the name, never {@code null}
     */
    public static String name(final JsonNode node) {
        final JsonNode name = node.path("name");
        return name.isValueNode() && !name.isNull() ? name.asText() : StringUtil.EMPTY;
    }

    /**
     * Returns the non-blank {@code name} of each element of an array such as {@code category}.
     *
     * @param array the array node
     * @return the names, in order
     */
    public static List<String> names(final JsonNode array) {
        final List<String> names = new ArrayList<>();
        for (final JsonNode element : array) {
            final String name = name(element);
            if (StringUtil.isNotBlank(name)) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Formats an issue's built-in attributes, one {@code Label: value} line each, skipping empty
     * values.
     *
     * @param issue the issue object
     * @return the attribute lines
     */
    public static String formatIssueAttributes(final JsonNode issue) {
        final StringBuilder buf = new StringBuilder();
        appendLine(buf, "Issue Type", name(issue.path("issueType")));
        appendLine(buf, "Status", name(issue.path("status")));
        appendLine(buf, "Priority", name(issue.path("priority")));
        appendLine(buf, "Resolution", name(issue.path("resolution")));
        appendLine(buf, "Assignee", name(issue.path("assignee")));
        appendLine(buf, "Category", String.join(", ", names(issue.path("category"))));
        appendLine(buf, "Version", String.join(", ", names(issue.path("versions"))));
        appendLine(buf, "Milestone", String.join(", ", names(issue.path("milestone"))));
        return buf.toString();
    }

    /**
     * Formats an issue's {@code customFields} array as field name to value text, skipping fields
     * with no value.
     *
     * @param customFields the {@code customFields} array
     * @return the field values, in order
     */
    public static Map<String, String> formatCustomFields(final JsonNode customFields) {
        final Map<String, String> fields = new LinkedHashMap<>();
        for (final JsonNode field : customFields) {
            final String fieldName = name(field);
            if (StringUtil.isBlank(fieldName)) {
                continue;
            }
            String value = formatValue(field.path("value"));
            final String otherValue = formatValue(field.path("otherValue"));
            if (StringUtil.isNotBlank(otherValue)) {
                value = StringUtil.isBlank(value) ? otherValue : value + ", " + otherValue;
            }
            if (StringUtil.isNotBlank(value)) {
                fields.put(fieldName, value);
            }
        }
        return fields;
    }

    /**
     * Formats one custom field value: a scalar as text, an object as its {@code name}, an array as
     * the comma-joined formatting of its elements.
     *
     * @param value the value node
     * @return the text, never {@code null}
     */
    public static String formatValue(final JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return StringUtil.EMPTY;
        }
        if (value.isArray()) {
            final List<String> parts = new ArrayList<>();
            for (final JsonNode element : value) {
                final String part = formatValue(element);
                if (StringUtil.isNotBlank(part)) {
                    parts.add(part);
                }
            }
            return String.join(", ", parts);
        }
        if (value.isObject()) {
            return name(value);
        }
        return value.asText();
    }

    /**
     * Formats custom field values as {@code Name: value} lines.
     *
     * @param customFields the output of {@link #formatCustomFields(JsonNode)}
     * @return the lines
     */
    public static String formatCustomFieldLines(final Map<String, String> customFields) {
        final StringBuilder buf = new StringBuilder();
        customFields.forEach((k, v) -> appendLine(buf, k, v));
        return buf.toString();
    }

    /**
     * Formats one comment as {@code author: content}; a comment whose {@code content} is null or
     * blank (a change-log-only comment) yields an empty string.
     *
     * @param comment the comment object
     * @return the text, or an empty string
     */
    public static String formatComment(final JsonNode comment) {
        final JsonNode content = comment.path("content");
        if (!content.isTextual() || StringUtil.isBlank(content.asText())) {
            return StringUtil.EMPTY;
        }
        final String author = name(comment.path("createdUser"));
        return StringUtil.isBlank(author) ? content.asText() : author + ": " + content.asText();
    }

    private static void appendLine(final StringBuilder buf, final String label, final String value) {
        if (StringUtil.isBlank(value)) {
            return;
        }
        if (buf.length() > 0) {
            buf.append('\n');
        }
        buf.append(label).append(": ").append(value);
    }

}
