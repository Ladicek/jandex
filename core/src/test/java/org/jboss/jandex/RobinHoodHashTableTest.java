package org.jboss.jandex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class RobinHoodHashTableTest {
    private static final RobinHoodHashTable.KeyOps<String> KEY_OPS = new RobinHoodHashTable.KeyOps<String>() {
        @Override
        public boolean equals(String key1, String key2) {
            return key1.equals(key2);
        }

        @Override
        public int hashCode(String key) {
            return key.hashCode();
        }
    };

    private static final RobinHoodHashTable.KeyOps<String> COLLIDING_KEY_OPS = new RobinHoodHashTable.KeyOps<String>() {
        @Override
        public boolean equals(String key1, String key2) {
            return key1.equals(key2);
        }

        @Override
        public int hashCode(String key) {
            return 42;
        }
    };

    private RobinHoodHashTable<String, String> table;

    @BeforeEach
    void setUp() {
        table = new RobinHoodHashTable<>(KEY_OPS);
    }

    @Test
    void getFromEmpty() {
        assertNull(table.get("missing"));
    }

    @Test
    void putAndGet() {
        assertTrue(table.put("a", "1"));
        assertEquals("1", table.get("a"));
    }

    @Test
    void putDuplicate() {
        assertTrue(table.put("a", "1"));
        assertFalse(table.put("a", "2"));
        assertEquals("1", table.get("a"));
    }

    @Test
    void getMissing() {
        table.put("a", "1");
        assertNull(table.get("b"));
    }

    @Test
    void nullValue() {
        assertTrue(table.put("a", null));
        assertNull(table.get("a"));
        // getOrPut should find the existing entry even though value is null
        String result = table.getOrPut("a", () -> "fallback");
        assertNull(result);
    }

    @Test
    void getOrPutInsertsWhenAbsent() {
        String result = table.getOrPut("a", () -> "1");
        assertEquals("1", result);
        assertEquals("1", table.get("a"));
    }

    @Test
    void getOrPutReturnsExistingWhenPresent() {
        table.put("a", "1");
        AtomicInteger calls = new AtomicInteger();
        String result = table.getOrPut("a", () -> {
            calls.incrementAndGet();
            return "2";
        });
        assertEquals("1", result);
        assertEquals(0, calls.get());
    }

    @Test
    void manyEntries() {
        int count = 1000;
        for (int i = 0; i < count; i++) {
            assertTrue(table.put("key" + i, "val" + i));
        }
        for (int i = 0; i < count; i++) {
            assertEquals("val" + i, table.get("key" + i));
        }
        assertNull(table.get("key" + count));
    }

    @Test
    void manyDuplicates() {
        int count = 500;
        for (int i = 0; i < count; i++) {
            assertTrue(table.put("key" + i, "val" + i));
        }
        for (int i = 0; i < count; i++) {
            assertFalse(table.put("key" + i, "other" + i));
        }
        for (int i = 0; i < count; i++) {
            assertEquals("val" + i, table.get("key" + i));
        }
    }

    @Test
    void collidingHashes() {
        RobinHoodHashTable<String, String> t = new RobinHoodHashTable<>(COLLIDING_KEY_OPS);

        for (int i = 0; i < 50; i++) {
            assertTrue(t.put("key" + i, "val" + i));
        }
        for (int i = 0; i < 50; i++) {
            assertEquals("val" + i, t.get("key" + i));
        }
        assertNull(t.get("missing"));
    }

    @Test
    void getOrPutMixedWithPut() {
        table.put("a", "1");
        table.getOrPut("b", () -> "2");
        table.put("c", "3");
        table.getOrPut("d", () -> "4");

        assertEquals("1", table.get("a"));
        assertEquals("2", table.get("b"));
        assertEquals("3", table.get("c"));
        assertEquals("4", table.get("d"));
    }

    // --- concurrent tests ---

    private static final int THREADS = 16;
    private static final int KEYS_PER_THREAD = 200;
    private static final int ITERATIONS = 100;

    @Test
    void concurrentDisjointWriters() throws Exception {
        for (int iter = 0; iter < ITERATIONS; iter++) {
            RobinHoodHashTable<String, String> t = new RobinHoodHashTable<>(KEY_OPS);
            Barrier start = Barrier.create();
            List<Thread> threads = new ArrayList<>();
            for (int threadIdx = 0; threadIdx < THREADS; threadIdx++) {
                int base = threadIdx * KEYS_PER_THREAD;
                threads.add(new Thread(() -> {
                    start.await();
                    for (int i = 0; i < KEYS_PER_THREAD; i++) {
                        t.put("key" + (base + i), "val" + (base + i));
                    }
                }));
            }
            startAndJoinAll(threads, start);
            for (int i = 0; i < THREADS * KEYS_PER_THREAD; i++) {
                assertEquals("val" + i, t.get("key" + i), "missing key" + i + " in iteration " + iter);
            }
        }
    }

    @Test
    void concurrentGetOrPutSingleInvocation() throws Exception {
        for (int iter = 0; iter < ITERATIONS; iter++) {
            RobinHoodHashTable<String, String> t = new RobinHoodHashTable<>(KEY_OPS);
            AtomicIntegerArray supplierCalls = new AtomicIntegerArray(KEYS_PER_THREAD);
            Barrier start = Barrier.create();
            List<Thread> threads = new ArrayList<>();
            for (int threadIdx = 0; threadIdx < THREADS; threadIdx++) {
                threads.add(new Thread(() -> {
                    start.await();
                    for (int i = 0; i < KEYS_PER_THREAD; i++) {
                        final int idx = i;
                        String result = t.getOrPut("key" + idx, () -> {
                            supplierCalls.incrementAndGet(idx);
                            return "val" + idx;
                        });
                        assertEquals("val" + idx, result);
                    }
                }));
            }
            startAndJoinAll(threads, start);
            for (int i = 0; i < KEYS_PER_THREAD; i++) {
                assertEquals(1, supplierCalls.get(i),
                        "supplier for key" + i + " called " + supplierCalls.get(i) + " times in iteration " + iter);
            }
        }
    }

    @Test
    void concurrentPutDeduplication() throws Exception {
        for (int iter = 0; iter < ITERATIONS; iter++) {
            RobinHoodHashTable<String, String> t = new RobinHoodHashTable<>(KEY_OPS);
            AtomicIntegerArray successCount = new AtomicIntegerArray(KEYS_PER_THREAD);
            Barrier start = Barrier.create();
            List<Thread> threads = new ArrayList<>();
            for (int threadIdx = 0; threadIdx < THREADS; threadIdx++) {
                final int tid = threadIdx;
                threads.add(new Thread(() -> {
                    start.await();
                    for (int i = 0; i < KEYS_PER_THREAD; i++) {
                        if (t.put("key" + i, "val" + tid + "_" + i)) {
                            successCount.incrementAndGet(i);
                        }
                    }
                }));
            }
            startAndJoinAll(threads, start);
            for (int i = 0; i < KEYS_PER_THREAD; i++) {
                assertEquals(1, successCount.get(i),
                        "put for key" + i + " succeeded " + successCount.get(i) + " times in iteration " + iter);
            }
        }
    }

    @Test
    void concurrentReadWrite() throws Exception {
        int keyCount = 500;
        for (int iter = 0; iter < ITERATIONS; iter++) {
            RobinHoodHashTable<String, String> t = new RobinHoodHashTable<>(KEY_OPS);
            Barrier start = Barrier.create();
            AtomicInteger errors = new AtomicInteger();
            List<Thread> threads = new ArrayList<>();
            // half the threads write
            for (int threadIdx = 0; threadIdx < THREADS / 2; threadIdx++) {
                threads.add(new Thread(() -> {
                    start.await();
                    for (int i = 0; i < keyCount; i++) {
                        t.put("key" + i, "val" + i);
                    }
                }));
            }
            // half the threads read
            for (int threadIdx = 0; threadIdx < THREADS / 2; threadIdx++) {
                threads.add(new Thread(() -> {
                    start.await();
                    for (int i = 0; i < keyCount; i++) {
                        String val = t.get("key" + i);
                        // must be null (not yet inserted) or the correct value
                        if (val != null && !val.equals("val" + i)) {
                            errors.incrementAndGet();
                        }
                    }
                }));
            }
            startAndJoinAll(threads, start);
            assertEquals(0, errors.get(), "corrupted reads in iteration " + iter);
        }
    }

    @Test
    void concurrentCollidingHashes() throws Exception {
        for (int iter = 0; iter < ITERATIONS; iter++) {
            RobinHoodHashTable<String, String> t = new RobinHoodHashTable<>(COLLIDING_KEY_OPS);
            AtomicIntegerArray supplierCalls = new AtomicIntegerArray(KEYS_PER_THREAD);
            Barrier start = Barrier.create();
            List<Thread> threads = new ArrayList<>();
            for (int threadIdx = 0; threadIdx < THREADS; threadIdx++) {
                threads.add(new Thread(() -> {
                    start.await();
                    for (int i = 0; i < KEYS_PER_THREAD; i++) {
                        final int idx = i;
                        String result = t.getOrPut("key" + idx, () -> {
                            supplierCalls.incrementAndGet(idx);
                            return "val" + idx;
                        });
                        assertEquals("val" + idx, result);
                    }
                }));
            }
            startAndJoinAll(threads, start);
            for (int i = 0; i < KEYS_PER_THREAD; i++) {
                assertEquals(1, supplierCalls.get(i),
                        "supplier for key" + i + " called " + supplierCalls.get(i) + " times in iteration " + iter);
                assertEquals("val" + i, t.get("key" + i), "wrong value for key" + i + " in iteration " + iter);
            }
        }
    }

    private static void startAndJoinAll(List<Thread> threads, Barrier start) throws InterruptedException {
        for (Thread t : threads) {
            t.start();
        }
        start.open();
        for (Thread t : threads) {
            t.join();
        }
    }
}
