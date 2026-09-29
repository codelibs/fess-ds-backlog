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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ContentFormatterTest extends UnitDsTestCase {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static JsonNode json(final String text) throws Exception {
        return mapper.readTree(text);
    }

    @Test
    public void test_formatIssueAttributes_allFields() throws Exception {
        final JsonNode issue = json("""
                {"issueType":{"id":1,"name":"Bug"},"status":{"id":1,"name":"Open"},"priority":{"id":2,"name":"High"},
                 "resolution":{"id":0,"name":"Fixed"},"assignee":{"id":9,"name":"Alice"},
                 "category":[{"id":1,"name":"UI"},{"id":2,"name":"API"}],
                 "versions":[{"id":1,"name":"1.0"}],"milestone":[{"id":3,"name":"Sprint 5"}]}
                """);

        assertEquals("Issue Type: Bug\nStatus: Open\nPriority: High\nResolution: Fixed\nAssignee: Alice\nCategory: UI, API\n"
                + "Version: 1.0\nMilestone: Sprint 5", ContentFormatter.formatIssueAttributes(issue));
    }

    @Test
    public void test_formatIssueAttributes_skipsNullAndEmpty() throws Exception {
        final JsonNode issue = json("""
                {"issueType":{"name":"Task"},"status":{"name":"Open"},"resolution":null,"assignee":null,"category":[],"versions":[]}
                """);

        assertEquals("Issue Type: Task\nStatus: Open", ContentFormatter.formatIssueAttributes(issue));
    }

    @Test
    public void test_formatCustomFields_valueShapes() throws Exception {
        final JsonNode fields = json("""
                [{"id":1,"name":"Text","value":"free text"},
                 {"id":2,"name":"Number","value":12.5},
                 {"id":3,"name":"Single","value":{"id":7,"name":"Large"}},
                 {"id":4,"name":"Multi","value":[{"id":1,"name":"Red"},{"id":2,"name":"Blue"}]},
                 {"id":5,"name":"Check","value":[{"id":1,"name":"Other"}],"otherValue":"custom"},
                 {"id":6,"name":"Empty","value":null},
                 {"id":7,"name":"","value":"no name"}]
                """);

        final Map<String, String> formatted = ContentFormatter.formatCustomFields(fields);

        assertEquals(List.of("Text", "Number", "Single", "Multi", "Check"), List.copyOf(formatted.keySet()));
        assertEquals("free text", formatted.get("Text"));
        assertEquals("12.5", formatted.get("Number"));
        assertEquals("Large", formatted.get("Single"));
        assertEquals("Red, Blue", formatted.get("Multi"));
        assertEquals("Other, custom", formatted.get("Check"));
        assertEquals("Text: free text\nNumber: 12.5\nSingle: Large\nMulti: Red, Blue\nCheck: Other, custom",
                ContentFormatter.formatCustomFieldLines(formatted));
    }

    @Test
    public void test_formatComment() throws Exception {
        assertEquals("Alice: Looks good",
                ContentFormatter.formatComment(json("{\"content\":\"Looks good\",\"createdUser\":{\"name\":\"Alice\"}}")));
        assertEquals("No author", ContentFormatter.formatComment(json("{\"content\":\"No author\"}")));
        assertEquals("", ContentFormatter.formatComment(json("{\"content\":null,\"createdUser\":{\"name\":\"Alice\"}}")));
        assertEquals("", ContentFormatter.formatComment(json("{\"content\":\"  \"}")));
    }

    @Test
    public void test_names_skipsBlank() throws Exception {
        assertEquals(List.of("a", "c"), ContentFormatter.names(json("[{\"name\":\"a\"},{\"name\":\"\"},{\"id\":1},{\"name\":\"c\"}]")));
        assertEquals(List.of(), ContentFormatter.names(json("null")));
        assertEquals("", ContentFormatter.name(json("{\"name\":null}")));
    }

}
