/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.segment.consensus.config;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Reads the OSGi metatype descriptors that bnd generates from
 * {@code @ObjectClassDefinition} interfaces into {@code OSGI-INF/metatype}, so
 * runtime views can show the declared configuration contract without keeping
 * another hand-written copy of names, descriptions, and defaults.
 */
public final class MetatypeCatalog {

    static final String METATYPE_FOLDER = "OSGI-INF/metatype/";

    public static final class Attribute {
        public final String id;
        public final String name;
        public final String description;
        public final String type;
        public final String defaultValue;

        Attribute(String id, String name, String description, String type, String defaultValue) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.type = type;
            this.defaultValue = defaultValue;
        }
    }

    public static final class Definition {
        public final String pid;
        public final String ocdId;
        public final String name;
        public final String description;
        public final List<Attribute> attributes;

        Definition(String pid, String ocdId, String name, String description, List<Attribute> attributes) {
            this.pid = pid;
            this.ocdId = ocdId;
            this.name = name;
            this.description = description;
            this.attributes = Collections.unmodifiableList(attributes);
        }
    }

    private MetatypeCatalog() {
    }

    /**
     * Loads the descriptor generated for an {@code @ObjectClassDefinition} type.
     *
     * @return the definition, or {@code null} when the descriptor is not on the classpath
     */
    @Nullable
    public static Definition load(Class<?> ocdType) {
        String resource = METATYPE_FOLDER + ocdType.getName() + ".xml";
        ClassLoader loader = ocdType.getClassLoader();
        try (InputStream in = loader != null ? loader.getResourceAsStream(resource) : null) {
            return in == null ? null : parse(in);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable metatype descriptor " + resource, e);
        }
    }

    static Definition parse(InputStream in) throws Exception {
        Document document = newDocumentBuilder().parse(in);
        Element ocd = firstElement(document, "OCD");
        if (ocd == null) {
            throw new IllegalStateException("Metatype descriptor has no OCD element");
        }
        List<Attribute> attributes = new ArrayList<>();
        NodeList ads = ocd.getElementsByTagName("AD");
        for (int i = 0; i < ads.getLength(); i++) {
            Element ad = (Element) ads.item(i);
            attributes.add(new Attribute(ad.getAttribute("id"), ad.getAttribute("name"),
                ad.getAttribute("description"), ad.getAttribute("type"), ad.getAttribute("default")));
        }
        Element designate = firstElement(document, "Designate");
        String pid = designate != null ? designate.getAttribute("pid") : ocd.getAttribute("id");
        return new Definition(pid, ocd.getAttribute("id"), ocd.getAttribute("name"),
            ocd.getAttribute("description"), attributes);
    }

    private static DocumentBuilder newDocumentBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }

    @Nullable
    private static Element firstElement(Document document, String tagName) {
        NodeList nodes = document.getElementsByTagName(tagName);
        return nodes.getLength() > 0 ? (Element) nodes.item(0) : null;
    }
}
