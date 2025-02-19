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

import com.codahale.metrics.Gauge;
import com.google.common.base.Function;
import com.google.common.base.MoreObjects;
import com.palantir.atlasdb.cassandra.CassandraKeyValueServiceConfig;
import com.palantir.atlasdb.encoding.PtBytes;
import com.palantir.atlasdb.keyvalue.cassandra.CassandraClientFactory.CassandraClientConfig;
import com.palantir.atlasdb.keyvalue.cassandra.pool.CassandraClientPoolHostLevelMetric;
import com.palantir.atlasdb.keyvalue.cassandra.pool.CassandraClientPoolMetrics;
import com.palantir.atlasdb.keyvalue.cassandra.pool.CassandraServer;
import com.palantir.atlasdb.util.MetricsManager;
import com.palantir.common.base.FunctionCheckedException;
import com.palantir.common.pooling.PoolingContainer;
import com.palantir.logsafe.SafeArg;
import com.palantir.logsafe.UnsafeArg;
import com.palantir.logsafe.logger.SafeLogger;
import com.palantir.logsafe.logger.SafeLoggerFactory;
import com.palantir.nylon.threads.ThreadNames;
import com.palantir.util.TimedRunner;
import com.palantir.util.TimedRunner.TaskContext;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.NoSuchElementException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.pool2.impl.DefaultEvictionPolicy;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TMemoryInputTransport;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;

public class CassandraClientPoolingContainer implements PoolingContainer<CassandraClient> {
    private static final SafeLogger log = SafeLoggerFactory.get(CassandraClientPoolingContainer.class);

    private final CassandraServer cassandraServer;

    /**
     * We use this proxy to actually talk to the Cassandra host.
     * */
    private final InetSocketAddress proxy;

    private final CassandraKeyValueServiceConfig config;
    private final MetricsManager metricsManager;
    private final AtomicLong count = new AtomicLong();
    private final AtomicInteger openRequests = new AtomicInteger();
    private final GenericObjectPool<CassandraClient> clientPool;
    private final int poolNumber;
    private final CassandraClientPoolMetrics poolMetrics;
    private final TimedRunner timedRunner;

    public CassandraClientPoolingContainer(
            MetricsManager metricsManager,
            CassandraServer cassandraServer,
            CassandraKeyValueServiceConfig config,
            int poolNumber,
            CassandraClientPoolMetrics poolMetrics) {
        this.metricsManager = metricsManager;
        this.cassandraServer = cassandraServer;
        this.proxy = cassandraServer.proxy();
        this.config = config;
        this.poolNumber = poolNumber;
        this.poolMetrics = poolMetrics;
        this.clientPool = createClientPool();
        this.timedRunner = TimedRunner.create(config.timeoutOnConnectionBorrow().toJavaDuration());
    }

    public CassandraServer getCassandraServer() {
        return cassandraServer;
    }

    /**
     * Number of open requests to {@link #runWithPooledResource(FunctionCheckedException)}.
     * This is different from the number of active objects in the pool, as creating a new
     * pooled object can block on {@link CassandraClientFactory#create()}} before being added
     * to the client pool.
     */
    public int getOpenRequests() {
        return openRequests.get();
    }

    // returns negative if not available; only expected use is debugging
    public int getActiveCheckouts() {
        return clientPool.getNumActive();
    }

    // returns negative if unbounded; only expected use is debugging
    public int getPoolSize() {
        return clientPool.getMaxTotal();
    }

    @Override
    public <V, K extends Exception> V runWithPooledResource(FunctionCheckedException<CassandraClient, V, K> fn)
            throws K {
        final String origName = Thread.currentThread().getName();
        ThreadNames.setThreadName(
                Thread.currentThread(),
                origName
                        + " calling cassandra host " + proxy
                        + " started at " + DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                        + " - " + count.getAndIncrement());
        try {
            openRequests.getAndIncrement();
            return runWithGoodResource(fn);
        } catch (Throwable t) {
            log.warn("Error occurred talking to host '{}'", SafeArg.of("host", cassandraServer), t);
            if (t instanceof NoSuchElementException && t.getMessage().contains("Pool exhausted")) {
                log.warn(
                        "Extra information about exhausted pool",
                        SafeArg.of("numActive", clientPool.getNumActive()),
                        SafeArg.of("maxTotal", clientPool.getMaxTotal()),
                        SafeArg.of("meanActiveTimeMillis", clientPool.getMeanActiveTimeMillis()),
                        SafeArg.of("meanIdleTimeMillis", clientPool.getMeanIdleTimeMillis()));
                poolMetrics.recordPoolExhaustion();
                if (log.isDebugEnabled()) {
                    logThreadStates();
                }
            }
            throw t;
        } finally {
            openRequests.getAndDecrement();
            ThreadNames.setThreadName(Thread.currentThread(), origName);
        }
    }

    @Override
    public <V> V runWithPooledResource(Function<CassandraClient, V> fn) {
        throw new UnsupportedOperationException("you should use FunctionCheckedException<?, ?, Exception> "
                + "to ensure the TTransportException type is propagated correctly.");
    }

    @SuppressWarnings("unchecked")
    private <V, K extends Exception> V runWithGoodResource(FunctionCheckedException<CassandraClient, V, K> fn)
            throws K {
        boolean shouldReuse = true;
        CassandraClient resource = null;
        try {
            resource = clientPool.borrowObject();
            CassandraClient finalResource = resource;
            TaskContext<V> taskContext = TaskContext.create(() -> fn.apply(finalResource), () -> {});
            return timedRunner.run(taskContext);
        } catch (Exception e) {
            if (isInvalidClientConnection(resource)) {
                log.warn(
                        "Not reusing resource due to {} of host {}",
                        UnsafeArg.of("exception", e.toString()),
                        SafeArg.of("cassandraHostName", cassandraServer.cassandraHostName()),
                        SafeArg.of("proxy", CassandraLogHelper.host(proxy)),
                        e);
                shouldReuse = false;
            }
            if (e instanceof TTransportException
                    && ((TTransportException) e).getType() == TTransportException.END_OF_FILE) {
                // If we have an end of file this is most likely due to this cassandra node being bounced.
                log.warn(
                        "Clearing client pool due to exception",
                        SafeArg.of("cassandraHostName", cassandraServer.cassandraHostName()),
                        SafeArg.of("proxy", CassandraLogHelper.host(proxy)),
                        e);
                clientPool.clear();
            }
            throw (K) e;
        } finally {
            if (resource != null) {
                if (shouldReuse) {
                    if (log.isDebugEnabled()) {
                        log.debug(
                                "Returning resource to pool of host {}",
                                SafeArg.of("cassandraHostname", cassandraServer.cassandraHostName()),
                                SafeArg.of("proxy", CassandraLogHelper.host(proxy)));
                    }
                    eagerlyCleanupReadBuffersFromIdleConnection(resource, cassandraServer);
                    clientPool.returnObject(resource);
                } else {
                    invalidateQuietly(resource);
                }
            } else {
                log.warn("Failed to acquire Cassandra resource from object pool");
            }
        }
    }

    private static void eagerlyCleanupReadBuffersFromIdleConnection(
            CassandraClient idleClient, CassandraServer server) {
        // eagerly cleanup idle-connection read buffer to keep a smaller memory footprint
        try {
            TTransport transport = idleClient.getInputProtocol().getTransport();
            if (transport instanceof TFramedTransport) {
                Field readBuffer = ((TFramedTransport) transport).getClass().getDeclaredField("readBuffer_");
                readBuffer.setAccessible(true);
                TMemoryInputTransport memoryInputTransport = (TMemoryInputTransport) readBuffer.get(transport);
                byte[] underlyingBuffer = memoryInputTransport.getBuffer();
                if (underlyingBuffer != null && memoryInputTransport.getBytesRemainingInBuffer() == 0) {
                    if (log.isDebugEnabled()) {
                        log.debug(
                                "During {} check-in, cleaned up a read buffer of {} bytes of host {}",
                                UnsafeArg.of("pool", idleClient),
                                SafeArg.of("bufferLength", underlyingBuffer.length),
                                SafeArg.of("cassandraHostname", server.cassandraHostName()),
                                SafeArg.of("proxy", CassandraLogHelper.host(server.proxy())));
                    }
                    memoryInputTransport.reset(PtBytes.EMPTY_BYTE_ARRAY);
                }
            }
        } catch (Exception e) {
            log.debug("Couldn't clean up read buffers on pool check-in.", e);
        }
    }

    private static boolean isInvalidClientConnection(CassandraClient client) {
        return client != null && client.isValid();
    }

    private void invalidateQuietly(CassandraClient resource) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("Discarding resource of host {}", SafeArg.of("host", CassandraLogHelper.host(proxy)));
            }
            clientPool.invalidateObject(resource);
        } catch (Exception e) {
            log.warn("Attempted to invalidate a non-reusable Cassandra resource, but failed to due an exception", e);
            // Ignore
        }
    }

    @Override
    public void shutdownPooling() {
        clientPool.close();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(getClass())
                .add("cassandraHost", cassandraServer.cassandraHostName())
                .add("host", proxy)
                .add("keyspace", config.getKeyspaceOrThrow())
                .add("usingSsl", config.usingSsl())
                .add(
                        "sslConfiguration",
                        config.sslConfiguration().isPresent()
                                ? config.sslConfiguration().get()
                                : "unspecified")
                .add("socketTimeoutMillis", config.socketTimeoutMillis())
                .add("socketQueryTimeoutMillis", config.socketQueryTimeoutMillis())
                .toString();
    }

    /**
     * Pool size:
     *    Always keep {@link CassandraKeyValueServiceConfig#poolSize()} connections around, per host. Allow bursting
     *    up to {@link CassandraKeyValueServiceConfig#maxConnectionBurstSize()} connections per host under load.
     *
     * Borrowing from pool:
     *    On borrow, check if the connection is actually open. If it is not,
     *       immediately discard this connection from the pool, and try to take another.
     *    Borrow attempts against a fully in-use pool immediately throw a NoSuchElementException.
     *       {@code CassandraClientPool} when it sees this will:
     *          Follow an exponential backoff as a method of back pressure.
     *          Try 3 times against this host, and then give up and try against different hosts 3 additional times.
     *
     *
     * In an asynchronous thread (using default values):
     *    Every 20-30 seconds, examine approximately a tenth of the connections in pool.
     *    Discard any connections in this tenth of the pool whose TCP connections are closed.
     *    Discard any connections in this tenth of the pool that have been idle for more than 10 minutes,
     *       while still keeping a minimum number of idle connections around for fast borrows.
     */
    private GenericObjectPool<CassandraClient> createClientPool() {
        CassandraClientConfig clientConfig = CassandraClientConfig.of(config);
        CassandraClientFactory cassandraClientFactory = new CassandraClientFactory(metricsManager, proxy, clientConfig);
        GenericObjectPoolConfig<CassandraClient> poolConfig = new GenericObjectPoolConfig<>();

        poolConfig.setMinIdle(config.poolSize());
        poolConfig.setMaxIdle(config.maxConnectionBurstSize());
        poolConfig.setMaxTotal(config.maxConnectionBurstSize());

        // immediately throw when we try and borrow from a full pool; dealt with at higher level
        poolConfig.setBlockWhenExhausted(false);

        // this test is free/just checks a boolean and does not block; borrow is still fast
        poolConfig.setTestOnBorrow(true);

        poolConfig.setSoftMinEvictableIdleTimeMillis(
                TimeUnit.MILLISECONDS.convert(Duration.ofSeconds(config.idleConnectionTimeoutSeconds())));
        poolConfig.setMinEvictableIdleTimeMillis(Long.MAX_VALUE);

        // the randomness here is to prevent all of the pools for all of the hosts
        // evicting all at at once, which isn't great for C*.
        int timeBetweenEvictionsSeconds = config.timeBetweenConnectionEvictionRunsSeconds();
        int delta = ThreadLocalRandom.current().nextInt(Math.min(timeBetweenEvictionsSeconds / 2, 10));
        poolConfig.setTimeBetweenEvictionRunsMillis(
                TimeUnit.MILLISECONDS.convert(Duration.ofSeconds(timeBetweenEvictionsSeconds + delta)));
        poolConfig.setNumTestsPerEvictionRun(-(int) (1.0 / config.proportionConnectionsToCheckPerEvictionRun()));
        poolConfig.setTestWhileIdle(true);

        poolConfig.setJmxNamePrefix(proxy.getHostString());
        poolConfig.setEvictionPolicy(new DefaultEvictionPolicy<>());
        GenericObjectPool<CassandraClient> pool = new GenericObjectPool<>(cassandraClientFactory, poolConfig);
        pool.setSwallowedExceptionListener(exception -> log.info("Swallowed exception within object pool", exception));
        registerMetrics(pool);
        log.info(
                "Creating a Cassandra client pool for {} with the configuration {}",
                SafeArg.of("cassandraHost", cassandraServer.cassandraHostName()),
                SafeArg.of("proxy", proxy),
                SafeArg.of("poolConfig", poolConfig));
        return pool;
    }

    private void logThreadStates() {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        for (ThreadInfo info : threadBean.getThreadInfo(threadBean.getAllThreadIds())) {
            // we're fairly good about annotating our C* pool thread names with the current action
            if (log.isTraceEnabled()) {
                log.trace(
                        "active thread",
                        UnsafeArg.of("threadName", info.getThreadName()),
                        SafeArg.of("state", info.getThreadState()),
                        SafeArg.of("blockedTime", info.getBlockedTime()),
                        SafeArg.of("waitedTime", info.getWaitedTime()),
                        UnsafeArg.of("stackTrace", info.getStackTrace()));
            } else if (log.isDebugEnabled()) { // omit the rather lengthy stack traces
                log.debug(
                        "active thread",
                        UnsafeArg.of("threadName", info.getThreadName()),
                        SafeArg.of("state", info.getThreadState()),
                        SafeArg.of("blockedTime", info.getBlockedTime()),
                        SafeArg.of("waitedTime", info.getWaitedTime()));
            }
        }
    }

    private void registerMetrics(GenericObjectPool<CassandraClient> pool) {
        registerPoolMetric(CassandraClientPoolHostLevelMetric.MEAN_ACTIVE_TIME_MILLIS, pool::getMeanActiveTimeMillis);
        registerPoolMetric(CassandraClientPoolHostLevelMetric.NUM_IDLE, () -> (long) pool.getNumIdle());
        registerPoolMetric(CassandraClientPoolHostLevelMetric.NUM_ACTIVE, () -> (long) pool.getNumActive());
        registerPoolMetric(CassandraClientPoolHostLevelMetric.CREATED, pool::getCreatedCount);
        registerPoolMetric(CassandraClientPoolHostLevelMetric.DESTROYED_BY_EVICTOR, pool::getDestroyedByEvictorCount);
        registerPoolMetric(CassandraClientPoolHostLevelMetric.DESTROYED, pool::getDestroyedCount);
    }

    private void registerPoolMetric(CassandraClientPoolHostLevelMetric metric, Gauge<Long> gauge) {
        poolMetrics.registerPoolMetric(metric, gauge, poolNumber);
    }
}
