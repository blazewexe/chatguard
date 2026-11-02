package dev.chatguard.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A simple LRU cache backed by {@link LinkedHashMap} with a fixed maximum size.
 * When the cache exceeds the maximum size, the least-recently-used entry is evicted.
 *
 * @param <K> key type
 * @param <V> value type
 */
public class BoundedCache<K, V> extends LinkedHashMap<K, V> {

    private final int maxSize;

    /**
     * Create a new bounded cache with the given maximum size.
     *
     * @param maxSize maximum number of entries before LRU eviction
     */
    public BoundedCache(int maxSize) {
        super(Math.max(16, maxSize / 4), 0.75f, true);
        this.maxSize = maxSize;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > maxSize;
    }

    /**
     * @return the configured maximum size
     */
    public int getMaxSize() {
        return maxSize;
    }
}

