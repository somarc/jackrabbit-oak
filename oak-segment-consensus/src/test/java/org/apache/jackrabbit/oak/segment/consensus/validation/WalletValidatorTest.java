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
package org.apache.jackrabbit.oak.segment.consensus.validation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WalletValidatorTest {

    private static final String HEX40 = "742d35Cc6634C0532925a3b844Bc9e7595f0bEb0";

    @Test
    public void acceptsExactly40HexCaseInsensitiveAndLowercases() {
        ValidationResult<String> result = WalletValidator.validate("  0x" + HEX40 + " ");

        assertTrue(result.isValid());
        assertEquals("0x" + HEX40.toLowerCase(), result.getNormalizedValue());
        assertNull(result.getError());
        assertEquals(200, result.getHttpStatus());
    }

    @Test
    public void rejects39Hex() {
        assertRejected("0x" + HEX40.substring(1));
    }

    @Test
    public void rejects41Hex() {
        assertRejected("0x" + HEX40 + "a");
    }

    @Test
    public void rejectsEightHexThatUsedToPass() {
        assertRejected("0x12345678");
    }

    @Test
    public void rejectsNonHex() {
        assertRejected("0x" + HEX40.substring(1) + "g");
    }

    @Test
    public void rejectsMissingPrefix() {
        ValidationResult<String> result = assertRejected(HEX40 + "ab");
        assertTrue(result.getError().contains("'0x' prefix"));
    }

    @Test
    public void rejectsUppercasePrefix() {
        assertRejected("0X" + HEX40);
    }

    @Test
    public void rejectsNullAndEmpty() {
        assertTrue(assertRejected(null).getError().startsWith("Missing wallet address"));
        assertRejected("");
    }

    @Test
    public void truncatesLongValuesInErrors() {
        ValidationResult<String> result = assertRejected("0x" + HEX40 + HEX40);
        assertTrue(result.getError().endsWith("0x742d35Cc6634C05..."));
    }

    private static ValidationResult<String> assertRejected(String wallet) {
        ValidationResult<String> result = WalletValidator.validate(wallet);
        assertFalse(result.isValid());
        assertNull(result.getNormalizedValue());
        assertEquals(400, result.getHttpStatus());
        return result;
    }
}
