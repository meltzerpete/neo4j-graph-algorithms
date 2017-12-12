package org.neo4j.graphalgo.core.huge;

import org.neo4j.graphalgo.api.HugeWeightMapping;
import org.neo4j.graphalgo.core.utils.ImportProgress;
import org.neo4j.graphalgo.core.utils.StatementTask;
import org.neo4j.graphalgo.core.utils.paged.ByteArray;
import org.neo4j.graphalgo.core.utils.paged.LongArray;
import org.neo4j.graphdb.Direction;
import org.neo4j.kernel.api.ReadOperations;
import org.neo4j.kernel.api.Statement;
import org.neo4j.kernel.api.exceptions.EntityNotFoundException;
import org.neo4j.kernel.impl.api.store.RelationshipIterator;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

final class HugeRelationshipImporter extends StatementTask<Void, EntityNotFoundException> {
    private final int batchIndex;
    private final ImportProgress progress;
    private final NodeQueue nodes;
    private final HugeIdMap idMap;
    private final LongArray inOffsets;
    private final LongArray outOffsets;
    private final ByteArray.LocalAllocator inAllocator;
    private final ByteArray.LocalAllocator outAllocator;
    private final int[] relationId;
    private final int weightId;
    private final HugeWeightMapping weights;
    private final boolean loadsBoth;
    private final boolean undirected;

    HugeRelationshipImporter(
            GraphDatabaseAPI api,
            int batchIndex,
            NodeQueue nodes,
            ImportProgress progress,
            HugeIdMap idMap,
            LongArray inOffsets,
            LongArray outOffsets,
            ByteArray inAdjacency,
            ByteArray outAdjacency,
            boolean undirected,
            int[] relationId,
            int weightId,
            HugeWeightMapping weights) {
        super(api);
        this.batchIndex = batchIndex;
        this.progress = progress;
        this.nodes = nodes;
        this.idMap = idMap;
        this.inOffsets = inOffsets;
        this.outOffsets = outOffsets;
        this.inAllocator = inAdjacency != null ? inAdjacency.newAllocator() : null;
        this.outAllocator = outAdjacency != null ? outAdjacency.newAllocator() : null;
        this.relationId = relationId;
        this.weightId = weightId;
        this.weights = weights;
        this.loadsBoth = inAdjacency != null && outAdjacency != null;
        this.undirected = undirected;
    }

    @Override
    public String threadName() {
        return "HugeRelationshipImport-" + batchIndex;
    }

    @Override
    public Void apply(final Statement statement) throws EntityNotFoundException {
        ReadOperations readOp = statement.readOperations();

        final RelationshipLoader loader;
        if (undirected) {
            assert outOffsets != null;
            assert outAllocator != null;

            RelationshipDeltaEncoding importer = newImporter(readOp, Direction.BOTH);
            loader = (neo, node) -> readUndirectedRelationships(
                    node,
                    neo,
                    readOp,
                    outOffsets,
                    outAllocator,
                    importer
            );
        } else {

            if (inAllocator != null) {
                RelationshipDeltaEncoding inImporter = newImporter(readOp, Direction.INCOMING);
                if (outAllocator != null) {
                    RelationshipDeltaEncoding outImporter = newImporter(readOp, Direction.OUTGOING);
                    loader = (neo, node) -> {
                        readRelationships(
                                node,
                                neo,
                                readOp,
                                Direction.OUTGOING,
                                outOffsets,
                                outAllocator,
                                outImporter
                        );
                        readRelationships(
                                node,
                                neo,
                                readOp,
                                Direction.INCOMING,
                                inOffsets,
                                inAllocator,
                                inImporter
                        );
                    };
                } else {
                    loader = (neo, node) -> readRelationships(
                            node,
                            neo,
                            readOp,
                            Direction.INCOMING,
                            inOffsets,
                            inAllocator,
                            inImporter
                    );
                }
            } else {
                if (outAllocator != null) {
                    RelationshipDeltaEncoding outImporter = newImporter(readOp, Direction.OUTGOING);
                    loader = (neo, node) -> readRelationships(
                            node,
                            neo,
                            readOp,
                            Direction.OUTGOING,
                            outOffsets,
                            outAllocator,
                            outImporter
                    );
                } else {
                    loader = (neo, node) -> {};
                }
            }
        }

        NodeQueue nodes = this.nodes;
        long nodeId;
        while ((nodeId = nodes.next()) != -1L) {
            loader.apply(idMap.toOriginalNodeId(nodeId), nodeId);
            progress.relProgress();
        }
        return null;
    }

    private RelationshipDeltaEncoding newImporter(
            ReadOperations readOp,
            Direction direction) {
        if (weightId >= 0) {
            return new RelationshipDeltaEncodingWithWeights(
                    idMap,
                    direction,
                    readOp,
                    weightId,
                    weights,
                    loadsBoth);
        }
        return new RelationshipDeltaEncoding(idMap, direction);
    }

    private void readRelationships(
            long sourceGraphId,
            long sourceNodeId,
            ReadOperations readOp,
            Direction direction,
            LongArray offsets,
            ByteArray.LocalAllocator allocator,
            RelationshipDeltaEncoding delta) throws EntityNotFoundException {

        int degree = degree(sourceNodeId, readOp, direction);
        if (degree <= 0) {
            return;
        }

        RelationshipIterator rs = relationships(sourceNodeId, readOp, direction);
        delta.reset(degree, sourceGraphId);
        while (rs.hasNext()) {
            rs.relationshipVisit(rs.next(), delta);
        }

        long requiredSize = delta.applyDelta();
        degree = delta.length;
        if (degree == 0) {
            return;
        }

        long adjacencyIdx = allocator.allocate(requiredSize);
        offsets.set(sourceGraphId, adjacencyIdx);

        ByteArray.BulkAdder bulkAdder = allocator.adder;
        bulkAdder.addUnsignedInt(degree);
        long[] targets = delta.targets;
        for (int i = 0; i < degree; i++) {
            bulkAdder.addVLong(targets[i]);
        }
    }

    private void readUndirectedRelationships(
            long sourceGraphId,
            long sourceNodeId,
            ReadOperations readOp,
            LongArray offsets,
            ByteArray.LocalAllocator allocator,
            RelationshipDeltaEncoding delta) throws EntityNotFoundException {

        int degree = degree(sourceNodeId, readOp, Direction.BOTH);
        if (degree > 0) {
            delta.reset(degree, sourceGraphId);
            delta.setDirection(Direction.INCOMING);
            RelationshipIterator rs = relationships(sourceNodeId, readOp, Direction.INCOMING);
            while (rs.hasNext()) {
                rs.relationshipVisit(rs.next(), delta);
            }
            delta.setDirection(Direction.OUTGOING);
            rs = relationships(sourceNodeId, readOp, Direction.OUTGOING);
            while (rs.hasNext()) {
                rs.relationshipVisit(rs.next(), delta);
            }

            long requiredSize = delta.applyDelta();
            degree = delta.length;
            long adjacencyIdx = allocator.allocate(requiredSize);
            offsets.set(sourceGraphId, adjacencyIdx);

            ByteArray.BulkAdder bulkAdder = allocator.adder;
            bulkAdder.addUnsignedInt(degree);
            long[] targets = delta.targets;
            for (int i = 0; i < degree; i++) {
                bulkAdder.addVLong(targets[i]);
            }
        }
    }

    private int degree(
            long sourceNodeId,
            ReadOperations readOp,
            Direction direction) throws EntityNotFoundException {
        return relationId == null
                ? readOp.nodeGetDegree(sourceNodeId, direction)
                : readOp.nodeGetDegree(sourceNodeId, direction, relationId[0]);
    }

    private RelationshipIterator relationships(
            long sourceNodeId,
            ReadOperations readOp,
            Direction direction) throws EntityNotFoundException {
        return relationId == null
                ? readOp.nodeGetRelationships(sourceNodeId, direction)
                : readOp.nodeGetRelationships(sourceNodeId, direction, relationId);
    }
}
