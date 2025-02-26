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
package org.apache.jackrabbit.oak.plugins.document;

import static org.apache.jackrabbit.oak.commons.conditions.Validate.checkArgument;
import static org.apache.jackrabbit.oak.plugins.document.Collection.NODES;
import static org.apache.jackrabbit.oak.plugins.document.NodeDocument.SplitDocType.INTERMEDIATE;
import static org.apache.jackrabbit.oak.plugins.document.NodeDocument.SplitDocType.NONE;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.jackrabbit.oak.commons.log.LogSilencer;
import org.apache.jackrabbit.oak.plugins.document.VersionGarbageCollector.VersionGCStats;
import org.apache.jackrabbit.oak.plugins.document.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements a split document cleanup.
 */
public class SplitDocumentCleanUp implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(SplitDocumentCleanUp.class);

    private static final LogSilencer LOG_SILENCER = new LogSilencer();

    // number of document IDs to collect before removing them in a single call
    private static final int DELETE_BATCH_SIZE = 100;

    protected final DocumentStore store;
    protected final Iterable<NodeDocument> splitDocGarbage;
    protected final VersionGCStats stats;
    protected final List<String> idsToBeDeleted = new ArrayList<>();
    protected int deleteCount;

    protected SplitDocumentCleanUp(DocumentStore store,
                                   VersionGCStats stats,
                                   Iterable<NodeDocument> splitDocGarbage) {
        this.store = store;
        this.stats = stats;
        this.splitDocGarbage = splitDocGarbage;
    }

    protected SplitDocumentCleanUp disconnect() {
        for (NodeDocument splitDoc : splitDocGarbage) {
            disconnect(splitDoc);
            collectIdToBeDeleted(splitDoc.getId());
        }
        return this;
    }

    /**
     * Collects document IDs for subsequent deletion.
     * <p>
     * Implementations that override
     * {@link SplitDocumentCleanUp#deleteSplitDocuments()} should override this
     * method as well.
     */
    protected void collectIdToBeDeleted(String id) {
        idsToBeDeleted.add(id);
        // proceed to delete early if we reach DELETE_BATCH_SIZE
        if (idsToBeDeleted.size() >= DELETE_BATCH_SIZE) {
            removeFromDocumentStore(idsToBeDeleted);
            deleteCount += idsToBeDeleted.size();
            idsToBeDeleted.clear();
        }
    }

    protected int deleteSplitDocuments() {
        removeFromDocumentStore(idsToBeDeleted);
        return idsToBeDeleted.size() + deleteCount;
    }

    private void removeFromDocumentStore(List<String> ids) {
        try {
            stats.deleteSplitDocs.start();
            store.remove(Collection.NODES, ids);
        } finally {
            stats.deleteSplitDocs.stop();
        }
    }

    private void removeFromDocumentStore(String id) {
        try {
            stats.deleteSplitDocs.start();
            store.remove(Collection.NODES, id);
        } finally {
            stats.deleteSplitDocs.stop();
        }
    }

    private void disconnect(NodeDocument splitDoc) {
        String splitId = splitDoc.getId();
        String mainId = Utils.getIdFromPath(splitDoc.getMainPath());
        NodeDocument doc = store.find(NODES, mainId);
        if (doc == null) {
            LOG.warn("Main document {} already removed. Split document is {}",
                    mainId, splitId);
            return;
        }

        Path splitDocPath = splitDoc.getPath();
        int height = Integer.parseInt(splitDocPath.getName());
        Revision rev = Revision.fromString(splitDocPath.getParent().getName());
        doc = doc.findPrevReferencingDoc(rev, height);
        if (doc == null) {
            LOG.warn("Split document {} for path {} not referenced anymore. Main document is {}",
                    splitId, splitDocPath, mainId);
            return;
        }
        // remove reference
        if (doc.getSplitDocType() == INTERMEDIATE) {
            disconnectFromIntermediate(doc, rev);
        } else {
            markStaleOnMain(doc, rev, height);
        }
    }

    private void disconnectFromIntermediate(NodeDocument splitDoc,
                                            Revision rev) {
        checkArgument(splitDoc.getSplitDocType() == INTERMEDIATE,
                "Illegal type: %s", splitDoc.getSplitDocType());

        String splitDocId = splitDoc.getId();
        UpdateOp update = new UpdateOp(splitDocId, false);
        NodeDocument.removePrevious(update, rev);
        NodeDocument old = store.findAndUpdate(NODES, update);
        if (old != null
                && old.getPreviousRanges().size() == 1
                && old.getPreviousRanges().containsKey(rev)) {
            // this was the last reference on an intermediate split doc
            disconnect(old);
            removeFromDocumentStore(old.getId());
            stats.intermediateSplitDocGCCount++;
        } else if (old == null) {
            // OAK-9601 : suspected root cause: if the "previous" entry
            // could not be removed (tbd, maybe modCnt mismatch, race condition etc),
            // it would not properly have done the disconnect()
            // and perhaps that is why we get these
            // "Document with previous revisions not found"
            if (!LOG_SILENCER.silence(splitDocId)) {
                LOG.warn("Split document reference could not be removed from intermediate {} -{}",
                        splitDocId, LogSilencer.SILENCING_POSTFIX);
            } else {
                LOG.debug("Split document reference could not be removed from intermediate {}",
                        splitDocId);
            }
        }
    }

    final void markStaleOnMain(NodeDocument main,
                               Revision rev,
                               int height) {
        checkArgument(main.getSplitDocType() == NONE,
                "Illegal type: %s", main.getSplitDocType());

        UpdateOp update = new UpdateOp(main.getId(), false);
        NodeDocument.setStalePrevious(update, rev, height);
        store.findAndUpdate(NODES, update);
    }

    @Override
    public void close() throws IOException {
        Utils.closeIfCloseable(splitDocGarbage);
    }
}
