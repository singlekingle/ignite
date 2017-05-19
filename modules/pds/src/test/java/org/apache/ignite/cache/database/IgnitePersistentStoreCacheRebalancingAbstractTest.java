/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.cache.database;

import java.io.File;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.CacheWriteSynchronizationMode;
import org.apache.ignite.cache.QueryEntity;
import org.apache.ignite.cache.QueryIndex;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.configuration.MemoryPolicyConfiguration;
import org.apache.ignite.configuration.MemoryConfiguration;
import org.apache.ignite.configuration.PersistenceConfiguration;
import org.apache.ignite.events.CacheRebalancingEvent;
import org.apache.ignite.events.EventType;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.processors.cache.database.wal.FileWriteAheadLogManager;
import org.apache.ignite.internal.util.typedef.G;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgniteBiPredicate;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.TcpDiscoveryIpFinder;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.apache.ignite.testframework.GridTestUtils;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.apache.ignite.transactions.Transaction;

/**
 * Test for rebalancing and persistence integration.
 */
public abstract class IgnitePersistentStoreCacheRebalancingAbstractTest extends GridCommonAbstractTest {
    /** */
    private static final TcpDiscoveryIpFinder IP_FINDER = new TcpDiscoveryVmIpFinder(true);

    /** */
    protected boolean explicitTx;

    /** Cache name. */
    private final String cacheName = "cache";

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        CacheConfiguration ccfg1 = cacheConfiguration(cacheName);
        ccfg1.setBackups(1);
        ccfg1.setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);

        CacheConfiguration ccfg2 = cacheConfiguration("indexed");
        ccfg2.setBackups(1);
        ccfg2.setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);

        QueryEntity qryEntity = new QueryEntity(Integer.class.getName(), TestValue.class.getName());

        LinkedHashMap<String, String> fields = new LinkedHashMap<>();

        fields.put("v1", Integer.class.getName());
        fields.put("v2", Integer.class.getName());

        qryEntity.setFields(fields);

        QueryIndex qryIdx = new QueryIndex("v1", true);

        qryEntity.setIndexes(Collections.singleton(qryIdx));

        ccfg2.setQueryEntities(Collections.singleton(qryEntity));

        cfg.setCacheConfiguration(ccfg1, ccfg2);

        MemoryConfiguration dbCfg = new MemoryConfiguration();

        dbCfg.setConcurrencyLevel(Runtime.getRuntime().availableProcessors() * 4);
        dbCfg.setPageSize(1024);

        MemoryPolicyConfiguration memPlcCfg = new MemoryPolicyConfiguration();

        memPlcCfg.setName("dfltMemPlc");
        memPlcCfg.setMaxSize(100 * 1024 * 1024);
        memPlcCfg.setInitialSize(100 * 1024 * 1024);
        memPlcCfg.setSwapFilePath("work/swap");

        dbCfg.setMemoryPolicies(memPlcCfg);
        dbCfg.setDefaultMemoryPolicyName("dfltMemPlc");

        cfg.setMemoryConfiguration(dbCfg);

        cfg.setPersistenceConfiguration(new PersistenceConfiguration());

        TcpDiscoverySpi discoSpi = new TcpDiscoverySpi();

        discoSpi.setIpFinder(IP_FINDER);

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected long getTestTimeout() {
        return 20 * 60 * 1000;
    }

    /**
     * @param cacheName Cache name.
     * @return Cache configuration.
     */
    protected abstract CacheConfiguration cacheConfiguration(String cacheName);

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        G.stopAll(true);

        System.setProperty(FileWriteAheadLogManager.IGNITE_PDS_WAL_MODE, "LOG_ONLY");

        deleteRecursively(U.resolveWorkDirectory(U.defaultWorkDirectory(), "db", false));
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        G.stopAll(true);

        deleteRecursively(U.resolveWorkDirectory(U.defaultWorkDirectory(), "db", false));
    }

    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        System.clearProperty(FileWriteAheadLogManager.IGNITE_PDS_WAL_MODE);
    }

    /**
     * Test that outdated partitions on restarted nodes are correctly replaced with newer versions.
     *
     * @throws Exception If fails.
     */
    public void testRebalancingOnRestart() throws Exception {
        Ignite ignite0 = startGrid(0);

        startGrid(1);

        IgniteEx ignite2 = startGrid(2);

        awaitPartitionMapExchange();

        IgniteCache<Integer, Integer> cache1 = ignite0.cache(cacheName);

        for (int i = 0; i < 5000; i++)
            cache1.put(i, i);

        ignite2.close();

        awaitPartitionMapExchange();

        ignite0.resetLostPartitions(Collections.singletonList(cache1.getName()));

        assert cache1.lostPartitions().isEmpty();

        for (int i = 0; i < 5000; i++)
            cache1.put(i, i * 2);

        info(">>>>>>>>>>>>>>>>>");
        info(">>>>>>>>>>>>>>>>>");
        info(">>>>>>>>>>>>>>>>>");
        info(">>>>>>>>>>>>>>>>>");
        info(">>>>>>>>>>>>>>>>>");
        info(">>>>>>>>>>>>>>>>>");

        info(">>> Done puts...");

        ignite2 = startGrid(2);

        awaitPartitionMapExchange();

        IgniteCache<Integer, Integer> cache3 = ignite2.cache(cacheName);

        for (int i = 0; i < 100; i++)
            assertEquals(String.valueOf(i), (Integer)(i * 2), cache3.get(i));
    }

    /**
     * Test that outdated partitions on restarted nodes are correctly replaced with newer versions.
     *
     * @throws Exception If fails.
     */
    public void testRebalancingOnRestartAfterCheckpoint() throws Exception {
        IgniteEx ignite0 = startGrid(0);

        IgniteEx ignite1 = startGrid(1);

        IgniteEx ignite2 = startGrid(2);
        IgniteEx ignite3 = startGrid(3);

        ignite0.cache(cacheName).rebalance().get();
        ignite1.cache(cacheName).rebalance().get();
        ignite2.cache(cacheName).rebalance().get();
        ignite3.cache(cacheName).rebalance().get();

        awaitPartitionMapExchange();

        IgniteCache<Integer, Integer> cache1 = ignite0.cache(null);

        for (int i = 0; i < 1000; i++)
            cache1.put(i, i);

        ignite0.context().cache().context().database().waitForCheckpoint("test");
        ignite1.context().cache().context().database().waitForCheckpoint("test");

        info("++++++++++ After checkpoint");

        ignite2.close();
        ignite3.close();

        awaitPartitionMapExchange();

        ignite0.resetLostPartitions(Collections.singletonList(cache1.getName()));

        assert cache1.lostPartitions().isEmpty();

        for (int i = 0; i < 1000; i++)
            cache1.put(i, i * 2);

        info(">>>>>>>>>>>>>>>>>");
        info(">>>>>>>>>>>>>>>>>");
        info(">>>>>>>>>>>>>>>>>");
        info(">>>>>>>>>>>>>>>>>");
        info(">>>>>>>>>>>>>>>>>");
        info(">>>>>>>>>>>>>>>>>");

        info(">>> Done puts...");

        ignite2 = startGrid(2);
        ignite3 = startGrid(3);

        ignite2.cache(cacheName).rebalance().get();
        ignite3.cache(cacheName).rebalance().get();

        awaitPartitionMapExchange();

        IgniteCache<Integer, Integer> cache2 = ignite2.cache(cacheName);
        IgniteCache<Integer, Integer> cache3 = ignite3.cache(cacheName);

        for (int i = 0; i < 100; i++) {
            assertEquals(String.valueOf(i), (Integer)(i * 2), cache2.get(i));
            assertEquals(String.valueOf(i), (Integer)(i * 2), cache3.get(i));
        }
    }

    /**
     * Test that all data is correctly restored after non-graceful restart.
     *
     * @throws Exception If fails.
     */
    public void testDataCorrectnessAfterRestart() throws Exception {
        IgniteEx ignite1 = (IgniteEx)G.start(getConfiguration("test1"));
        IgniteEx ignite2 = (IgniteEx)G.start(getConfiguration("test2"));
        IgniteEx ignite3 = (IgniteEx)G.start(getConfiguration("test3"));
        IgniteEx ignite4 = (IgniteEx)G.start(getConfiguration("test4"));

        awaitPartitionMapExchange();

        IgniteCache<Integer, Integer> cache1 = ignite1.cache(cacheName);

        for (int i = 0; i < 100; i++)
            cache1.put(i, i);

        ignite1.close();
        ignite2.close();
        ignite3.close();
        ignite4.close();

        ignite1 = (IgniteEx)G.start(getConfiguration("test1"));
        ignite2 = (IgniteEx)G.start(getConfiguration("test2"));
        ignite3 = (IgniteEx)G.start(getConfiguration("test3"));
        ignite4 = (IgniteEx)G.start(getConfiguration("test4"));

        awaitPartitionMapExchange();

        cache1 = ignite1.cache(cacheName);
        IgniteCache<Integer, Integer> cache2 = ignite2.cache(cacheName);
        IgniteCache<Integer, Integer> cache3 = ignite3.cache(cacheName);
        IgniteCache<Integer, Integer> cache4 = ignite4.cache(cacheName);

        for (int i = 0; i < 100; i++) {
            assert cache1.get(i).equals(i);
            assert cache2.get(i).equals(i);
            assert cache3.get(i).equals(i);
            assert cache4.get(i).equals(i);
        }
    }

    /**
     * Test that partitions are marked as lost when all owners leave cluster, but recover after nodes rejoin.
     *
     * @throws Exception If fails.
     */
    public void testPartitionLossAndRecover() throws Exception {
        Ignite ignite1 = G.start(getConfiguration("test1"));
        Ignite ignite2 = G.start(getConfiguration("test2"));
        IgniteEx ignite3 = (IgniteEx)G.start(getConfiguration("test3"));
        IgniteEx ignite4 = (IgniteEx)G.start(getConfiguration("test4"));

        awaitPartitionMapExchange();

        IgniteCache<Integer, Integer> cache1 = ignite1.cache(cacheName);

        for (int i = 0; i < 100; i++)
            cache1.put(i, i);

        ignite1.active(false);

        ignite3.close();
        ignite4.close();

        ignite1.active(true);

        awaitPartitionMapExchange();

        assert !ignite1.cache(cacheName).lostPartitions().isEmpty();

        ignite3 = (IgniteEx)G.start(getConfiguration("test3"));
        ignite4 = (IgniteEx)G.start(getConfiguration("test4"));

        awaitPartitionMapExchange();

        ignite1.resetLostPartitions(Collections.singletonList(cache1.getName()));

        IgniteCache<Integer, Integer> cache2 = ignite2.cache(cacheName);
        IgniteCache<Integer, Integer> cache3 = ignite3.cache(cacheName);
        IgniteCache<Integer, Integer> cache4 = ignite4.cache(cacheName);

        for (int i = 0; i < 100; i++) {
            assert cache1.get(i).equals(i);
            assert cache2.get(i).equals(i);
            assert cache3.get(i).equals(i);
            assert cache4.get(i).equals(i);
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testTopologyChangesWithConstantLoad() throws Exception {
        fail("only for one run, must be removed soon");

        final int entriesCnt = 10_000;
        int maxNodesCount = 4;
        int topChanges = 20;
        final String cacheName = "indexed";

        final AtomicBoolean stop = new AtomicBoolean();

        final ConcurrentMap<Integer, TestValue> map = new ConcurrentHashMap<>();

        Ignite ignite = startGrid(0);

        IgniteCache<Integer, TestValue> cache = ignite.cache(cacheName);

        for (int i = 0; i < entriesCnt; i++) {
            cache.put(i, new TestValue(i, i));
            map.put(i, new TestValue(i, i));
        }

        final AtomicInteger nodesCnt = new AtomicInteger();

        IgniteInternalFuture fut = GridTestUtils.runMultiThreadedAsync(new Callable<Void>() {
            @Override public Void call() throws Exception {
                while (true) {
                    if (stop.get())
                        return null;

                    int k = ThreadLocalRandom.current().nextInt(entriesCnt);
                    int v1 = ThreadLocalRandom.current().nextInt();
                    int v2 = ThreadLocalRandom.current().nextInt();

                    int n = nodesCnt.get();

                    if (n <= 0)
                        continue;

                    Ignite ignite;

                    try {
                        ignite = grid(ThreadLocalRandom.current().nextInt(n));
                    }
                    catch (Exception ignored) {
                        continue;
                    }

                    if (ignite == null)
                        continue;

                    Transaction tx = null;
                    boolean success = true;

                    if (explicitTx)
                        tx = ignite.transactions().txStart();

                    try {
                        ignite.cache(cacheName).put(k, new TestValue(v1, v2));
                    }
                    catch (Exception e) {
                        success = false;
                    }
                    finally {
                        if (tx != null) {
                            try {
                                tx.commit();
                            }
                            catch (Exception e) {
                                success = false;
                            }
                        }
                    }

                    if (success)
                        map.put(k, new TestValue(v1, v2));
                }
            }
        }, 1, "load-runner");

        for (int i = 0; i < topChanges; i++) {
            U.sleep(3_000);

            boolean add;
            if (nodesCnt.get() <= maxNodesCount / 2)
                add = true;
            else if (nodesCnt.get() > maxNodesCount)
                add = false;
            else
                add = ThreadLocalRandom.current().nextBoolean();

            if (add)
                startGrid(nodesCnt.incrementAndGet());
            else
                stopGrid(nodesCnt.getAndDecrement());

            awaitPartitionMapExchange();

            cache.rebalance().get();
        }

        stop.set(true);

        fut.get();

        awaitPartitionMapExchange();

        for (Map.Entry<Integer, TestValue> entry : map.entrySet())
            assertEquals(Integer.toString(entry.getKey()), entry.getValue(), cache.get(entry.getKey()));
    }

    /**
     *
     */
    private static class TestValue implements Serializable {
        /** V 1. */
        private final int v1;
        /** V 2. */
        private final int v2;

        /**
         * @param v1 V 1.
         * @param v2 V 2.
         */
        private TestValue(int v1, int v2) {
            this.v1 = v1;
            this.v2 = v2;
        }

        /** {@inheritDoc} */
        @Override public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            TestValue val = (TestValue)o;

            return v1 == val.v1 && v2 == val.v2;

        }

        /** {@inheritDoc} */
        @Override public int hashCode() {
            int res = v1;

            res = 31 * res + v2;

            return res;
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return "TestValue{" +
                "v1=" + v1 +
                ", v2=" + v2 +
                '}';
        }
    }
}