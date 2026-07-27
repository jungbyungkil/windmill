package com.windmill.util;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 외부 API(관광공사/기상청) 원본 응답을 짧게 캐싱하기 위한 최소 TTL 캐시.
 * 원본 데이터를 가공 없이 그대로 보관하고, 만료 시에만 재조회한다.
 */
public class SimpleTtlCache<K, V> {

    private record Entry<V>(V value, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private final ConcurrentHashMap<K, Entry<V>> store = new ConcurrentHashMap<>();
    private final Duration ttl;

    public SimpleTtlCache(Duration ttl) {
        this.ttl = ttl;
    }

    @SuppressWarnings("unchecked")
    public V get(K key) {
        Entry<V> entry = store.get(key);
        if (entry == null || entry.isExpired()) {
            return null;
        }
        return entry.value();
    }

    public void put(K key, V value) {
        store.put(key, new Entry<>(value, Instant.now().plus(ttl)));
    }

    public V getOrCompute(K key, Supplier<V> supplier) {
        V cached = get(key);
        if (cached != null) {
            return cached;
        }
        V computed = supplier.get();
        if (computed != null) {
            put(key, computed);
        }
        return computed;
    }

    public void evictAll() {
        store.clear();
    }
}
