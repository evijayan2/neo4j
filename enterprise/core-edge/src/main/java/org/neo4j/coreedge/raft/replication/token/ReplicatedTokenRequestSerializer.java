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
package org.neo4j.coreedge.raft.replication.token;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import org.neo4j.coreedge.raft.net.NetworkFlushableChannelNetty4;
import org.neo4j.coreedge.raft.net.NetworkReadableClosableChannelNetty4;
import org.neo4j.coreedge.raft.replication.StringMarshal;
import org.neo4j.kernel.impl.storageengine.impl.recordstorage.RecordStorageCommandReaderFactory;
import org.neo4j.kernel.impl.transaction.command.Command;
import org.neo4j.kernel.impl.transaction.log.ReadableClosablePositionAwareChannel;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryCommand;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryReader;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryWriter;
import org.neo4j.kernel.impl.transaction.log.entry.VersionAwareLogEntryReader;
import org.neo4j.storageengine.api.StorageCommand;

public class ReplicatedTokenRequestSerializer
{
    public static void serialize( ReplicatedTokenRequest content, ByteBuf buffer )
    {
        buffer.writeInt( content.type().ordinal() );
        StringMarshal.marshal( buffer, content.tokenName() );

        buffer.writeInt( content.commandBytes().length );
        buffer.writeBytes( content.commandBytes() );
    }

    public static ReplicatedTokenRequest deserialize( ByteBuf buffer )
    {
        TokenType type = TokenType.values()[buffer.readInt()];
        String tokenName = StringMarshal.unmarshal( buffer );

        int commandBytesLength = buffer.readInt();
        byte[] commandBytes = new  byte[commandBytesLength];
        buffer.readBytes( commandBytes, 0, commandBytesLength );

        return new ReplicatedTokenRequest( type, tokenName, commandBytes );
    }

    public static byte[] createCommandBytes( Collection<Command> commands )
    {
        ByteBuf commandBuffer = Unpooled.buffer();
        NetworkFlushableChannelNetty4 channel = new NetworkFlushableChannelNetty4( commandBuffer );

        try
        {
            new LogEntryWriter( channel ).serialize( commands );
        }
        catch ( IOException e )
        {
            e.printStackTrace(); // TODO: Handle or throw.
        }

        byte[] commandsBytes = commandBuffer.array().clone();
        commandBuffer.release();

        return commandsBytes;
    }

    public static Collection<StorageCommand> extractCommands( byte[] commandBytes )
    {
        ByteBuf txBuffer = Unpooled.wrappedBuffer( commandBytes );
        NetworkReadableClosableChannelNetty4 channel = new NetworkReadableClosableChannelNetty4( txBuffer );

        LogEntryReader<ReadableClosablePositionAwareChannel> reader = new VersionAwareLogEntryReader<>(
                new RecordStorageCommandReaderFactory() );

        LogEntryCommand entryRead;
        List<StorageCommand> commands = new LinkedList<>();

        try
        {
            while ( (entryRead = (LogEntryCommand) reader.readLogEntry( channel )) != null )
            {
                commands.add( entryRead.getXaCommand() );
            }
        }
        catch ( IOException e )
        {
            e.printStackTrace(); // TODO: Handle or throw.
        }

        return commands;
    }
}
