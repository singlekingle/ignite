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

package org.apache.ignite.internal.processors.cache.ttl;

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.cache.eviction.lru.*;
import org.apache.ignite.cache.query.*;
import org.apache.ignite.cache.store.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.spi.discovery.tcp.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.*;
import org.apache.ignite.testframework.junits.common.*;

import javax.cache.*;
import javax.cache.configuration.*;
import javax.cache.expiry.*;
import javax.cache.integration.*;
import java.util.*;

import static java.util.concurrent.TimeUnit.*;
import static org.apache.ignite.cache.CacheMode.*;
import static org.apache.ignite.cache.CachePeekMode.*;
import static org.apache.ignite.cache.CacheRebalanceMode.*;
import static org.apache.ignite.cache.CacheWriteSynchronizationMode.*;

/**
 * TTL test.
 */
public abstract class CacheTtlAbstractSelfTest extends GridCommonAbstractTest {
    /** */
    private static final TcpDiscoveryIpFinder IP_FINDER = new TcpDiscoveryVmIpFinder(true);

    /** */
    private static final int MAX_CACHE_SIZE = 5;

    /** */
    private static final int SIZE = 11;

    /** */
    private static final long DEFAULT_TIME_TO_LIVE = 2000;

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        CacheConfiguration ccfg = new CacheConfiguration();

        ccfg.setCacheMode(cacheMode());
        ccfg.setAtomicityMode(atomicityMode());
        ccfg.setMemoryMode(memoryMode());
        ccfg.setOffHeapMaxMemory(0);
        ccfg.setEvictionPolicy(new LruEvictionPolicy(MAX_CACHE_SIZE));
        ccfg.setIndexedTypes(Integer.class, Integer.class);
        ccfg.setBackups(2);
        ccfg.setWriteSynchronizationMode(FULL_SYNC);
        ccfg.setRebalanceMode(SYNC);

        ccfg.setCacheStoreFactory(singletonFactory(new CacheStoreAdapter() {
            @Override public void loadCache(IgniteBiInClosure clo, Object... args) {
                for (int i = 0; i < SIZE; i++)
                    clo.apply(i, i);
            }

            @Override public Object load(Object key) throws CacheLoaderException {
                return key;
            }

            @Override public void write(Cache.Entry entry) throws CacheWriterException {
                // No-op.
            }

            @Override public void delete(Object key) throws CacheWriterException {
                // No-op.
            }
        }));

        ccfg.setExpiryPolicyFactory(
                FactoryBuilder.factoryOf(new TouchedExpiryPolicy(new Duration(MILLISECONDS, DEFAULT_TIME_TO_LIVE))));

        cfg.setCacheConfiguration(ccfg);

        ((TcpDiscoverySpi)cfg.getDiscoverySpi()).setIpFinder(IP_FINDER);

        return cfg;
    }

    /**
     * @return Atomicity mode.
     */
    protected abstract CacheAtomicityMode atomicityMode();

    /**
     * @return Memory mode.
     */
    protected abstract CacheMemoryMode memoryMode();

    /**
     * @return Cache mode.
     */
    protected abstract CacheMode cacheMode();

    /**
     * @return GridCount
     */
    protected abstract int gridCount();

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        startGrids(gridCount());
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        stopAllGrids();
    }

    /**
     * @throws Exception If failed.
     */
    public void testDefaultTimeToLiveLoadCache() throws Exception {
        IgniteCache<Integer, Integer> cache = jcache(0);

        cache.loadCache(null);

        checkSizeBeforeLive(SIZE);

        Thread.sleep(DEFAULT_TIME_TO_LIVE + 500);

        checkSizeAfterLive();
    }

    /**
     * @throws Exception If failed.
     */
    public void testDefaultTimeToLiveLoadAll() throws Exception {
        defaultTimeToLiveLoadAll(false);

        defaultTimeToLiveLoadAll(true);
    }

    /**
     * @param replaceExisting Replace existing value flag.
     * @throws Exception If failed.
     */
    private void defaultTimeToLiveLoadAll(boolean replaceExisting) throws Exception {
        IgniteCache<Integer, Integer> cache = jcache(0);

        CompletionListenerFuture fut = new CompletionListenerFuture();

        Set<Integer> keys = new HashSet<>();

        for (int i = 0; i < SIZE; ++i)
            keys.add(i);

        cache.loadAll(keys, replaceExisting, fut);

        fut.get();

        checkSizeBeforeLive(SIZE);

        Thread.sleep(DEFAULT_TIME_TO_LIVE + 500);

        checkSizeAfterLive();
    }

    /**
     * @throws Exception If failed.
     */
    public void testDefaultTimeToLiveStreamerAdd() throws Exception {
        try (IgniteDataStreamer<Integer, Integer> streamer = ignite(0).dataStreamer(null)) {
            for (int i = 0; i < SIZE; i++)
                streamer.addData(i, i);
        }

        checkSizeBeforeLive(SIZE);

        Thread.sleep(DEFAULT_TIME_TO_LIVE + 500);

        checkSizeAfterLive();

        try (IgniteDataStreamer<Integer, Integer> streamer = ignite(0).dataStreamer(null)) {
            streamer.allowOverwrite(true);

            for (int i = 0; i < SIZE; i++)
                streamer.addData(i, i);
        }

        checkSizeBeforeLive(SIZE);

        Thread.sleep(DEFAULT_TIME_TO_LIVE + 500);

        checkSizeAfterLive();
    }

    /**
     * @throws Exception If failed.
     */
    public void testDefaultTimeToLivePut() throws Exception {
        IgniteCache<Integer, Integer> cache = jcache(0);

        Integer key = 0;

        cache.put(key, 1);

        checkSizeBeforeLive(1);

        Thread.sleep(DEFAULT_TIME_TO_LIVE + 500);

        checkSizeAfterLive();
    }

    /**
     * @throws Exception If failed.
     */
    public void testDefaultTimeToLivePutAll() throws Exception {
        IgniteCache<Integer, Integer> cache = jcache(0);

        Map<Integer, Integer> entries = new HashMap<>();

        for (int i = 0; i < SIZE; ++i)
            entries.put(i, i);

        cache.putAll(entries);

        checkSizeBeforeLive(SIZE);

        Thread.sleep(DEFAULT_TIME_TO_LIVE + 500);

        checkSizeAfterLive();
    }

    /**
     * @throws Exception If failed.
     */
    public void testDefaultTimeToLivePreload() throws Exception {
        if (cacheMode() == LOCAL)
            return;

        IgniteCache<Integer, Integer> cache = jcache(0);

        Map<Integer, Integer> entries = new HashMap<>();

        for (int i = 0; i < SIZE; ++i)
            entries.put(i, i);

        cache.putAll(entries);

        startGrid(gridCount());

        checkSizeBeforeLive(SIZE, gridCount() + 1);

        Thread.sleep(DEFAULT_TIME_TO_LIVE + 500);

        checkSizeAfterLive(gridCount() + 1);
    }

    /**
     * @throws Exception If failed.
     */
    public void testTimeToLiveTtl() throws Exception {
        long time = DEFAULT_TIME_TO_LIVE + 2000;

        IgniteCache<Integer, Integer> cache = this.<Integer, Integer>jcache(0).withExpiryPolicy(
            new TouchedExpiryPolicy(new Duration(MILLISECONDS, time)));

        for (int i = 0; i < SIZE; i++)
            cache.put(i, i);

        checkSizeBeforeLive(SIZE);

        Thread.sleep(DEFAULT_TIME_TO_LIVE + 500);

        checkSizeBeforeLive(SIZE);

        Thread.sleep(time - DEFAULT_TIME_TO_LIVE + 500);

        checkSizeAfterLive();
    }

    /**
     * @param size Expected size.
     * @throws Exception If failed.
     */
    private void checkSizeBeforeLive(int size) throws Exception {
        checkSizeBeforeLive(size, gridCount());
    }

    /**
     * @param size Expected size.
     * @param gridCnt Number of nodes.
     * @throws Exception If failed.
     */
    private void checkSizeBeforeLive(int size, int gridCnt) throws Exception {
        for (int i = 0; i < gridCnt; ++i) {
            IgniteCache<Integer, Integer> cache = jcache(i);

            log.info("Size [node=" + i +
                ", heap=" + cache.localSize(PRIMARY, BACKUP, NEAR, ONHEAP) +
                ", offheap=" + cache.localSize(PRIMARY, BACKUP, NEAR, OFFHEAP) +
                ", swap=" + cache.localSize(PRIMARY, BACKUP, NEAR, SWAP) + ']');

            if (memoryMode() == CacheMemoryMode.OFFHEAP_TIERED) {
                assertEquals("Unexpected size, node: " + i, 0, cache.localSize(PRIMARY, BACKUP, NEAR, ONHEAP));
                assertEquals("Unexpected size, node: " + i, size, cache.localSize(PRIMARY, BACKUP, NEAR, OFFHEAP));
            }
            else {
                assertEquals("Unexpected size, node: " + i, size > MAX_CACHE_SIZE ? MAX_CACHE_SIZE : size,
                    cache.localSize(PRIMARY, BACKUP, NEAR, ONHEAP));

                assertEquals("Unexpected size, node: " + i,
                    size > MAX_CACHE_SIZE ? size - MAX_CACHE_SIZE : 0, cache.localSize(PRIMARY, BACKUP, NEAR, OFFHEAP));
            }

            for (int key = 0; key < size; key++)
                assertNotNull(cache.localPeek(key));

            assertFalse(cache.query(new SqlQuery<>(Integer.class, "_val >= 0")).getAll().isEmpty());
        }
    }

    /**
     * @throws Exception If failed.
     */
    private void checkSizeAfterLive() throws Exception {
        checkSizeAfterLive(gridCount());
    }

    /**
     * @param gridCnt Number of nodes.
     * @throws Exception If failed.
     */
    private void checkSizeAfterLive(int gridCnt) throws Exception {
        for (int i = 0; i < gridCnt; ++i) {
            IgniteCache<Integer, Integer> cache = jcache(i);

            log.info("Size [node=" + i +
                ", heap=" + cache.localSize(ONHEAP) +
                ", offheap=" + cache.localSize(OFFHEAP) +
                ", swap=" + cache.localSize(SWAP) + ']');

            assertEquals(0, cache.localSize());
            assertEquals(0, cache.localSize(OFFHEAP));
            assertEquals(0, cache.localSize(SWAP));
            assertEquals(0, cache.query(new SqlQuery<>(Integer.class, "_val >= 0")).getAll().size());

            for (int key = 0; key < SIZE; key++)
                assertNull(cache.localPeek(key));
        }
    }
}
