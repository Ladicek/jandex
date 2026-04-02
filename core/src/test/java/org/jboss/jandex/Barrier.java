package org.jboss.jandex;

interface Barrier {
    void await();

    void open();

    static Barrier create() {
        return new BarrierImpl();
    }
}
