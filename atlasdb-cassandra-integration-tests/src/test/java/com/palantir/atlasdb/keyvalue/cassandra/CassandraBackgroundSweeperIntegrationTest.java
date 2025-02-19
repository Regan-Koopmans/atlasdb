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
package com.palantir.atlasdb.keyvalue.cassandra;

import com.palantir.atlasdb.containers.CassandraResource;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.sweep.AbstractBackgroundSweeperIntegrationTest;
import com.palantir.atlasdb.util.MetricsManagers;
import org.junit.ClassRule;

public class CassandraBackgroundSweeperIntegrationTest extends AbstractBackgroundSweeperIntegrationTest {
    @ClassRule
    public static final CassandraResource CASSANDRA =
            new CassandraResource(CassandraBackgroundSweeperIntegrationTest::createKeyValueService);

    @Override
    protected KeyValueService getKeyValueService() {
        return CASSANDRA.getDefaultKvs();
    }

    private static KeyValueService createKeyValueService() {
        return CassandraKeyValueServiceImpl.create(
                MetricsManagers.createForTests(),
                CASSANDRA.getConfig(),
                CassandraTestTools.getMutationProviderWithStartingTimestamp(1_000_000, services));
    }
}
