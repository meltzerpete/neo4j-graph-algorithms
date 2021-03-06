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
package org.neo4j.graphalgo.core.sources;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.neo4j.graphalgo.api.RelationshipConsumer;

import static org.mockito.Matchers.*;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * @author mknblch
 */
@RunWith(MockitoJUnitRunner.class)
public class BufferedAllRelationshipIteratorTest {

    private static BufferedAllRelationshipIterator iterator;

    @Mock
    private RelationshipConsumer relationConsumer;

    @BeforeClass
    public static void setupGraph() {

        iterator = BufferedAllRelationshipIterator.builder()
                .add(0, 1)
                .add(0, 2)
                .add(1, 2)
                .build();
    }

    @Test
    public void testRelations() throws Exception {
        iterator.forEachRelationship(relationConsumer);
        verify(relationConsumer, times(3)).accept(anyInt(), anyInt(), anyLong());
        verify(relationConsumer, times(1)).accept(eq(0), eq(1), anyLong());
        verify(relationConsumer, times(1)).accept(eq(0), eq(2), anyLong());
        verify(relationConsumer, times(1)).accept(eq(1), eq(2), anyLong());
    }
}
