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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.codahale.metrics.MetricRegistry;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.palantir.atlasdb.cassandra.CassandraCredentialsConfig;
import com.palantir.atlasdb.cassandra.CassandraKeyValueServiceConfig;
import com.palantir.atlasdb.cassandra.CassandraServersConfigs.ThriftHostsExtractingVisitor;
import com.palantir.atlasdb.cassandra.ImmutableDefaultConfig;
import com.palantir.atlasdb.keyvalue.cassandra.pool.CassandraServer;
import com.palantir.atlasdb.keyvalue.cassandra.pool.CassandraService;
import com.palantir.atlasdb.util.MetricsManagers;
import com.palantir.common.base.FunctionCheckedException;
import com.palantir.common.concurrent.InitializeableScheduledExecutorServiceSupplier;
import com.palantir.common.exception.AtlasDbDependencyException;
import com.palantir.conjure.java.api.config.service.HumanReadableDuration;
import com.palantir.tritium.metrics.registry.DefaultTaggedMetricRegistry;
import com.palantir.tritium.metrics.registry.MetricName;
import com.palantir.tritium.metrics.registry.TaggedMetricRegistry;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.cassandra.thrift.InvalidRequestException;
import org.jmock.lib.concurrent.DeterministicScheduler;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.OngoingStubbing;

public class CassandraClientPoolTest {
    private static final int POOL_REFRESH_INTERVAL_SECONDS = 3 * 60;
    private static final int TIME_BETWEEN_EVICTION_RUNS_SECONDS = 20;
    private static final int UNRESPONSIVE_HOST_BACKOFF_SECONDS = 5 * 60;
    private static final int DEFAULT_PORT = 5000;
    private static final String HOSTNAME_1 = "1.0.0.0";
    private static final String HOSTNAME_2 = "2.0.0.0";
    private static final String HOSTNAME_3 = "3.0.0.0";
    private static final InetSocketAddress HOST_1 = InetSocketAddress.createUnresolved(HOSTNAME_1, DEFAULT_PORT);
    private static final InetSocketAddress HOST_2 = InetSocketAddress.createUnresolved(HOSTNAME_2, DEFAULT_PORT);
    private static final InetSocketAddress HOST_3 = InetSocketAddress.createUnresolved(HOSTNAME_3, DEFAULT_PORT);

    private static final CassandraServer CASS_SERVER_1 = CassandraServer.of(HOST_1);
    private static final CassandraServer CASS_SERVER_2 = CassandraServer.of(HOST_2);
    private static final CassandraServer CASS_SERVER_3 = CassandraServer.of(HOST_3);

    private final MetricRegistry metricRegistry = new MetricRegistry();
    private final TaggedMetricRegistry taggedMetricRegistry = new DefaultTaggedMetricRegistry();

    private DeterministicScheduler deterministicExecutor = new DeterministicScheduler();
    private Set<CassandraServer> poolServers = new HashSet<>();

    private CassandraKeyValueServiceConfig config;
    private Blacklist blacklist;
    private CassandraService cassandra = mock(CassandraService.class);

    @Before
    public void setup() {
        config = mock(CassandraKeyValueServiceConfig.class);
        when(config.poolRefreshIntervalSeconds()).thenReturn(POOL_REFRESH_INTERVAL_SECONDS);
        when(config.timeBetweenConnectionEvictionRunsSeconds()).thenReturn(TIME_BETWEEN_EVICTION_RUNS_SECONDS);
        when(config.unresponsiveHostBackoffTimeSeconds()).thenReturn(UNRESPONSIVE_HOST_BACKOFF_SECONDS);
        when(config.credentials()).thenReturn(mock(CassandraCredentialsConfig.class));
        when(config.getKeyspaceOrThrow()).thenReturn("ks");
        blacklist = new Blacklist(config);

        doAnswer(invocation -> {
                    Set<InetSocketAddress> inetSocketAddresses =
                            config.servers().accept(new ThriftHostsExtractingVisitor());
                    return inetSocketAddresses.stream().map(CassandraServer::of).collect(Collectors.toSet());
                })
                .when(cassandra)
                .getInitialServerList();
        doAnswer(invocation -> poolServers.add(getInvocationAddress(invocation)))
                .when(cassandra)
                .addPool(any());
        doAnswer(invocation -> poolServers.add(getInvocationAddress(invocation)))
                .when(cassandra)
                .returnOrCreatePool(any(), any());
        doAnswer(invocation -> {
                    poolServers.remove(getInvocationAddress(invocation));
                    return mock(CassandraClientPoolingContainer.class);
                })
                .when(cassandra)
                .removePool(any());
        doAnswer(invocation -> poolServers.stream()
                        .collect(Collectors.toMap(x -> x, x -> mock(CassandraClientPoolingContainer.class))))
                .when(cassandra)
                .getPools();
        when(config.socketTimeoutMillis()).thenReturn(1);
    }

    @Test
    public void cassandraPoolMetricsMustBeRegisteredForThreePools() {
        clientPoolWithInitialProxies(ImmutableSet.of(HOST_1, HOST_2, HOST_3));
        assertThatMetricsArePresent(ImmutableSet.of("pool1", "pool2", "pool3"));
    }

    private void assertThatMetricsArePresent(ImmutableSet<String> poolNames) {
        assertThat(taggedMetricRegistry.getMetrics().keySet())
                .containsAll(poolNames.stream().map(this::getPoolMetricName).collect(Collectors.toSet()));
    }

    private MetricName getPoolMetricName(String poolName) {
        return MetricName.builder()
                .safeName(MetricRegistry.name(CassandraClientPoolingContainer.class, "created"))
                .safeTags(ImmutableMap.of("pool", poolName))
                .build();
    }

    @Test
    public void shouldNotAttemptMoreThanOneConnectionOnSuccess() {
        CassandraClientPool cassandraClientPool = clientPoolWithServersInCurrentPool(ImmutableSet.of(CASS_SERVER_1));
        cassandraClientPool.runWithRetryOnServer(CASS_SERVER_1, noOp());
        verifyNumberOfAttemptsOnHost(CASS_SERVER_1, cassandraClientPool, 1);
    }

    @Test
    public void shouldRetryOnSameNodeToFailureAndThenRedirect() {
        // TODO(ssouza): make 4 =
        // 1 + CassandraClientPoolImpl.MAX_TRIES_TOTAL - CassandraClientPoolImpl.MAX_TRIES_SAME_HOST
        int numHosts = 4;
        List<CassandraServer> serverList = new ArrayList<>();
        for (int i = 0; i < numHosts; i++) {
            serverList.add(getServerForIndex(i));
        }

        CassandraClientPoolImpl clientPool = throwingClientPoolWithServersInCurrentPool(
                ImmutableSet.copyOf(serverList), new SocketTimeoutException());
        assertThatThrownBy(() -> runNoopWithRetryOnHost(serverList.get(0), clientPool))
                .isInstanceOf(Exception.class);

        verifyNumberOfAttemptsOnHost(serverList.get(0), clientPool, CassandraClientPoolImpl.getMaxRetriesPerHost());
        for (int i = 1; i < numHosts; i++) {
            verifyNumberOfAttemptsOnHost(serverList.get(i), clientPool, 1);
        }
    }

    @Test
    public void shouldKeepRetryingIfNowhereToRedirectTo() {
        CassandraClientPoolImpl cassandraClientPool = throwingClientPoolWithServersInCurrentPool(
                ImmutableSet.of(CASS_SERVER_1), new SocketTimeoutException());

        assertThatThrownBy(() -> runNoopWithRetryOnHost(CASS_SERVER_1, cassandraClientPool))
                .isInstanceOf(Exception.class);
        verifyNumberOfAttemptsOnHost(CASS_SERVER_1, cassandraClientPool, CassandraClientPoolImpl.getMaxTriesTotal());
    }

    @Test
    public void testRequestFailureMetricsWithConnectionException() {
        runTwoNoopsOnTwoHostsAndThrowFromSecondRunOnFirstHost(
                new SocketTimeoutException("test_socket_timeout_exception"));
        verifyAggregateFailureMetrics(0.25, 0.25);
    }

    @Test
    public void testRequestFailureMetricsWithNoConnectionException() {
        runTwoNoopsOnTwoHostsAndThrowFromSecondRunOnFirstHost(
                new NoSuchElementException("test_non_connection_exception"));
        verifyAggregateFailureMetrics(0.25, 0.0);
    }

    private void runTwoNoopsOnTwoHostsAndThrowFromSecondRunOnFirstHost(Exception exception) {
        CassandraClientPoolImpl cassandraClientPool =
                clientPoolWithServersInCurrentPool(ImmutableSet.of(CASS_SERVER_1, CASS_SERVER_2));

        runNoopOnHost(CASS_SERVER_1, cassandraClientPool);
        runNoopOnHost(CASS_SERVER_2, cassandraClientPool);
        runNoopOnHost(CASS_SERVER_2, cassandraClientPool);

        CassandraClientPoolingContainer container =
                cassandraClientPool.getCurrentPools().get(CASS_SERVER_1);
        setFailureModeForHost(container, exception);

        assertThatThrownBy(() -> runNoopOnHost(CASS_SERVER_1, cassandraClientPool))
                .isInstanceOf(Exception.class);
    }

    private CassandraServer getServerForIndex(int index) {
        return CassandraServer.of(InetSocketAddress.createUnresolved(Integer.toString(index), index));
    }

    @Test
    public void testBlacklistMetrics() {
        CassandraClientPool cassandraClientPool =
                clientPoolWithServersInCurrentPool(ImmutableSet.of(CASS_SERVER_1, CASS_SERVER_2));
        CassandraClientPoolingContainer container =
                cassandraClientPool.getCurrentPools().get(CASS_SERVER_1);
        runNoopWithRetryOnHost(CASS_SERVER_1, cassandraClientPool);
        verifyBlacklistMetric(0);
        setFailureModeForHost(container, new SocketTimeoutException());
        runNoopWithRetryOnHost(CASS_SERVER_1, cassandraClientPool);
        verifyBlacklistMetric(1);
    }

    @Test
    public void successfulRequestCausesHostToBeRemovedFromBlacklist() {
        CassandraClientPool cassandraClientPool = clientPoolWithServersInCurrentPool(ImmutableSet.of(CASS_SERVER_1));
        CassandraClientPoolingContainer container =
                cassandraClientPool.getCurrentPools().get(CASS_SERVER_1);
        AtomicBoolean fail = new AtomicBoolean(true);
        setConditionalTimeoutFailureForHost(container, unused -> fail.get());

        assertThatThrownBy(() -> runNoopWithRetryOnHost(CASS_SERVER_1, cassandraClientPool))
                .isInstanceOf(AtlasDbDependencyException.class);
        assertThat(blacklist.contains(CASS_SERVER_1)).isTrue();

        fail.set(false);

        runNoopWithRetryOnHost(CASS_SERVER_1, cassandraClientPool);
        assertThat(blacklist.contains(CASS_SERVER_1)).isFalse();
    }

    @Test
    public void resilientToRollingRestarts() {
        CassandraClientPool cassandraClientPool =
                clientPoolWithServersInCurrentPool(ImmutableSet.of(CASS_SERVER_1, CASS_SERVER_2));
        AtomicReference<CassandraServer> downHost = new AtomicReference<>(CASS_SERVER_1);
        cassandraClientPool
                .getCurrentPools()
                .values()
                .forEach(pool -> setConditionalTimeoutFailureForHost(
                        pool, container -> container.getCassandraServer().equals(downHost.get())));

        runNoopWithRetryOnHost(CASS_SERVER_1, cassandraClientPool);
        assertThat(blacklist.contains(CASS_SERVER_1)).isTrue();

        downHost.set(CASS_SERVER_2);

        runNoopWithRetryOnHost(CASS_SERVER_2, cassandraClientPool);
        assertThat(blacklist.contains(CASS_SERVER_1)).isFalse();
    }

    @Test
    public void attemptsShouldBeCountedPerHost() {
        when(config.servers())
                .thenReturn(ImmutableDefaultConfig.builder().addThriftHosts().build());
        CassandraClientPoolImpl cassandraClientPool = CassandraClientPoolImpl.createImplForTest(
                MetricsManagers.of(metricRegistry, taggedMetricRegistry),
                config,
                CassandraClientPoolImpl.StartupChecks.DO_NOT_RUN,
                blacklist);

        host(CASS_SERVER_1)
                .throwsException(new SocketTimeoutException())
                .throwsException(new InvalidRequestException())
                .inPool(cassandraClientPool);

        host(CASS_SERVER_2).throwsException(new SocketTimeoutException()).inPool(cassandraClientPool);

        runNoopWithRetryOnHost(CASS_SERVER_1, cassandraClientPool);
        assertThat(blacklist.contains(CASS_SERVER_2)).isFalse();
    }

    @Test
    public void hostIsAutomaticallyRemovedOnStartup() {
        when(config.servers())
                .thenReturn(ImmutableDefaultConfig.builder()
                        .addThriftHosts(CASS_SERVER_1.proxy(), CASS_SERVER_2.proxy(), CASS_SERVER_3.proxy())
                        .build());
        when(config.autoRefreshNodes()).thenReturn(true);

        setCassandraServersTo(CASS_SERVER_1);

        createClientPool();
        assertThat(poolServers).containsExactlyInAnyOrder(CASS_SERVER_1);
    }

    @Test
    public void hostIsAutomaticallyRemovedOnRefresh() {
        when(config.servers())
                .thenReturn(ImmutableDefaultConfig.builder()
                        .addThriftHosts(CASS_SERVER_1.proxy(), CASS_SERVER_2.proxy(), CASS_SERVER_3.proxy())
                        .build());
        when(config.autoRefreshNodes()).thenReturn(true);

        setCassandraServersTo(CASS_SERVER_1, CASS_SERVER_2, CASS_SERVER_3);

        createClientPool();
        assertThat(poolServers).containsExactlyInAnyOrder(CASS_SERVER_1, CASS_SERVER_2, CASS_SERVER_3);

        setCassandraServersTo(CASS_SERVER_1, CASS_SERVER_2);
        deterministicExecutor.tick(config.poolRefreshIntervalSeconds(), TimeUnit.SECONDS);
        assertThat(poolServers).containsExactlyInAnyOrder(CASS_SERVER_1, CASS_SERVER_2);
    }

    @Test
    public void hostIsAutomaticallyAddedOnStartup() {
        when(config.servers())
                .thenReturn(ImmutableDefaultConfig.builder()
                        .addThriftHosts(CASS_SERVER_1.proxy())
                        .build());
        when(config.autoRefreshNodes()).thenReturn(true);

        setCassandraServersTo(CASS_SERVER_1, CASS_SERVER_2);

        createClientPool();
        assertThat(poolServers).containsExactlyInAnyOrder(CASS_SERVER_1, CASS_SERVER_2);
    }

    @Test
    public void hostIsAutomaticallyAddedOnRefresh() {
        when(config.servers())
                .thenReturn(ImmutableDefaultConfig.builder()
                        .addThriftHosts(CASS_SERVER_1.proxy(), CASS_SERVER_2.proxy())
                        .build());
        when(config.autoRefreshNodes()).thenReturn(true);

        setCassandraServersTo(CASS_SERVER_1, CASS_SERVER_2);

        createClientPool();
        assertThat(poolServers).containsExactlyInAnyOrder(CASS_SERVER_1, CASS_SERVER_2);

        setCassandraServersTo(CASS_SERVER_1, CASS_SERVER_2, CASS_SERVER_3);
        deterministicExecutor.tick(config.poolRefreshIntervalSeconds(), TimeUnit.SECONDS);
        assertThat(poolServers).containsExactlyInAnyOrder(CASS_SERVER_1, CASS_SERVER_2, CASS_SERVER_3);
    }

    @Test
    public void hostsAreNotRemovedOrAddedWhenRefreshIsDisabled() {
        when(config.servers())
                .thenReturn(ImmutableDefaultConfig.builder()
                        .addThriftHosts(CASS_SERVER_1.proxy(), CASS_SERVER_2.proxy())
                        .build());
        when(config.autoRefreshNodes()).thenReturn(false);

        setCassandraServersTo(CASS_SERVER_1);
        createClientPool();
        assertThat(poolServers).containsExactlyInAnyOrder(CASS_SERVER_1, CASS_SERVER_2);

        setCassandraServersTo(CASS_SERVER_1, CASS_SERVER_2, CASS_SERVER_3);
        deterministicExecutor.tick(config.poolRefreshIntervalSeconds(), TimeUnit.SECONDS);
        assertThat(poolServers).containsExactlyInAnyOrder(CASS_SERVER_1, CASS_SERVER_2);
    }

    @Test
    public void hostsAreResetToConfigOnRefreshWhenRefreshIsDisabled() {
        when(config.servers())
                .thenReturn(ImmutableDefaultConfig.builder()
                        .addThriftHosts(CASS_SERVER_1.proxy(), CASS_SERVER_2.proxy())
                        .build());
        when(config.autoRefreshNodes()).thenReturn(false);

        setCassandraServersTo(CASS_SERVER_1);
        createClientPool();
        assertThat(poolServers).containsExactlyInAnyOrder(CASS_SERVER_1, CASS_SERVER_2);

        cassandra.addPool(CASS_SERVER_3);
        assertThat(poolServers).containsExactlyInAnyOrder(CASS_SERVER_1, CASS_SERVER_2, CASS_SERVER_3);

        deterministicExecutor.tick(config.poolRefreshIntervalSeconds(), TimeUnit.SECONDS);
        assertThat(poolServers).containsExactlyInAnyOrder(CASS_SERVER_1, CASS_SERVER_2);

        setCassandraServersTo(CASS_SERVER_2, CASS_SERVER_3);
        cassandra.removePool(CASS_SERVER_1);
        assertThat(poolServers).containsExactlyInAnyOrder(CASS_SERVER_2);

        deterministicExecutor.tick(config.poolRefreshIntervalSeconds(), TimeUnit.SECONDS);
        assertThat(poolServers).containsExactlyInAnyOrder(CASS_SERVER_1, CASS_SERVER_2);
    }

    @Test
    public void shutsDownHostsBeyondAbsenceTolerance() {
        CassandraClientPoolImpl cassandraClientPool =
                clientPoolWithServersInCurrentPool(ImmutableSet.of(CASS_SERVER_1));
        Map<CassandraServer, CassandraClientPoolingContainer> currentPoolSnapshot =
                cassandraClientPool.getCurrentPools();
        assertThat(currentPoolSnapshot.keySet()).containsExactly(CASS_SERVER_1);
        CassandraClientPoolingContainer container1 = currentPoolSnapshot.get(CASS_SERVER_1);
        verifyNoInteractions(container1);

        cassandraClientPool.setServersInPoolTo(ImmutableSet.of(CASS_SERVER_3));
        // The refresh will mark absence of host1 beyond limit of tolerance
        cassandraClientPool.setServersInPoolTo(ImmutableSet.of(CASS_SERVER_3));
        assertThat(cassandraClientPool.getCurrentPools().keySet()).containsExactly(CASS_SERVER_3);
        verify(container1).shutdownPooling();
    }

    private CassandraServer getInvocationAddress(InvocationOnMock invocation) {
        return invocation.getArgument(0);
    }

    private void setCassandraServersTo(CassandraServer... servers) {
        when(cassandra.refreshTokenRangesAndGetServers())
                .thenReturn(Arrays.stream(servers).collect(Collectors.toSet()));
    }

    private CassandraClientPoolImpl createClientPool() {
        return CassandraClientPoolImpl.createImplForTest(
                MetricsManagers.createForTests(),
                config,
                CassandraClientPoolImpl.StartupChecks.DO_NOT_RUN,
                InitializeableScheduledExecutorServiceSupplier.createForTests(deterministicExecutor),
                blacklist,
                cassandra);
    }

    private HostBuilder host(CassandraServer server) {
        return new HostBuilder(server);
    }

    static class HostBuilder {
        private CassandraServer server;
        private List<Exception> exceptions = new ArrayList<>();
        private boolean returnsValue = true;

        HostBuilder(CassandraServer server) {
            this.server = server;
        }

        HostBuilder throwsException(Exception ex) {
            exceptions.add(ex);
            return this;
        }

        void inPool(CassandraClientPool cassandraClientPool) {
            CassandraClientPoolingContainer container = mock(CassandraClientPoolingContainer.class);
            when(container.getCassandraServer()).thenReturn(server);
            try {
                OngoingStubbing<Object> stubbing = when(container.runWithPooledResource(
                        Mockito.<FunctionCheckedException<CassandraClient, Object, Exception>>any()));
                for (Exception ex : exceptions) {
                    stubbing = stubbing.thenThrow(ex);
                }
                if (returnsValue) {
                    stubbing.thenReturn("Response");
                }
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
            cassandraClientPool.getCurrentPools().put(server, container);
        }
    }

    private void verifyNumberOfAttemptsOnHost(
            CassandraServer host, CassandraClientPool cassandraClientPool, int numAttempts) {
        verify(cassandraClientPool.getCurrentPools().get(host), Mockito.times(numAttempts))
                .runWithPooledResource(
                        Mockito.<FunctionCheckedException<CassandraClient, Object, RuntimeException>>any());
    }

    private CassandraClientPoolImpl clientPoolWithInitialProxies(Set<InetSocketAddress> proxies) {
        return clientPoolWith(proxies, ImmutableSet.of(), Optional.empty());
    }

    private CassandraClientPoolImpl clientPoolWithServersInCurrentPool(Set<CassandraServer> servers) {
        return clientPoolWith(ImmutableSet.of(), servers, Optional.empty());
    }

    private CassandraClientPoolImpl throwingClientPoolWithServersInCurrentPool(
            Set<CassandraServer> servers, Exception exception) {
        return clientPoolWith(ImmutableSet.of(), servers, Optional.of(exception));
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType") // Unpacking it seems less readable
    private CassandraClientPoolImpl clientPoolWith(
            Set<InetSocketAddress> servers, Set<CassandraServer> serversInPool, Optional<Exception> failureMode) {
        when(config.servers())
                .thenReturn(ImmutableDefaultConfig.builder()
                        .addAllThriftHosts(servers)
                        .build());
        when(config.timeoutOnConnectionClose()).thenReturn(Duration.ofSeconds(10));
        when(config.timeoutOnConnectionBorrow()).thenReturn(HumanReadableDuration.minutes(10));
        when(config.consecutiveAbsencesBeforePoolRemoval()).thenReturn(1);

        CassandraClientPoolImpl cassandraClientPool = CassandraClientPoolImpl.createImplForTest(
                MetricsManagers.of(metricRegistry, taggedMetricRegistry),
                config,
                CassandraClientPoolImpl.StartupChecks.DO_NOT_RUN,
                blacklist);

        serversInPool.forEach(address -> cassandraClientPool
                .getCurrentPools()
                .put(address, getMockPoolingContainerForHost(address, failureMode)));

        return cassandraClientPool;
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType") // Unpacking it seems less readable
    private CassandraClientPoolingContainer getMockPoolingContainerForHost(
            CassandraServer address, Optional<Exception> maybeFailureMode) {
        CassandraClientPoolingContainer poolingContainer = mock(CassandraClientPoolingContainer.class);
        when(poolingContainer.getCassandraServer()).thenReturn(address);
        maybeFailureMode.ifPresent(e -> setFailureModeForHost(poolingContainer, e));
        return poolingContainer;
    }

    private void setFailureModeForHost(CassandraClientPoolingContainer poolingContainer, Exception failureMode) {
        try {
            when(poolingContainer.runWithPooledResource(
                            Mockito.<FunctionCheckedException<CassandraClient, Object, Exception>>any()))
                    .thenThrow(failureMode);
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    @SuppressWarnings("unchecked") // We know the types are correct within this test.
    private void setConditionalTimeoutFailureForHost(
            CassandraClientPoolingContainer container, Function<CassandraClientPoolingContainer, Boolean> condition) {
        try {
            when(container.runWithPooledResource(any(FunctionCheckedException.class)))
                    .then(invocation -> {
                        if (condition.apply(container)) {
                            throw new SocketTimeoutException();
                        }
                        return 42;
                    });
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    private void runNoopOnHost(CassandraServer cassandraServer, CassandraClientPool pool) {
        pool.runOnCassandraServer(cassandraServer, noOp());
    }

    private void runNoopWithRetryOnHost(CassandraServer cassandraServer, CassandraClientPool pool) {
        pool.runWithRetryOnServer(cassandraServer, noOp());
    }

    private FunctionCheckedException<CassandraClient, Void, RuntimeException> noOp() {
        return new FunctionCheckedException<CassandraClient, Void, RuntimeException>() {
            @Override
            public Void apply(CassandraClient input) throws RuntimeException {
                return null;
            }

            @Override
            public String toString() {
                return "no-op";
            }
        };
    }

    private void verifyAggregateFailureMetrics(
            double requestFailureProportion, double requestConnectionExceptionProportion) {
        assertThat(getAggregateMetricValueForMetricName("requestFailureProportion"))
                .isEqualTo(requestFailureProportion);
        assertThat(getAggregateMetricValueForMetricName("requestConnectionExceptionProportion"))
                .isEqualTo(requestConnectionExceptionProportion);
    }

    private void verifyBlacklistMetric(Integer expectedSize) {
        assertThat(getAggregateMetricValueForMetricName("numBlacklistedHosts")).isEqualTo(expectedSize);
    }

    private Object getAggregateMetricValueForMetricName(String metricName) {
        String fullyQualifiedMetricName = MetricRegistry.name(CassandraClientPool.class, metricName);
        return metricRegistry.getGauges().get(fullyQualifiedMetricName).getValue();
    }
}
