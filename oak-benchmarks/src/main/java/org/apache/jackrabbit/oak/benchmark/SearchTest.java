/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.jackrabbit.oak.benchmark;

import static org.apache.jackrabbit.guava.common.collect.Lists.newArrayList;
import static org.apache.jackrabbit.oak.plugins.index.IndexConstants.INDEX_DEFINITIONS_NAME;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.jcr.Node;
import javax.jcr.Session;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.QueryResult;
import javax.jcr.query.RowIterator;

import org.apache.jackrabbit.guava.common.collect.ImmutableList;
import org.apache.commons.io.FileUtils;
import org.apache.jackrabbit.oak.benchmark.wikipedia.WikipediaImport;
import org.apache.jackrabbit.oak.plugins.index.IndexUtils;
import org.apache.jackrabbit.oak.spi.lifecycle.RepositoryInitializer;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SearchTest extends AbstractTest<SearchTest.TestContext> {

    private static final Logger LOG = LoggerFactory.getLogger(SearchTest.class);
    /**
     * Pattern used to find words and other searchable tokens within the
     * imported Wikipedia pages.
     */
    private static final Pattern WORD_PATTERN =
            Pattern.compile("\\p{LD}{3,}");

    private int maxSampleSize = 100;

    private final WikipediaImport importer;

    private final Set<String> sampleSet = new HashSet<>();

    private final Random random = new Random(42); //fixed seed

    private int count = 0;

    private int maxRowsToFetch = Integer.getInteger("maxRowsToFetch", 100);

    private TestContext defaultContext;

    /**
     * null means true; true means true
     */
    protected Boolean storageEnabled;

    protected ExecutorService executorService = Executors.newFixedThreadPool(2);

    protected File indexCopierDir;

    public SearchTest(File dump, boolean flat, boolean doReport, Boolean storageEnabled) {
        this.importer = new WikipediaImport(dump, flat, doReport) {
            @Override
            protected void pageAdded(String title, String text) {
                count++;
                if (count % 100 == 0
                        && sampleSet.size() < maxSampleSize
                        && text != null) {
                    List<String> words = new ArrayList<>();

                    if(isFullTextSearch()) {
                        Matcher matcher = WORD_PATTERN.matcher(text);
                        while (matcher.find()) {
                            words.add(matcher.group());
                        }

                        if (!words.isEmpty()) {
                            sampleSet.add(words.get(words.size() / 2));
                        }
                    } else {
                        // If it's a property search and
                        // not a fullText Search then, search on title
                        // Since text could consist of lengthy articles
                        sampleSet.add(title);
                    }

                }
            }
        };
        this.storageEnabled = storageEnabled;
        this.indexCopierDir = createTemporaryFolder(null);
    }

    @Override
    public void beforeSuite() throws Exception {
        random.setSeed(42);
        sampleSet.clear();
        count = 0;

        importer.importWikipedia(loginWriter());
        Thread.sleep(10); // allow some time for the indexer to catch up

        defaultContext = new TestContext();
    }

    @Override
    protected void afterSuite() throws Exception {
        executorService.shutdown();
        executorService.awaitTermination(1, TimeUnit.MINUTES);
        FileUtils.deleteDirectory(indexCopierDir);
    }

    @Override
    protected TestContext prepareThreadExecutionContext() {
        return new TestContext();
    }

    @Override
    protected void runTest() throws Exception {
        runTest(defaultContext);
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void runTest(TestContext ec) throws Exception {
        LOG.trace("Starting test execution");
        QueryManager qm = ec.session.getWorkspace().getQueryManager();
        // TODO verify why "order by jcr:score()" accounts for what looks
        // like > 20% of the perf lost in Collections.sort
        for (String word : ec.words) {
            String query = getQuery(word);
            Query q = qm.createQuery(query, queryType());
            QueryResult r = q.execute();
            RowIterator it = r.getRows();
            if (!it.hasNext()) {
                LOG.warn("No results found for the query - " + query);
            } else {
                for (int rows = 0; it.hasNext() && rows < maxRowsToFetch; rows++) {
                    Node n = it.nextRow().getNode();
                    LOG.trace("Result found for fulltext search on word " + word + "on path " + n.getPath());
                    ec.hash += n.getProperty("text").getString().hashCode();
                    ec.hash += n.getProperty("title").getString().hashCode();
                }
            }

        }
    }

    protected String queryType() {
        return Query.XPATH;
    }

    protected String getQuery(String word) {
        return "//*[jcr:contains(@text, '" + word + "')] ";
    }

    // Override this in extending class to return false if
    // test needs to query on property equality
    // List for words to query upon would be formed accordingly.
    protected boolean isFullTextSearch() {
        return true;
    }

    class TestContext {
        final Session session = loginWriter();
        final String[] words = getRandomWords();
        int hash = 0; // summary variable to prevent JIT compiler tricks
    }

    private String[] getRandomWords() {
        List<String> samples = newArrayList(sampleSet);
        String[] words = new String[100];
        for (int i = 0; i < words.length; i++) {
            words[i] = samples.get(random.nextInt(samples.size()));
        }
        return words;
    }

    private File createTemporaryFolder(File parentFolder) {
        File createdFolder = null;
        try {
            createdFolder = File.createTempFile("oak", "", parentFolder);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        createdFolder.delete();
        createdFolder.mkdir();
        return createdFolder;
    }


    protected class UUIDInitializer implements RepositoryInitializer {
        @Override
        public void initialize(@NotNull NodeBuilder builder) {

            NodeBuilder uuid = IndexUtils.createIndexDefinition(builder.child(INDEX_DEFINITIONS_NAME), "uuid", true, true,
                    ImmutableList.<String>of("jcr:uuid"), null);
            uuid.setProperty("info",
                    "Oak index for UUID lookup (direct lookup of nodes with the mixin 'mix:referenceable').");

        }
    }
}
