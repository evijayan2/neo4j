/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
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
package org.neo4j.kernel.impl.transaction.state;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.neo4j.collection.primitive.Primitive;
import org.neo4j.collection.primitive.PrimitiveLongCollections;
import org.neo4j.collection.primitive.PrimitiveLongObjectMap;
import org.neo4j.collection.primitive.PrimitiveLongObjectVisitor;
import org.neo4j.collection.primitive.PrimitiveLongSet;
import org.neo4j.helpers.collection.Iterables;
import org.neo4j.helpers.collection.Pair;
import org.neo4j.kernel.api.index.NodePropertyUpdate;
import org.neo4j.kernel.api.properties.DefinedProperty;
import org.neo4j.kernel.impl.api.index.UpdateMode;
import org.neo4j.kernel.impl.core.IteratingPropertyReceiver;
import org.neo4j.kernel.impl.store.NodeStore;
import org.neo4j.kernel.impl.store.PropertyStore;
import org.neo4j.kernel.impl.store.record.NodeRecord;
import org.neo4j.kernel.impl.store.record.PropertyRecord;
import org.neo4j.kernel.impl.transaction.command.Command.Mode;
import org.neo4j.kernel.impl.transaction.command.Command.NodeCommand;
import org.neo4j.kernel.impl.transaction.command.Command.PropertyCommand;

import static org.neo4j.kernel.api.index.NodePropertyUpdate.add;
import static org.neo4j.kernel.api.index.NodePropertyUpdate.remove;
import static org.neo4j.kernel.impl.store.NodeLabelsField.parseLabelsField;

/**
 * Derives logical index updates from physical records, provided by {@link NodeCommand node commands} and
 * {@link PropertyCommand property commands}. For some types of updates state from store is also needed,
 * for example if adding a label to a node which already has properties matching existing and online indexes;
 * in that case the properties for that node needs to be read from store since the commands in that transaction
 * cannot itself provide enough information.
 *
 * One instance can be {@link #feed(PrimitiveLongObjectMap, PrimitiveLongObjectMap) fed} data about
 * multiple transactions, to be {@link #iterator() accessed} later.
 */
public class OnlineIndexUpdates implements IndexUpdates
{
    private final NodeStore nodeStore;
    private final PropertyStore propertyStore;
    private final PropertyLoader propertyLoader;
    private final Collection<NodePropertyUpdate> updates = new ArrayList<>();

    public OnlineIndexUpdates( NodeStore nodeStore,
                             PropertyStore propertyStore,
                             PropertyLoader propertyLoader )
    {
        this.nodeStore = nodeStore;
        this.propertyStore = propertyStore;
        this.propertyLoader = propertyLoader;
    }

    @Override
    public Iterator<NodePropertyUpdate> iterator()
    {
        return updates.iterator();
    }

    @Override
    public void collectUpdatedNodeIds( PrimitiveLongSet target )
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void feed( PrimitiveLongObjectMap<List<PropertyCommand>> propertyCommands,
            PrimitiveLongObjectMap<NodeCommand> nodeCommands )
    {
        Map<Pair<Long,Integer>,NodePropertyUpdate> propertyChanges = new HashMap<>();
        gatherUpdatesFromPropertyCommands( nodeCommands, propertyCommands, propertyChanges );
        gatherUpdatesFromNodeCommands( nodeCommands, propertyCommands, propertyChanges );
    }

    private void gatherUpdatesFromPropertyCommands(
            PrimitiveLongObjectMap<NodeCommand> nodeCommands,
            PrimitiveLongObjectMap<List<PropertyCommand>> propCommands,
            final Map<Pair<Long,Integer>,NodePropertyUpdate> propertyLookup )
    {
        propCommands.visitEntries( new PrimitiveLongObjectVisitor<List<PropertyCommand>,RuntimeException>()
        {
            @Override
            public boolean visited( long nodeId, List<PropertyCommand> propertyCommands )
            {
                gatherUpdatesFromPropertyCommandsForNode( nodeId, nodeCommands, propertyCommands );
                return false;
            }
        } );

        for ( NodePropertyUpdate update : updates )
        {
            if ( update.getUpdateMode() == UpdateMode.CHANGED )
            {
                propertyLookup.put( Pair.of( update.getNodeId(), update.getPropertyKeyId() ), update );
            }
        }
    }

    private void gatherUpdatesFromPropertyCommandsForNode( long nodeId,
            PrimitiveLongObjectMap<NodeCommand> nodeCommands,
            List<PropertyCommand> propertyCommandsForNode )
    {
        long[] nodeLabelsBefore, nodeLabelsAfter;
        NodeCommand nodeChanges = nodeCommands.get( nodeId );
        if ( nodeChanges != null )
        {
            nodeLabelsBefore = parseLabelsField( nodeChanges.getBefore() ).get( nodeStore );
            nodeLabelsAfter = parseLabelsField( nodeChanges.getAfter() ).get( nodeStore );
        }
        else
        {
            /* If the node doesn't exist here then we've most likely encountered this scenario:
             * - TX1: Node N exists and has property record P
             * - rotate log
             * - TX2: P gets changed
             * - TX3: N gets deleted (also P, but that's irrelevant for this scenario)
             * - N is persisted to disk for some reason
             * - crash
             * - recover
             * - TX2: P has changed and updates to indexes are gathered. As part of that it tries to read
             *        the labels of N (which does not exist a.t.m.).
             *
             * We can actually (if we disregard any potential inconsistencies) just assume that
             * if this happens and we're in recovery mode that the node in question will be deleted
             * in an upcoming transaction, so just skip this update.
             */
            NodeRecord nodeRecord = nodeStore.getRecord( nodeId );
            nodeLabelsBefore = nodeLabelsAfter = parseLabelsField( nodeRecord ).get( nodeStore );
        }

        propertyStore.toLogicalUpdates( updates,
                Iterables.<PropertyRecordChange,PropertyCommand>cast( propertyCommandsForNode ),
                nodeLabelsBefore, nodeLabelsAfter );
    }

    private void gatherUpdatesFromNodeCommands(
            PrimitiveLongObjectMap<NodeCommand> nodeCommands,
            PrimitiveLongObjectMap<List<PropertyCommand>> propertyCommands,
            final Map<Pair<Long,Integer>,NodePropertyUpdate> propertyLookup )
    {
        nodeCommands.visitEntries( new PrimitiveLongObjectVisitor<NodeCommand,RuntimeException>()
        {
            @Override
            public boolean visited( long key, NodeCommand nodeCommand )
            {
                gatherUpdatesFromNodeCommand( nodeCommand, nodeCommands, propertyCommands, propertyLookup );
                return false;
            }
        } );
    }

    private void gatherUpdatesFromNodeCommand( NodeCommand nodeCommand,
            PrimitiveLongObjectMap<NodeCommand> nodeCommands,
            PrimitiveLongObjectMap<List<PropertyCommand>> propertyCommands,
            Map<Pair<Long,Integer>,NodePropertyUpdate> propertyLookup )
    {
        long nodeId = nodeCommand.getKey();
        long[] labelsBefore = parseLabelsField( nodeCommand.getBefore() ).get( nodeStore );
        long[] labelsAfter = parseLabelsField( nodeCommand.getAfter() ).get( nodeStore );

        if ( nodeCommand.getMode() == Mode.DELETE )
        {
            // For deleted nodes rely on the updates from the perspective of properties to cover it all
            // otherwise we'll get duplicate update during recovery, or cannot load properties if deleted.
            return;
        }

        LabelChangeSummary summary = new LabelChangeSummary( labelsBefore, labelsAfter );
        if ( !summary.hasAddedLabels() && !summary.hasRemovedLabels() )
        {
            return;
        }

        Iterator<DefinedProperty> properties = nodeFullyLoadProperties( nodeId, nodeCommands, propertyCommands );
        while ( properties.hasNext() )
        {
            DefinedProperty property = properties.next();
            int propertyKeyId = property.propertyKeyId();
            if ( summary.hasAddedLabels() )
            {
                Object value = property.value();
                updates.add( add( nodeId, propertyKeyId, value, summary.getAddedLabels() ) );
            }
            if ( summary.hasRemovedLabels() )
            {
                NodePropertyUpdate propertyChange = propertyLookup.get( Pair.of( nodeId, propertyKeyId ) );
                Object value = propertyChange == null ? property.value() : propertyChange.getValueBefore();
                updates.add( remove( nodeId, propertyKeyId, value, summary.getRemovedLabels() ) );
            }
        }
    }

    private Iterator<DefinedProperty> nodeFullyLoadProperties( long nodeId,
            PrimitiveLongObjectMap<NodeCommand> nodeCommands,
            PrimitiveLongObjectMap<List<PropertyCommand>> propertyCommands )
    {
        NodeCommand nodeCommand = nodeCommands.get( nodeId );
        NodeRecord nodeRecord = (nodeCommand == null) ? nodeStore.getRecord( nodeId ) : nodeCommand.getAfter();

        IteratingPropertyReceiver receiver = new IteratingPropertyReceiver();
        PrimitiveLongObjectMap<PropertyRecord> propertiesById =
                propertiesFromCommandsForNode( propertyCommands.get( nodeId ) );
        propertyLoader.nodeLoadProperties( nodeRecord, propertiesById, receiver );
        return receiver;
    }

    private PrimitiveLongObjectMap<PropertyRecord> propertiesFromCommandsForNode(
            List<PropertyCommand> propertyCommands )
    {
        if ( propertyCommands == null )
        {
            return PrimitiveLongCollections.emptyObjectMap();
        }
        PrimitiveLongObjectMap<PropertyRecord> result = Primitive.longObjectMap( propertyCommands.size() );
        for ( PropertyCommand command : propertyCommands )
        {
            PropertyRecord after = command.getAfter();
            if ( after.inUse() && after.isNodeSet() )
            {
                result.put( after.getId(), after );
            }
        }
        return result;
    }
}
