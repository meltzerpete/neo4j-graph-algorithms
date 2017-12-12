package org.neo4j.graphalgo.core.huge;

import org.neo4j.graphalgo.api.HugeWeightMapping;
import org.neo4j.graphalgo.core.HugeWeightMap;
import org.neo4j.graphalgo.core.utils.RawValues;
import org.neo4j.graphdb.Direction;
import org.neo4j.kernel.api.ReadOperations;
import org.neo4j.kernel.api.exceptions.EntityNotFoundException;

final class RelationshipDeltaEncodingWithWeights extends RelationshipDeltaEncoding {
    private final int weightId;
    private final HugeWeightMap weights;
    private final ReadOperations readOp;
    private final boolean isBoth;
    private final double defaultValue;

    RelationshipDeltaEncodingWithWeights(
            final HugeIdMap idMap,
            final Direction direction,
            final ReadOperations readOp,
            int weightId,
            HugeWeightMapping weights,
            boolean isBoth) {
        super(idMap, direction);
        this.readOp = readOp;
        this.isBoth = isBoth;
        if (!(weights instanceof HugeWeightMap) || weightId < 0) {
            throw new IllegalArgumentException(
                    "expected weights to be defined");
        }
        this.weightId = weightId;
        this.weights = (HugeWeightMap) weights;
        defaultValue = this.weights.defaultValue();
    }

    @Override
    long maybeVisit(
            final long relationshipId,
            final long endNodeId) throws EntityNotFoundException {
        long targetGraphId = super.maybeVisit(relationshipId, endNodeId);
        if (targetGraphId >= 0) {
            Object value = readOp.relationshipGetProperty(
                    relationshipId,
                    weightId);
            double doubleVal = RawValues.extractValue(value, defaultValue);
            if (doubleVal != defaultValue) {
                long source = sourceGraphId;
                long target = targetGraphId;
                if (isBoth && source > target) {
                    target = source;
                    source = targetGraphId;
                }
                weights.put(source, target, doubleVal);
            }
        }
        return targetGraphId;
    }
}
