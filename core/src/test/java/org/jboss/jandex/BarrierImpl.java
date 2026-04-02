package org.jboss.jandex;

import java.util.concurrent.locks.AbstractQueuedSynchronizer;

final class BarrierImpl implements Barrier {
    private static class Sync extends AbstractQueuedSynchronizer {
        protected int tryAcquireShared(int ignore) {
            return getState() != 0 ? 1 : -1;
        }

        protected boolean tryReleaseShared(int ignore) {
            setState(1);
            return true;
        }
    }

    private final Sync sync = new Sync();

    @Override
    public void await() {
        sync.acquireShared(1);
    }

    @Override
    public void open() {
        sync.releaseShared(1);
    }
}
