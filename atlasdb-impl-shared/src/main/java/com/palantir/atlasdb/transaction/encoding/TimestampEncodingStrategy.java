/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.atlasdb.transaction.encoding;

/**
 * Strategy for encoding start timestamps as cells, possibly for persistence.
 *
 * It is expected that decode and encode for both start and commit timestamps should be mutually inverse, so that
 * encoding and then decoding is a no-op. That is, for any timestamp ts, the following should hold:
 *
 * - decodeCellAsStartTimestamp(encodeStartTimestampAsCell(ts)) == ts
 * - for any timestamp ts', decodeValueAsCommitTimestamp(ts', encodeCommitTimestampAsValue(ts', ts)) == ts
 *
 */
public interface TimestampEncodingStrategy<V> extends CellEncodingStrategy {
    byte[] encodeCommitTimestampAsValue(long startTimestamp, V commitTimestamp);

    V decodeValueAsCommitTimestamp(long startTimestamp, byte[] value);
}
