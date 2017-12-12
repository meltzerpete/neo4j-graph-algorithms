package org.neo4j.graphalgo.core.huge;

import org.apache.lucene.util.ArrayUtil;
import org.neo4j.graphalgo.core.utils.paged.DeltaEncoding;
import org.neo4j.graphdb.Direction;
import org.neo4j.kernel.api.exceptions.EntityNotFoundException;
import org.neo4j.kernel.impl.api.RelationshipVisitor;

import java.util.Arrays;

class RelationshipDeltaEncoding implements RelationshipVisitor<EntityNotFoundException> {

    private final HugeIdMap idMap;
    private Direction direction;

    private long prevTarget;
    private boolean isSorted;

    int length;
    long sourceGraphId;
    long[] targets;

    RelationshipDeltaEncoding(
            HugeIdMap idMap,
            Direction direction) {
        this.idMap = idMap;
        this.direction = direction;
        targets = new long[0];
    }

    final void reset(int degree, long sourceGraphId) {
        this.sourceGraphId = sourceGraphId;
        length = 0;
        prevTarget = -1L;
        isSorted = true;
        if (targets.length < degree) {
            targets = new long[ArrayUtil.oversize(degree, Long.BYTES)];
        }
    }

    final void setDirection(Direction direction) {
        this.direction = direction;
    }

    @Override
    public final void visit(
            final long relationshipId,
            final int typeId,
            final long startNodeId,
            final long endNodeId) throws EntityNotFoundException {
        maybeVisit(
                relationshipId,
                direction == Direction.OUTGOING ? endNodeId : startNodeId);
    }

    long maybeVisit(
            final long relationshipId,
            final long endNodeId) throws EntityNotFoundException {
        long targetId = idMap.toHugeMappedNodeId(endNodeId);
        if (targetId == -1L) {
            return -1L;
        }

        if (isSorted && targetId < prevTarget) {
            isSorted = false;
        }
        return prevTarget = targets[length++] = targetId;
    }

    final long applyDelta() {
        int length = this.length;
        if (length == 0) {
            return 0L;
        }

        long[] targets = this.targets;
        if (!isSorted) {
            Arrays.sort(targets, 0, length);
        }

        long delta = targets[0];
        int writePos = 1;
        long requiredBytes = 4L + DeltaEncoding.vSize(delta);  // length as full-int

        for (int i = 1; i < length; ++i) {
            long nextDelta = targets[i];
            long value = targets[writePos] = nextDelta - delta;
            if (value > 0L) {
                ++writePos;
                requiredBytes += DeltaEncoding.vSize(value);
                delta = nextDelta;
            }
        }

        this.length = writePos;
        return requiredBytes;
    }
}
