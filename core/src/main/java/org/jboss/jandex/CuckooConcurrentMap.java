package org.jboss.jandex;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * A concurrent hash map based on the Striped Concurrent Cuckoo Hashing algorithm
 * in The Art of Multiprocessor Programming by Maurice Herlihy and Nir Shavit.
 * <p>
 * Keys must not be {@code null}. Values may be {@code null}.
 * <p>
 * <strong>This map does not use the classic {@link Object#equals(Object) equals()}
 * and {@link Object#hashCode() hashCode()} methods from {@code Object}!</strong> Instead,
 * it uses the {@link KeyOps} interface, whose instance must be passed
 * to the {@code CuckooConcurrentMap} constructor.
 */
final class CuckooConcurrentMap<K, V> {
    interface KeyOps<K> {
        boolean equals(K key1, K key2);

        int hashCode(K key);
    }

    static final class Entry<K, V> {
        final K key;
        final V value;

        Entry(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private static final int DEFAULT_CAPACITY = 16;
    private static final int PROBE_SIZE = 8;
    private static final int THRESHOLD = PROBE_SIZE / 2;
    private static final int LIMIT = 8;

    private final static int ONLY_POSITIVE_MASK = 0x7F_FF_FF_FF;
    private static final int GOLDEN_RATIO_MULTIPLIER = 0x9E_37_79_B9;

    private volatile int capacity;
    private volatile ArrayList<Entry<K, V>>[][] tables;
    private final ReentrantLock[][] locks;
    private final KeyOps<K> keyOps;

    public CuckooConcurrentMap(KeyOps<K> keyOps) {
        this(DEFAULT_CAPACITY, keyOps);
    }

    private CuckooConcurrentMap(int capacity, KeyOps<K> keyOps) {
        this.capacity = capacity < 2 ? 2 : Integer.highestOneBit(capacity - 1) << 1;
        this.tables = new ArrayList[2][capacity];
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < this.capacity; j++) {
                this.tables[i][j] = new ArrayList<>(PROBE_SIZE);
            }
        }
        this.locks = new ReentrantLock[2][capacity];
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < capacity; j++) {
                this.locks[i][j] = new ReentrantLock();
            }
        }
        this.keyOps = Objects.requireNonNull(keyOps);
    }

    private int hash0(K key) {
        return keyOps.hashCode(key) & ONLY_POSITIVE_MASK;
    }

    private int hash1(K key) {
        return (keyOps.hashCode(key) * GOLDEN_RATIO_MULTIPLIER) & ONLY_POSITIVE_MASK;
    }

    private Entry<K, V> findInList(ArrayList<Entry<K, V>> list, K key) {
        for (Entry<K, V> entry : list) {
            if (keyOps.equals(entry.key, key)) {
                return entry;
            }
        }
        return null;
    }

    private Entry<K, V> find(K key) {
        int h0 = hash0(key) % capacity;
        Entry<K, V> e0 = findInList(tables[0][h0], key);
        if (e0 != null) {
            return e0;
        }
        int h1 = hash1(key) % capacity;
        Entry<K, V> e1 = findInList(tables[1][h1], key);
        if (e1 != null) {
            return e1;
        }
        return null;
    }

    public V get(K key) {
        acquire(key);
        try {
            Entry<K, V> entry = find(key);
            return entry != null ? entry.value : null;
        } finally {
            release(key);
        }
    }

    public V getOrPut(K key, Supplier<V> valueSupplier) {
        acquire(key);
        try {
            Entry<K, V> entry = find(key);
            if (entry != null) {
                return entry.value;
            }
            V value = valueSupplier.get();
            put(key, value);
            return value;
        } finally {
            release(key);
        }
    }

    public boolean put(K key, V value) {
        return put(new Entry<>(key, value));
    }

    private boolean put(Entry<K, V> entry) {
        K key = entry.key;
        acquire(key);
        int h0 = hash0(key) % capacity;
        int h1 = hash1(key) % capacity;
        int i = -1;
        int h = -1;
        boolean mustResize = false;
        try {
            if (find(key) != null) {
                return false;
            }
            ArrayList<Entry<K, V>> list0 = tables[0][h0];
            ArrayList<Entry<K, V>> list1 = tables[1][h1];
            if (list0.size() < THRESHOLD) {
                list0.add(entry);
                return true;
            } else if (list1.size() < THRESHOLD) {
                list1.add(entry);
                return true;
            } else if (list0.size() < PROBE_SIZE) {
                list0.add(entry);
                i = 0;
                h = h0;
            } else if (list1.size() < PROBE_SIZE) {
                list1.add(entry);
                i = 1;
                h = h1;
            } else {
                mustResize = true;
            }
        } finally {
            release(key);
        }

        if (mustResize) {
            resize();
            put(entry);
        } else if (!relocate(i, h)) {
            resize();
        }
        return true;
    }

    public boolean remove(K key) {
        acquire(key);
        try {
            ArrayList<Entry<K, V>> list0 = tables[0][hash0(key) % capacity];
            Entry<K, V> e0 = findInList(list0, key);
            if (e0 != null) {
                list0.remove(e0);
                return true;
            }
            ArrayList<Entry<K, V>> list1 = tables[1][hash1(key) % capacity];
            Entry<K, V> e1 = findInList(list1, key);
            if (e1 != null) {
                list1.remove(e1);
                return true;
            }
            return false;
        } finally {
            release(key);
        }
    }

    private void acquire(K key) {
        locks[0][hash0(key) % locks[0].length].lock();
        locks[1][hash1(key) % locks[1].length].lock();
    }

    private void release(K key) {
        locks[0][hash0(key) % locks[0].length].unlock();
        locks[1][hash1(key) % locks[1].length].unlock();
    }

    private void resize() {
        int oldCapacity = capacity;
        for (ReentrantLock lock : locks[0]) {
            lock.lock();
        }
        try {
            if (capacity != oldCapacity) {
                return;
            }
            ArrayList<Entry<K, V>>[][] oldTables = tables;
            capacity <<= 1;
            tables = new ArrayList[2][capacity];
            for (ArrayList<Entry<K, V>>[] table : tables) {
                for (int i = 0; i < table.length; i++) {
                    table[i] = new ArrayList<>(PROBE_SIZE);
                }
            }
            for (ArrayList<Entry<K, V>>[] table : oldTables) {
                for (ArrayList<Entry<K, V>> list : table) {
                    for (Entry<K, V> entry : list) {
                        put(entry);
                    }
                }
            }
        } finally {
            for (ReentrantLock lock : locks[0]) {
                lock.unlock();
            }
        }
    }

    private boolean relocate(int i, int hi) {
        int j = 1 - i;
        int hj = 0;
        for (int round = 0; round < LIMIT; round++) {
            ArrayList<Entry<K, V>> iList = tables[i][hi];
            Entry<K, V> entry = iList.get(0);
            K key = entry.key;
            switch (i) {
                case 0:
                    hj = hash1(key) % capacity;
                    break;
                case 1:
                    hj = hash0(key) % capacity;
                    break;
            }

            acquire(key);
            ArrayList<Entry<K, V>> jList = tables[j][hj];
            try {
                if (iList.remove(entry)) {
                    if (jList.size() < THRESHOLD) {
                        jList.add(entry);
                        return true;
                    } else if (jList.size() < PROBE_SIZE) {
                        jList.add(entry);
                        i = 1 - i;
                        hi = hj;
                        j = 1 - j;
                    } else {
                        iList.add(entry);
                        return false;
                    }
                } else if (iList.size() >= THRESHOLD) {
                    continue;
                } else {
                    return true;
                }
            } finally {
                release(key);
            }
        }
        return false;
    }
}
