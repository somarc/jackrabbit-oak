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
package org.apache.jackrabbit.oak.segment.http.server.binary;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Optional;

import static org.junit.Assert.assertEquals;

public class CidMappingServiceTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void aRemovedMappingIsNoLongerKnownInEitherDirection() throws Exception {
        String blob = "ab".repeat(32);
        try (CidMappingService service = new CidMappingService(folder.getRoot().toPath())) {
            service.registerMapping(blob + "#40000", "QmCid");
            service.registerMapping("cd".repeat(32), "QmOther");

            service.removeMapping(blob + "#40000");
            service.removeMapping(null);
            service.removeMapping("ef".repeat(32));

            assertEquals(Optional.empty(), service.getCid(blob));
            assertEquals(Optional.empty(), service.getOakBlobId("QmCid"));
            assertEquals(Optional.of("cd".repeat(32)), service.getOakBlobId("QmOther"));
        }
    }
}
