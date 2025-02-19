/*
 * (c) Copyright 2021 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.atlasdb.pue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.palantir.atlasdb.keyvalue.api.Cell;
import com.palantir.atlasdb.keyvalue.api.CheckAndSetException;
import com.palantir.atlasdb.keyvalue.api.KeyAlreadyExistsException;
import com.palantir.atlasdb.keyvalue.api.TableReference;
import com.palantir.atlasdb.keyvalue.impl.InMemoryKeyValueService;
import com.palantir.atlasdb.transaction.encoding.TwoPhaseEncodingStrategy;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class ResilientCommitTimestampPutUnlessExistsTableTest {
    private static final String VALIDATING_STAGING_VALUES = "validating staging values";
    private static final String NOT_VALIDATING_STAGING_VALUES = "not validating staging values";

    private final UnreliableInMemoryKvs kvs = new UnreliableInMemoryKvs();
    private final ConsensusForgettingStore spiedStore =
            spy(new KvsConsensusForgettingStore(kvs, TableReference.createFromFullyQualifiedName("test.table")));

    private final PutUnlessExistsTable<Long, Long> pueTable;

    @SuppressWarnings("unchecked") // Guaranteed given implementation of data()
    public ResilientCommitTimestampPutUnlessExistsTableTest(String name, Object parameter) {
        pueTable = new ResilientCommitTimestampPutUnlessExistsTable(
                spiedStore, TwoPhaseEncodingStrategy.INSTANCE, ((Supplier<Boolean>) parameter));
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        Object[][] data = new Object[][] {
            {VALIDATING_STAGING_VALUES, (Supplier<Boolean>) () -> false},
            {NOT_VALIDATING_STAGING_VALUES, (Supplier<Boolean>) () -> true}
        };
        return Arrays.asList(data);
    }

    @Test
    public void canPutAndGet() throws ExecutionException, InterruptedException {
        pueTable.putUnlessExists(1L, 2L);
        assertThat(pueTable.get(1L).get()).isEqualTo(2L);

        verify(spiedStore).putUnlessExists(anyMap());
        verify(spiedStore, atLeastOnce()).put(anyMap());
        verify(spiedStore).getMultiple(any());
    }

    @Test
    public void emptyReturnsNull() throws ExecutionException, InterruptedException {
        assertThat(pueTable.get(3L).get()).isNull();
    }

    @Test
    public void cannotPueTwice() {
        pueTable.putUnlessExists(1L, 2L);
        assertThatThrownBy(() -> pueTable.putUnlessExists(1L, 2L)).isInstanceOf(KeyAlreadyExistsException.class);
    }

    @Test
    public void canPutAndGetMultiple() throws ExecutionException, InterruptedException {
        ImmutableMap<Long, Long> inputs = ImmutableMap.of(1L, 2L, 3L, 4L, 7L, 8L);
        pueTable.putUnlessExistsMultiple(inputs);
        assertThat(pueTable.get(ImmutableList.of(1L, 3L, 5L, 7L)).get()).containsExactlyInAnyOrderEntriesOf(inputs);
    }

    @Test
    public void pueThatThrowsIsCorrectedOnGet() throws ExecutionException, InterruptedException {
        kvs.setThrowOnNextPue();
        assertThatThrownBy(() -> pueTable.putUnlessExists(1L, 2L)).isInstanceOf(RuntimeException.class);
        verify(spiedStore, never()).put(anyMap());

        assertThat(pueTable.get(1L).get()).isEqualTo(2L);
        verify(spiedStore).put(anyMap());
    }

    @Test
    public void getReturnsStagingValuesThatWereCommittedBySomeoneElse()
            throws ExecutionException, InterruptedException {
        TwoPhaseEncodingStrategy strategy = TwoPhaseEncodingStrategy.INSTANCE;

        long startTimestamp = 1L;
        long commitTimestamp = 2L;
        Cell timestampAsCell = strategy.encodeStartTimestampAsCell(startTimestamp);
        byte[] stagingValue =
                strategy.encodeCommitTimestampAsValue(startTimestamp, PutUnlessExistsValue.staging(commitTimestamp));
        byte[] committedValue =
                strategy.encodeCommitTimestampAsValue(startTimestamp, PutUnlessExistsValue.committed(commitTimestamp));
        spiedStore.putUnlessExists(timestampAsCell, stagingValue);

        List<byte[]> actualValues = ImmutableList.of(committedValue);

        doThrow(new CheckAndSetException("done elsewhere", timestampAsCell, stagingValue, actualValues))
                .when(spiedStore)
                .checkAndTouch(timestampAsCell, stagingValue);

        assertThat(pueTable.get(startTimestamp).get()).isEqualTo(commitTimestamp);
    }

    @Test
    public void onceNonNullValueIsReturnedItIsAlwaysReturned() {
        PutUnlessExistsTable<Long, Long> putUnlessExistsTable = new ResilientCommitTimestampPutUnlessExistsTable(
                new CassandraImitatingConsensusForgettingStore(0.5d), TwoPhaseEncodingStrategy.INSTANCE);

        for (long startTs = 1L; startTs < 1000; startTs++) {
            long ts = startTs;
            List<Long> successfulCommitTs = IntStream.range(0, 3)
                    .mapToObj(offset -> tryPue(putUnlessExistsTable, ts, ts + offset))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .collect(Collectors.toList());
            assertThat(successfulCommitTs).hasSizeLessThanOrEqualTo(1);

            Optional<Long> onlyAllowedCommitTs = successfulCommitTs.stream().findFirst();
            for (int i = 0; i < 30; i++) {
                Long valueRead = firstSuccessfulRead(putUnlessExistsTable, startTs);
                onlyAllowedCommitTs.ifPresentOrElse(
                        commit -> assertThat(valueRead).isEqualTo(commit),
                        () -> assertThat(valueRead).isIn(null, ts, ts + 1, ts + 2));
                onlyAllowedCommitTs = Optional.ofNullable(valueRead);
            }
        }
    }

    private static Optional<Long> tryPue(
            PutUnlessExistsTable<Long, Long> putUnlessExistsTable, long startTs, long commitTs) {
        try {
            putUnlessExistsTable.putUnlessExists(startTs, commitTs);
            return Optional.of(commitTs);
        } catch (Exception e) {
            // this is ok, we may have failed because it already exists or randomly. Either way, continue.
            return Optional.empty();
        }
    }

    private static Long firstSuccessfulRead(PutUnlessExistsTable<Long, Long> putUnlessExistsTable, long ts) {
        while (true) {
            try {
                return putUnlessExistsTable.get(ts).get();
            } catch (Exception e) {
                // this is ok, when we try to read we may end up doing a write, which can throw -- we will retry
            }
        }
    }

    private static class UnreliableInMemoryKvs extends InMemoryKeyValueService {
        private boolean throwOnNextPue = false;

        public UnreliableInMemoryKvs() {
            super(true);
        }

        void setThrowOnNextPue() {
            throwOnNextPue = true;
        }

        @Override
        public void putUnlessExists(TableReference tableRef, Map<Cell, byte[]> values) {
            super.putUnlessExists(tableRef, values);
            if (throwOnNextPue) {
                throwOnNextPue = false;
                throw new RuntimeException("Ohno!");
            }
        }
    }
}
