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
package org.neo4j.server;

import org.neo4j.logging.Log;

import static java.lang.String.format;

/**
 * Thrown during start-up of the server.
 *
 * @deprecated This class is for internal use only and will be moved to an internal package in a future release
 */
@Deprecated
public class ServerStartupException extends RuntimeException
{
    public ServerStartupException( String message, Throwable t )
    {
        super( message, t);
    }

    public ServerStartupException( String message )
    {
        super( message );
    }

    public void describeTo( Log log )
    {
        // By default, log the full error. The intention is that sub classes can override this and
        // specify less extreme logging options.
        log.error( format( "Failed to start Neo4j: %s", getMessage() ), this );
    }
}
