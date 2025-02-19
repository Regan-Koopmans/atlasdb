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
package com.palantir.atlasdb.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.palantir.atlasdb.AtlasDbConstants;
import com.palantir.atlasdb.cache.TimestampCache;
import com.palantir.atlasdb.keyvalue.api.LockWatchCachingConfig;
import com.palantir.atlasdb.memory.InMemoryAtlasDbConfig;
import com.palantir.atlasdb.spi.KeyValueServiceConfig;
import com.palantir.atlasdb.sweep.queue.config.TargetedSweepInstallConfig;
import com.palantir.exception.NotInitializedException;
import com.palantir.logsafe.Preconditions;
import com.palantir.logsafe.exceptions.SafeIllegalStateException;
import com.palantir.logsafe.logger.SafeLogger;
import com.palantir.logsafe.logger.SafeLoggerFactory;
import java.util.Optional;
import javax.annotation.Nullable;
import org.immutables.value.Value;

@JsonDeserialize(as = ImmutableAtlasDbConfig.class)
@JsonSerialize(as = ImmutableAtlasDbConfig.class)
@JsonIgnoreProperties("enableSweep")
@Value.Immutable
public abstract class AtlasDbConfig {

    private static final SafeLogger log = SafeLoggerFactory.get(AtlasDbConfig.class);

    public abstract KeyValueServiceConfig keyValueService();

    public abstract Optional<LeaderConfig> leader();

    public abstract Optional<TimeLockClientConfig> timelock();

    public abstract Optional<ServerListConfig> lock();

    public abstract Optional<ServerListConfig> timestamp();

    public abstract Optional<PersistentStorageConfig> persistentStorage();

    /**
     * A namespace refers to a String that is used to identify this AtlasDB client to the relevant timestamp, lock and
     * key value services. Currently, this only applies to external timelock services, and Cassandra KVS (where it is
     * used as the keyspace).
     *
     * For backwards compatibility reasons, this is optional. If no namespace is specified:
     *   - if using Cassandra, the keyspace must be explicitly specified.
     *   - if using TimeLock, the client name must be explicitly specified.
     *
     * If a namespace is specified and a Cassandra keyspace / TimeLock client name is also explicitly specified, then
     * AtlasDB will fail to start if these are contradictory.
     */
    public abstract Optional<String> namespace();

    /**
     * The transaction read timeout is the maximum amount of
     * time a read only transaction can safely run. Read only
     * transactions that run any longer may fail if they attempt
     * to perform additional reads.
     * <p>
     * The benefit of making this smaller is making overwritten
     * data 'unreadable' more quickly. This allows the background
     * sweeper to delete overwritten data sooner.
     */
    @Value.Default
    public long getTransactionReadTimeoutMillis() {
        return AtlasDbConstants.DEFAULT_TRANSACTION_READ_TIMEOUT;
    }

    /**
     * The punch interval is how frequently a row mapping the
     * current wall clock time to the maximum timestamp is
     * recorded.
     * <p>
     * These records allow wall clock times (used by the
     * transaction read timeout) to be translated to timestamps.
     */
    @Value.Default
    public long getPunchIntervalMillis() {
        return AtlasDbConstants.DEFAULT_PUNCH_INTERVAL_MILLIS;
    }

    /**
     * Scrubbing is the process of removing overwritten or deleted
     * cells from the underlying key value store after a hard-delete
     * transaction has committed (as opposed to shadowing such data,
     * which still leaves the data available to transactions that
     * started before the overwrite or deletion).
     * <p>
     * Scrubbing non-aggressively will cause scrubbing to be delayed
     * until the transaction read timeout passes ensuring that no
     * (well behaved, shorter than read timeout) transactions will
     * attempt to read scrubbed data. (Note: Badly behaved transactions
     * that do so will abort with an exception).
     * <p>
     * Scrubbing aggressively will cause the deletion to occur
     * immediately, which will cause any active transactions that
     * attempt to read the deleted cell to abort and fail with an
     * exception.
     */
    @Value.Default
    public boolean backgroundScrubAggressively() {
        return AtlasDbConstants.DEFAULT_BACKGROUND_SCRUB_AGGRESSIVELY;
    }

    /**
     * The number of background threads to use to perform scrubbing.
     */
    @Value.Default
    public int getBackgroundScrubThreads() {
        return AtlasDbConstants.DEFAULT_BACKGROUND_SCRUB_THREADS;
    }

    /**
     * The number of background threads to use to read from the scrub queue.
     */
    @Value.Default
    public int getBackgroundScrubReadThreads() {
        return AtlasDbConstants.DEFAULT_BACKGROUND_SCRUB_READ_THREADS;
    }

    /**
     * The frequency with which the background sweeper runs to clean up
     * cells that have been non-aggressively scrubbed.
     */
    @Value.Default
    public long getBackgroundScrubFrequencyMillis() {
        return AtlasDbConstants.DEFAULT_BACKGROUND_SCRUB_FREQUENCY_MILLIS;
    }

    /**
     * The number of cells to scrub per batch by the background scrubber.
     */
    @Value.Default
    public int getBackgroundScrubBatchSize() {
        return AtlasDbConstants.DEFAULT_BACKGROUND_SCRUB_BATCH_SIZE;
    }

    /**
     * If false, the KVS and classes that depend on it will only try to initialize synchronously and will throw on
     * failure, preventing AtlasDB from starting. This is consistent with the behaviour prior to implementing
     * asynchronous initialization.
     * <p>
     * If true, initialization will be attempted synchronously, but on failure we keep retrying asynchronously to start
     * AtlasDB. If a method is invoked on an not-yet-initialized
     * {@link com.palantir.atlasdb.transaction.api.TransactionManager} or other object, a
     * {@link NotInitializedException} will be thrown. Clients can register a
     * {@link com.palantir.atlasdb.http.NotInitializedExceptionMapper} if they wish to map this exception to a 503
     * status code.
     */
    @Value.Default
    public boolean initializeAsync() {
        return AtlasDbConstants.DEFAULT_INITIALIZE_ASYNC;
    }

    /**
     * Install time configurations for targeted sweep.
     */
    @Value.Default
    public TargetedSweepInstallConfig targetedSweep() {
        return TargetedSweepInstallConfig.defaultTargetedSweepConfig();
    }

    /**
     * Whether to run the background sweeper process at all. Note that this differs from
     * {@link AtlasDbRuntimeConfig#sweep()} in that configuration there assumes that the background sweeper threads will
     * still be scheduled, just that they find they do not need to do any work when they wake up. If this flag here
     * is set to false, this stops us from even running these threads. This saves on thread scheduling overhead,
     * at the expense of requiring a rolling bounce should one actually decide that one wishes to use Background Sweep.
     *
     * The JSON endpoint that may be mounted on a service to run an explicit background sweep is not affected.
     */
    @Value.Default
    public boolean runBackgroundSweepProcess() {
        // TODO (jkong): Confirm with large internal product owner that this is generally safe to set to false.
        return true;
    }

    /**
     * Install configuration for lock watch caching, including maximum cache size and validation probability.
     */
    @Value.Default
    public LockWatchCachingConfig lockWatchCaching() {
        return LockWatchCachingConfig.builder().build();
    }

    /**
     * The number of milliseconds to wait between each batch of cells
     * processed by the background sweeper.
     * @deprecated Use {@link AtlasDbRuntimeConfig#sweep()} {@link SweepConfig#pauseMillis()} to make this value
     * live-reloadable.
     */
    @Deprecated
    @SuppressWarnings("InlineMeSuggester")
    @Value.Default
    public long getSweepPauseMillis() {
        return AtlasDbConstants.DEFAULT_SWEEP_PAUSE_MILLIS;
    }

    /**
     * The number of milliseconds to wait between retries when the background sweeper can't delete data, due to the
     * persistent lock being taken.
     */
    @Value.Default
    public long getSweepPersistentLockWaitMillis() {
        return AtlasDbConstants.DEFAULT_SWEEP_PERSISTENT_LOCK_WAIT_MILLIS;
    }

    /**
     * The target number of (cell, timestamp) pairs to examine in a single run of the background sweeper.
     * @deprecated Use {@link AtlasDbRuntimeConfig#sweep()} {@link SweepConfig#readLimit()} to make this value
     * live-reloadable.
     */
    @Deprecated
    @Nullable
    public abstract Integer getSweepReadLimit();

    /**
     * The target number of candidate (cell, timestamp) pairs to load per batch while sweeping.
     * @deprecated Use {@link AtlasDbRuntimeConfig#sweep()} {@link SweepConfig#candidateBatchHint()} to make this value
     * live-reloadable.
     */
    @Deprecated
    @Nullable
    public abstract Integer getSweepCandidateBatchHint();

    /**
     * The target number of (cell, timestamp) pairs to delete at once while sweeping.
     * @deprecated Use {@link AtlasDbRuntimeConfig#sweep()} {@link SweepConfig#deleteBatchHint()} to make this value
     * live-reloadable.
     */
    @Deprecated
    @Nullable
    public abstract Integer getSweepDeleteBatchHint();

    /**
     * @deprecated Use {@link AtlasDbRuntimeConfig#sweep()}
     * {@link SweepConfig#readLimit()},
     * {@link SweepConfig#candidateBatchHint()}, and
     * {@link SweepConfig#deleteBatchHint()}.
     */
    @Deprecated
    @Nullable
    public abstract Integer getSweepBatchSize();

    /**
     * @deprecated Use {@link AtlasDbRuntimeConfig#sweep()}
     * {@link SweepConfig#readLimit()},
     * {@link SweepConfig#candidateBatchHint()}, and
     * {@link SweepConfig#deleteBatchHint()}.
     */
    @Deprecated
    @Nullable
    public abstract Integer getSweepCellBatchSize();

    /**
     * The time threshold for ProfilingKeyValueService to log a KVS operation for being slow.
     */
    @Value.Default
    public long getKvsSlowLogThresholdMillis() {
        return 1000;
    }

    /**
     * The default lock expiration time for requests to the lock service.
     */
    @Value.Default
    public int getDefaultLockTimeoutSeconds() {
        return AtlasDbConstants.DEFAULT_LOCK_TIMEOUT_SECONDS;
    }

    /**
     * If set, the timestamp cache that should be used by AtlasDB. If set, any timestamp cache sizing configuration
     * is ignored.
     */
    public abstract Optional<TimestampCache> timestampCache();

    /**
     * If set, allows the timelock and key-value-service namespaces to be different.
     *
     * Note that this only works on DBKvs. If using Cassandra, this behaviour is strictly not allowed. Please contact
     * the AtlasDB team if this is an issue.
     *
     * You should only be using this if you know what you are doing. Using this flag where it is not necessary
     * may result in increased risk of SEVERE DATA CORRUPTION.
     */
    @Value.Default
    public boolean enableNonstandardAndPossiblyErrorProneTopologyAllowDifferentKvsAndTimelockNamespaces() {
        return false;
    }

    @Value.Check
    protected final void check() {
        checkLeaderAndTimelockBlocks();
        checkLockAndTimestampBlocks();
        checkNamespaceConfigConsistent();
        checkSweepConfigs();
    }

    @Value.Derived
    public boolean remoteTimestampAndLockOrLeaderBlocksPresent() {
        return (timestamp().isPresent() && lock().isPresent()) || leader().isPresent();
    }

    private void checkSweepConfigs() {
        if (getSweepBatchSize() != null
                || getSweepCellBatchSize() != null
                || getSweepReadLimit() != null
                || getSweepCandidateBatchHint() != null
                || getSweepDeleteBatchHint() != null) {
            log.warn("Your configuration specifies sweep parameters on the install config. They will be ignored."
                    + " Please use the runtime config to specify them.");
        }
    }

    private void checkLeaderAndTimelockBlocks() {
        if (leader().isPresent()) {
            Preconditions.checkState(
                    areTimeAndLockConfigsAbsent(),
                    "If the leader block is present, then the lock and timestamp server blocks must both be absent.");
            Preconditions.checkState(
                    !timelock().isPresent(), "If the leader block is present, then the timelock block must be absent.");
            Preconditions.checkState(
                    !leader().get().leaders().isEmpty(), "Leader config must have at least one server.");
        }

        if (timelock().isPresent()) {
            Preconditions.checkState(
                    areTimeAndLockConfigsAbsent(),
                    "If the timelock block is present, then the lock and timestamp blocks must both be absent.");
        }
    }

    private void checkLockAndTimestampBlocks() {
        Preconditions.checkState(
                lock().isPresent() == timestamp().isPresent(),
                "Lock and timestamp server blocks must either both be present or both be absent.");
        checkServersListHasAtLeastOneServerIfPresent(lock());
        checkServersListHasAtLeastOneServerIfPresent(timestamp());
    }

    private static void checkServersListHasAtLeastOneServerIfPresent(Optional<ServerListConfig> serverListOptional) {
        serverListOptional.ifPresent(serverList -> Preconditions.checkState(
                serverList.hasAtLeastOneServer(), "Server list must have at least one server."));
    }

    private void checkNamespaceConfigConsistent() {
        if (namespace().isPresent()) {
            String presentNamespace = namespace().get();
            Preconditions.checkState(!presentNamespace.contains("\""), "Namespace should not be quoted");

            boolean allowDifferentKvsNamespace =
                    enableNonstandardAndPossiblyErrorProneTopologyAllowDifferentKvsAndTimelockNamespaces();
            keyValueService()
                    .namespace()
                    .ifPresent(kvsNamespace -> Preconditions.checkState(
                            kvsNamespace.equals(presentNamespace) || allowDifferentKvsNamespace,
                            "If present, keyspace/dbName/sid config should be the same as the"
                                    + " atlas root-level namespace config."));

            timelock()
                    .flatMap(TimeLockClientConfig::client)
                    .ifPresent(client -> Preconditions.checkState(
                            client.equals(presentNamespace),
                            "If present, the TimeLock client config should be the same as the"
                                    + " atlas root-level namespace config."));
            return;
        }

        // There is no top level namespace
        if (keyValueService() instanceof InMemoryAtlasDbConfig) {
            if (timelock().isPresent() && !timelock().get().client().isPresent()) {
                throw new SafeIllegalStateException("For InMemoryKVS, the TimeLock client should not be empty");
            }
            return;
        }

        // There is no top level namespace AND the config is not an in-memory config
        Preconditions.checkState(
                keyValueService().namespace().isPresent(),
                "Either the atlas root-level namespace" + " or the keyspace/dbName/sid config needs to be set.");
        String keyValueServiceNamespace = keyValueService().namespace().get();
        Preconditions.checkState(
                !keyValueServiceNamespace.contains("\""), "KeyValueService namespace should not be quoted");

        if (timelock().isPresent()) {
            TimeLockClientConfig timeLockConfig = timelock().get();

            Preconditions.checkState(
                    timeLockConfig.client().isPresent(),
                    "Either the atlas root-level namespace config or the TimeLock client config should be present.");

            if (keyValueService().type().equals("cassandra")
                    || !enableNonstandardAndPossiblyErrorProneTopologyAllowDifferentKvsAndTimelockNamespaces()) {
                Preconditions.checkState(
                        timeLockConfig.client().equals(Optional.of(keyValueServiceNamespace)),
                        "AtlasDB refused to start, in order to avoid potential data corruption."
                                + " Please contact AtlasDB support to remediate this. Specific steps are required;"
                                + " DO NOT ATTEMPT TO FIX THIS YOURSELF.");
            }
        }
    }

    private boolean areTimeAndLockConfigsAbsent() {
        return !lock().isPresent() && !timestamp().isPresent();
    }

    @JsonIgnore
    public AtlasDbConfig toOfflineConfig() {
        return ImmutableAtlasDbConfig.builder()
                .from(this)
                .leader(Optional.empty())
                .lock(Optional.empty())
                .timestamp(Optional.empty())
                .build();
    }
}
