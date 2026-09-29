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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.SAXParserFactory;

import org.junit.jupiter.api.Test;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Fess merges every {@code fess_ds++.xml} on the class path at start-up, so a file that is not
 * well-formed stops Fess from booting. No other test loads it, hence this one.
 */
public class DiXmlTest {

    @Test
    public void test_fessDsXml_isWellFormed_andRegistersTheDataStore() throws Exception {
        final URL url = getClass().getResource("/fess_ds++.xml");
        assertNotNull(url, "fess_ds++.xml must be on the class path");
        final SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        final List<String> componentClasses = new ArrayList<>();
        try (InputStream in = url.openStream()) {
            factory.newSAXParser().parse(in, new DefaultHandler() {
                @Override
                public void startElement(final String uri, final String localName, final String qName, final Attributes attributes) {
                    if ("component".equals(qName)) {
                        componentClasses.add(attributes.getValue("class"));
                    }
                }
            });
        }
        assertEquals(List.of(BacklogDataStore.class.getName()), componentClasses);
    }
}
