package org.neo4j.graphalgo.core.huge;

import org.neo4j.kernel.api.exceptions.EntityNotFoundException;

@FunctionalInterface
interface RelationshipLoader {
    void apply(long neoId, long nodeId) throws EntityNotFoundException;
}
