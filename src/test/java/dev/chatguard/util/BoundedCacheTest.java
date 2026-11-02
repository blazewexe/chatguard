package dev.chatguard.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BoundedCacheTest {

    @Test
    @DisplayName("BoundedCache evicts eldest entry when capacity is exceeded")
    void testLruEviction() {
        BoundedCache<String, Integer> cache = new BoundedCache<>(3);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        assertEquals(3, cache.size());

        // Access "a" to make it more recently used
        cache.get("a");

        // Insert "d", which should evict "b" (since "a" was accessed, "b" is now eldest)
        cache.put("d", 4);
        assertEquals(3, cache.size());
        assertTrue(cache.containsKey("a"));
        assertFalse(cache.containsKey("b"));
        assertTrue(cache.containsKey("c"));
        assertTrue(cache.containsKey("d"));
    }
}
