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
package org.neo4j.kernel.impl.transaction.command;

import org.junit.Test;

import org.neo4j.kernel.impl.store.PropertyType;
import org.neo4j.kernel.impl.store.record.NeoStoreRecord;
import org.neo4j.kernel.impl.store.record.RelationshipGroupRecord;
import org.neo4j.kernel.impl.store.record.RelationshipRecord;
import org.neo4j.kernel.impl.transaction.log.InMemoryClosableChannel;
import org.neo4j.storageengine.api.CommandReader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PhysicalLogCommandReaderV3_0Test
{
    @Test
    public void shouldReadRelationshipCommand() throws Throwable
    {
        // Given
        InMemoryClosableChannel channel = new InMemoryClosableChannel();
        RelationshipRecord before = new RelationshipRecord( 42, -1, -1, -1 );
        RelationshipRecord after = new RelationshipRecord( 42, true, 1, 2, 3, 4, 5, 6, 7, true, true );
        new Command.RelationshipCommand( before, after ).serialize( channel );

        // When
        PhysicalLogCommandReaderV3_0 reader = new PhysicalLogCommandReaderV3_0();
        Command command = reader.read( channel );
        assertTrue( command instanceof Command.RelationshipCommand );

        Command.RelationshipCommand relationshipCommand = (Command.RelationshipCommand) command;

        // Then
        assertEquals( before, relationshipCommand.getBefore() );
        assertEquals( after, relationshipCommand.getAfter() );
    }

    @Test
    public void shouldReadRelationshipGroupCommand() throws Throwable
    {
        // Given
        InMemoryClosableChannel channel = new InMemoryClosableChannel();
        RelationshipGroupRecord before = new RelationshipGroupRecord( 42, 3 );
        RelationshipGroupRecord after = new RelationshipGroupRecord( 42, 3, 4, 5, 6, 7, 8, true );
        after.setCreated();

        new Command.RelationshipGroupCommand( before, after ).serialize( channel );

        // When
        PhysicalLogCommandReaderV3_0 reader = new PhysicalLogCommandReaderV3_0();
        Command command = reader.read( channel );
        assertTrue( command instanceof Command.RelationshipGroupCommand);

        Command.RelationshipGroupCommand relationshipGroupCommand = (Command.RelationshipGroupCommand) command;

        // Then
        assertEquals( before, relationshipGroupCommand.getBefore() );
        assertEquals( after, relationshipGroupCommand.getAfter() );
    }

    @Test
    public void shouldReadNeoStoreCommand() throws Throwable
    {
        // Given
        InMemoryClosableChannel channel = new InMemoryClosableChannel();
        NeoStoreRecord before = new NeoStoreRecord();
        NeoStoreRecord after = new NeoStoreRecord();
        after.setNextProp( 42 );

        new Command.NeoStoreCommand( before, after ).serialize( channel );

        // When
        PhysicalLogCommandReaderV3_0 reader = new PhysicalLogCommandReaderV3_0();
        Command command = reader.read( channel );
        assertTrue( command instanceof Command.NeoStoreCommand);

        Command.NeoStoreCommand neoStoreCommand = (Command.NeoStoreCommand) command;

        // Then
        assertEquals( before.getNextProp(), neoStoreCommand.getBefore().getNextProp() );
        assertEquals( after.getNextProp(), neoStoreCommand.getAfter().getNextProp() );
    }

    @Test
    public void shouldReadSomeCommands() throws Exception
    {
        // GIVEN
        InMemoryClosableChannel channel = new InMemoryClosableChannel();
        Commands.createNode( 0 ).serialize( channel );
        Commands.createNode( 1 ).serialize( channel );
        Commands.createRelationshipTypeToken( 0, 0 ).serialize( channel );
        Commands.createRelationship( 0, 0, 1, 0 ).serialize( channel );
        Commands.createPropertyKeyToken( 0, 0 ).serialize( channel );
        Commands.createProperty( 0, PropertyType.SHORT_STRING, 0 ).serialize( channel );
        CommandReader reader = new PhysicalLogCommandReaderV3_0();

        // THEN
        assertTrue( reader.read( channel ) instanceof Command.NodeCommand );
        assertTrue( reader.read( channel ) instanceof Command.NodeCommand );
        assertTrue( reader.read( channel ) instanceof Command.RelationshipTypeTokenCommand );
        assertTrue( reader.read( channel ) instanceof Command.RelationshipCommand );
        assertTrue( reader.read( channel ) instanceof Command.PropertyKeyTokenCommand );
        assertTrue( reader.read( channel ) instanceof Command.PropertyCommand );
    }
}
