/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
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
package com.palantir.atlasdb.sweep;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.palantir.atlasdb.cleaner.Follower;
import com.palantir.atlasdb.keyvalue.api.Cell;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.keyvalue.api.Namespace;
import com.palantir.atlasdb.keyvalue.api.TableReference;
import com.palantir.atlasdb.transaction.api.Transaction;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class CellsSweeperShould {
    private static final TableReference TABLE_REFERENCE = TableReference.create(Namespace.create("ns"), "testTable");
    private static final Cell SINGLE_CELL =
            Cell.create("cellRow".getBytes(StandardCharsets.UTF_8), "cellCol".getBytes(StandardCharsets.UTF_8));

    private static final ImmutableSet<Cell> SINGLE_CELL_SET = ImmutableSet.of(SINGLE_CELL);
    private static final ImmutableMultimap<Cell, Long> SINGLE_CELL_TS_PAIR = ImmutableMultimap.<Cell, Long>builder()
            .putAll(
                    Cell.create(
                            "cellPairRow".getBytes(StandardCharsets.UTF_8),
                            "cellPairCol".getBytes(StandardCharsets.UTF_8)),
                    ImmutableSet.of(5L, 10L, 15L, 20L))
            .build();

    private final KeyValueService mockKvs = mock(KeyValueService.class);
    private final Follower mockFollower = mock(Follower.class);

    private final CellsSweeper sweeper = new CellsSweeper(null, mockKvs, ImmutableList.of(mockFollower));

    @Test
    public void ensureCellSweepDeletesCells() {
        sweeper.sweepCells(TABLE_REFERENCE, SINGLE_CELL_TS_PAIR, ImmutableSet.of());

        verify(mockKvs).delete(TABLE_REFERENCE, SINGLE_CELL_TS_PAIR);
    }

    @Test
    public void ensureSentinelsAreAddedToKvs() {
        sweeper.sweepCells(TABLE_REFERENCE, SINGLE_CELL_TS_PAIR, SINGLE_CELL_SET);

        verify(mockKvs).addGarbageCollectionSentinelValues(TABLE_REFERENCE, SINGLE_CELL_SET);
    }

    @Test
    public void ensureFollowersRunAgainstCellsToSweep() {
        sweeper.sweepCells(TABLE_REFERENCE, SINGLE_CELL_TS_PAIR, ImmutableSet.of());

        verify(mockFollower)
                .run(any(), any(), eq(SINGLE_CELL_TS_PAIR.keySet()), eq(Transaction.TransactionType.HARD_DELETE));
    }

    @Test
    public void sentinelsArentAddedIfNoCellsToSweep() {
        sweeper.sweepCells(TABLE_REFERENCE, ImmutableMultimap.of(), SINGLE_CELL_SET);

        verify(mockKvs, never()).addGarbageCollectionSentinelValues(TABLE_REFERENCE, SINGLE_CELL_SET);
    }

    @Test
    public void ensureNoActionTakenIfNoCellsToSweep() {
        sweeper.sweepCells(TABLE_REFERENCE, ImmutableMultimap.of(), ImmutableSet.of());

        verify(mockKvs, never()).delete(any(), any());
        verify(mockKvs, never()).addGarbageCollectionSentinelValues(any(), any());
    }
}
