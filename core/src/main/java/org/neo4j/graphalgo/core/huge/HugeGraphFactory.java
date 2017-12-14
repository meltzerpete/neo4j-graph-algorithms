/**
 * Copyright (c) 2017 "Neo4j, Inc." <http://neo4j.com>
 *
 * This file is part of Neo4j Graph Algorithms <http://github.com/neo4j-contrib/neo4j-graph-algorithms>.
 *
 * Neo4j Graph Algorithms is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.graphalgo.core.huge;

import org.neo4j.graphalgo.api.GraphFactory;
import org.neo4j.graphalgo.api.GraphSetup;
import org.neo4j.graphalgo.api.HugeGraph;
import org.neo4j.graphalgo.api.HugeWeightMapping;
import org.neo4j.graphalgo.core.GraphDimensions;
import org.neo4j.graphalgo.core.utils.ImportProgress;
import org.neo4j.graphalgo.core.utils.ParallelUtil;
import org.neo4j.graphalgo.core.utils.paged.AllocationTracker;
import org.neo4j.graphalgo.core.utils.paged.ByteArray;
import org.neo4j.graphalgo.core.utils.paged.LongArray;
import org.neo4j.helpers.Exceptions;
import org.neo4j.kernel.api.exceptions.EntityNotFoundException;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

import java.util.Arrays;

public final class HugeGraphFactory extends GraphFactory {

    public HugeGraphFactory(
            GraphDatabaseAPI api,
            GraphSetup setup) {
        super(api, setup);
    }

    @Override
    public HugeGraph build() {
        try {
            return importGraph();
        } catch (EntityNotFoundException e) {
            throw Exceptions.launderedException(e);
        }
    }


    private HugeGraph importGraph() throws EntityNotFoundException {
        int concurrency = setup.concurrency();
        AllocationTracker tracker = setup.tracker;
        HugeWeightMapping weights = hugeWeightMapping(tracker, dimensions.weightId(), setup.relationDefaultWeight);
        HugeIdMap mapping = loadHugeIdMap(tracker);
        HugeGraph graph = loadRelationships(dimensions, mapping, weights, concurrency, tracker, progress);
        progressLogger.logDone(tracker);
        return graph;
    }

    private HugeGraph loadRelationships(
            GraphDimensions dimensions,
            HugeIdMap mapping,
            HugeWeightMapping weights,
            int concurrency,
            AllocationTracker tracker,
            ImportProgress progress) {
        if (setup.loadAsUndirected) {
            return loadUndirectedRelationships(
                    dimensions,
                    mapping,
                    weights,
                    concurrency,
                    tracker,
                    progress);
        }

        final long nodeCount = dimensions.hugeNodeCount();
        final int[] relationId = dimensions.relationId();
        final int weightId = dimensions.weightId();

        LongArray inOffsets = null;
        LongArray outOffsets = null;
        ByteArray inAdjacency = null;
        ByteArray outAdjacency = null;
        if (setup.loadIncoming) {
            inOffsets = LongArray.newArray(nodeCount, tracker);
            inAdjacency = ByteArray.newArray(0, tracker);
        }
        if (setup.loadOutgoing) {
            outOffsets = LongArray.newArray(nodeCount, tracker);
            outAdjacency = ByteArray.newArray(nodeCount, tracker);
        }
        if (setup.loadIncoming || setup.loadOutgoing) {
            // needs final b/c of reference from lambda
            final LongArray finalInOffsets = inOffsets;
            final LongArray finalOutOffsets = outOffsets;
            final ByteArray finalInAdjacency = inAdjacency;
            final ByteArray finalOutAdjacency = outAdjacency;

            NodeQueue nodes = new NodeQueue(nodeCount);
            HugeRelationshipImporter[] tasks = new HugeRelationshipImporter[concurrency];
            Arrays.setAll(tasks, i -> new HugeRelationshipImporter(
                    api,
                    i,
                    nodes,
                    progress,
                    mapping,
                    finalInOffsets,
                    finalOutOffsets,
                    finalInAdjacency,
                    finalOutAdjacency,
                    false,
                    relationId,
                    weightId,
                    weights
            ));
            ParallelUtil.run(Arrays.asList(tasks), threadPool);
        }

        return new HugeGraphImpl(
                tracker,
                mapping,
                weights,
                inAdjacency,
                outAdjacency,
                inOffsets,
                outOffsets
        );
    }

    private HugeGraph loadUndirectedRelationships(
            GraphDimensions dimensions,
            HugeIdMap mapping,
            HugeWeightMapping weights,
            int concurrency,
            AllocationTracker tracker,
            ImportProgress progress) {
        final long nodeCount = dimensions.hugeNodeCount();
        final int[] relationId = dimensions.relationId();
        final int weightId = dimensions.weightId();

        LongArray offsets = LongArray.newArray(nodeCount, tracker);
        ByteArray adjacency = ByteArray.newArray(0, tracker);

        NodeQueue nodes = new NodeQueue(nodeCount);
        HugeRelationshipImporter[] tasks = new HugeRelationshipImporter[concurrency];
        Arrays.setAll(tasks, i -> new HugeRelationshipImporter(
                api,
                i,
                nodes,
                progress,
                mapping,
                null,
                offsets,
                null,
                adjacency,
                true,
                relationId,
                weightId,
                weights
        ));
        ParallelUtil.run(Arrays.asList(tasks), threadPool);

        return new HugeGraphImpl(
                tracker,
                mapping,
                weights,
                null,
                adjacency,
                null,
                offsets
        );
    }
}
