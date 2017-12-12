package org.neo4j.graphalgo.core.huge;

import java.util.concurrent.atomic.AtomicLong;

final class NodeQueue {
    private final AtomicLong current = new AtomicLong();
    private final long max;

    NodeQueue(final long max) {
        this.max = max;
    }

    long next() {
        long nodeId = current.getAndIncrement();
        return nodeId < max ? nodeId : -1L;
    }
}
