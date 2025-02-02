/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.coreedge.raft.state.explorer.action;

import java.util.LinkedList;

import org.neo4j.coreedge.server.RaftTestMember;
import org.neo4j.coreedge.raft.RaftMessages;
import org.neo4j.coreedge.raft.state.explorer.ClusterState;

public class OutOfOrderDelivery implements Action
{
    private final RaftTestMember member;

    public OutOfOrderDelivery( RaftTestMember member )
    {
        this.member = member;
    }

    @Override
    public ClusterState advance( ClusterState previous )
    {
        ClusterState newClusterState = new ClusterState( previous );
        LinkedList<RaftMessages.Message<RaftTestMember>> inboundQueue = new LinkedList<>( previous.queues.get( member ) );
        if ( inboundQueue.size() < 2 )
        {
            return previous;
        }
        RaftMessages.Message<RaftTestMember> message = inboundQueue.poll();
        inboundQueue.add( 1, message );

        newClusterState.queues.put( member, inboundQueue );
        return newClusterState;
    }
}
