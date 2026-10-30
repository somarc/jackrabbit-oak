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
package org.apache.jackrabbit.oak.blob.cloud.ipfs;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Random;

import static org.junit.Assert.assertEquals;

/**
 * Shows that the CIDs Oak records depend only on the content, not on a node's
 * own {@code Import.*} settings, for a file large enough to need more than one
 * level of UnixFS links.
 */
public class IPFSKuboImportProfileTest {

    /**
     * CID of {@link #deterministicFile} under Oak's pinned import profile, as
     * also produced by {@code ipfs add --only-hash --cid-version=0
     * --raw-leaves=false --chunker=size-262144} on Kubo 0.43.1.
     */
    private static final String GOLDEN_CID = "Qma7P9KW6iSj5e9J6b4HSzAtzmy9WS69YsxNKktQX88H4i";

    @ClassRule
    public static final KuboDockerRule DEFAULT_NODE = new KuboDockerRule();

    @ClassRule
    public static final KuboDockerRule SKEWED_NODE = new KuboDockerRule()
        .configured("Import.CidVersion", "1")
        .configured("Import.UnixFSRawLeaves", "true")
        .configured("Import.UnixFSChunker", "\"size-1048576\"")
        .configured("Import.UnixFSFileMaxLinks", "16")
        .configured("Import.HashFunction", "\"sha2-512\"");

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testNodeImportSettingsDoNotChangeTheCid() throws Exception {
        File file = deterministicFile();

        String onDefault = new DefaultIpfsClient(DEFAULT_NODE.apiEndpoint()).add(file);
        String onSkewed = new DefaultIpfsClient(SKEWED_NODE.apiEndpoint()).add(file);

        assertEquals(onDefault, onSkewed);
        assertEquals(GOLDEN_CID, onDefault);
    }

    /**
     * 48 MiB: past 174 links of 256 KiB, so the DAG has two levels.
     */
    private File deterministicFile() throws Exception {
        File file = folder.newFile("deterministic.bin");
        Random random = new Random(20261030L);
        byte[] chunk = new byte[1024 * 1024];
        try (OutputStream out = Files.newOutputStream(file.toPath())) {
            for (int i = 0; i < 48; i++) {
                random.nextBytes(chunk);
                out.write(chunk);
            }
        }
        return file;
    }
}
