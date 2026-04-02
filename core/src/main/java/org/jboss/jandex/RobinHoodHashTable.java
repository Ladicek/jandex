package org.jboss.jandex;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * A concurrent hash map using striped locking with Robin Hood hashing.
 * Each segment has its own lock and hash table, so operations on different
 * segments proceed in parallel. Robin Hood hashing keeps probe distances
 * short and enables early termination on lookups.
 * <p>
 * Keys must not be {@code null}. Values may be {@code null}.
 * <p>
 * <strong>This map does not use the classic {@link Object#equals(Object) equals()}
 * and {@link Object#hashCode() hashCode()} methods from {@code Object}!</strong> Instead,
 * it uses the {@link KeyOps} interface, whose instance must be passed
 * to the {@code RobinHoodHashTable} constructor.
 */
final class RobinHoodHashTable<K, V> {
    interface KeyOps<K> {
        boolean equals(K key1, K key2);

        int hashCode(K key);
    }

    static final class Entry<K, V> {
        final int hash;
        final K key;
        final V value;

        Entry(int hash, K key, V value) {
            this.hash = hash;
            this.key = key;
            this.value = value;
        }
    }

    private static final int SEGMENT_BITS = 4;
    private static final int SEGMENT_COUNT = 1 << SEGMENT_BITS;
    private static final int SEGMENT_MASK = SEGMENT_COUNT - 1;
    private static final int INITIAL_SEGMENT_CAPACITY = 16; // must be power of 2
    private static final float LOAD_FACTOR = 0.75f;

    private final KeyOps<K> keyOps;
    private final ReentrantLock[] locks;
    private Entry<K, V>[][] tables;
    private final int[] sizes;

    @SuppressWarnings("unchecked")
    RobinHoodHashTable(KeyOps<K> keyOps) {
        this.keyOps = Objects.requireNonNull(keyOps);
        this.locks = new ReentrantLock[SEGMENT_COUNT];
        this.tables = new Entry[SEGMENT_COUNT][];
        this.sizes = new int[SEGMENT_COUNT];
        for (int i = 0; i < SEGMENT_COUNT; i++) {
            this.locks[i] = new ReentrantLock();
            this.tables[i] = new Entry[INITIAL_SEGMENT_CAPACITY];
        }
    }

    private static int spread(int h) {
        return h ^ (h >>> 16);
    }

    private static int segment(int hash) {
        return (hash >>> (Integer.SIZE - SEGMENT_BITS)) & SEGMENT_MASK;
    }

    private static int probeDistance(int hash, int idx, int mask) {
        return (idx - (hash & mask) + mask + 1) & mask;
    }

    @SuppressWarnings("unchecked")
    public V get(K key) {
        int h = spread(keyOps.hashCode(key));
        int seg = segment(h);
        locks[seg].lock();
        try {
            Entry<K, V>[] table = tables[seg];
            int mask = table.length - 1;
            int idx = h & mask;
            int dist = 0;
            while (true) {
                Entry<K, V> e = table[idx];
                if (e == null) {
                    return null;
                }
                if (dist > probeDistance(e.hash, idx, mask)) {
                    return null;
                }
                if (e.hash == h && keyOps.equals(e.key, key)) {
                    return e.value;
                }
                idx = (idx + 1) & mask;
                dist++;
            }
        } finally {
            locks[seg].unlock();
        }
    }

    @SuppressWarnings("unchecked")
    public V getOrPut(K key, Supplier<V> valueSupplier) {
        int h = spread(keyOps.hashCode(key));
        int seg = segment(h);
        locks[seg].lock();
        try {
            Entry<K, V>[] table = tables[seg];
            int mask = table.length - 1;
            int idx = h & mask;
            int dist = 0;
            while (true) {
                Entry<K, V> e = table[idx];
                if (e == null) {
                    break;
                }
                if (dist > probeDistance(e.hash, idx, mask)) {
                    break;
                }
                if (e.hash == h && keyOps.equals(e.key, key)) {
                    return e.value;
                }
                idx = (idx + 1) & mask;
                dist++;
            }
            V value = valueSupplier.get();
            insertAndShift(seg, idx, new Entry<>(h, key, value));
            return value;
        } finally {
            locks[seg].unlock();
        }
    }

    @SuppressWarnings("unchecked")
    public boolean put(K key, V value) {
        int h = spread(keyOps.hashCode(key));
        int seg = segment(h);
        locks[seg].lock();
        try {
            Entry<K, V>[] table = tables[seg];
            int mask = table.length - 1;
            int idx = h & mask;
            int dist = 0;
            while (true) {
                Entry<K, V> e = table[idx];
                if (e == null) {
                    break;
                }
                if (dist > probeDistance(e.hash, idx, mask)) {
                    break;
                }
                if (e.hash == h && keyOps.equals(e.key, key)) {
                    return false;
                }
                idx = (idx + 1) & mask;
                dist++;
            }
            insertAndShift(seg, idx, new Entry<>(h, key, value));
            return true;
        } finally {
            locks[seg].unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private void insertAndShift(int seg, int idx, Entry<K, V> incoming) {
        if (sizes[seg] + 1 > (int) (tables[seg].length * LOAD_FACTOR)) {
            resize(seg);
            // recompute position in the new table
            Entry<K, V>[] table = tables[seg];
            int mask = table.length - 1;
            idx = incoming.hash & mask;
            while (table[idx] != null) {
                int existingDist = probeDistance(table[idx].hash, idx, mask);
                int incomingDist = probeDistance(incoming.hash, idx, mask);
                if (incomingDist > existingDist) {
                    Entry<K, V> displaced = table[idx];
                    table[idx] = incoming;
                    incoming = displaced;
                }
                idx = (idx + 1) & mask;
            }
            table[idx] = incoming;
            sizes[seg]++;
            return;
        }

        Entry<K, V>[] table = tables[seg];
        int mask = table.length - 1;
        while (table[idx] != null) {
            Entry<K, V> displaced = table[idx];
            table[idx] = incoming;
            incoming = displaced;
            idx = (idx + 1) & mask;
        }
        table[idx] = incoming;
        sizes[seg]++;
    }

    @SuppressWarnings("unchecked")
    private void resize(int seg) {
        Entry<K, V>[] oldTable = tables[seg];
        int newCap = oldTable.length << 1;
        Entry<K, V>[] newTable = new Entry[newCap];
        int mask = newCap - 1;
        for (Entry<K, V> e : oldTable) {
            if (e == null) {
                continue;
            }
            int idx = e.hash & mask;
            while (newTable[idx] != null) {
                int existingDist = probeDistance(newTable[idx].hash, idx, mask);
                int incomingDist = probeDistance(e.hash, idx, mask);
                if (incomingDist > existingDist) {
                    Entry<K, V> displaced = newTable[idx];
                    newTable[idx] = e;
                    e = displaced;
                }
                idx = (idx + 1) & mask;
            }
            newTable[idx] = e;
        }
        tables[seg] = newTable;
    }
}
