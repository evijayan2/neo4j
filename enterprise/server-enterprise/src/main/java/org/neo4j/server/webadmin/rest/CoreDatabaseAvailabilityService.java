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
package org.neo4j.server.webadmin.rest;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.neo4j.coreedge.server.core.CoreGraphDatabase;
import org.neo4j.coreedge.raft.roles.Role;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.server.rest.management.AdvertisableService;
import org.neo4j.server.rest.repr.BadInputException;
import org.neo4j.server.rest.repr.OutputFormat;

import static javax.ws.rs.core.MediaType.TEXT_PLAIN_TYPE;
import static javax.ws.rs.core.Response.Status.FORBIDDEN;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static javax.ws.rs.core.Response.Status.OK;
import static javax.ws.rs.core.Response.status;

@Path(CoreDatabaseAvailabilityService.BASE_PATH)
public class CoreDatabaseAvailabilityService implements AdvertisableService
{
    public static final String BASE_PATH = "server/core";
    public static final String IS_WRITABLE_PATH = "/writable";
    public static final String IS_AVAILABLE_PATH = "/available";

    private final OutputFormat output;
    private final CoreGraphDatabase coreDatabase;

    public CoreDatabaseAvailabilityService( @Context OutputFormat output, @Context GraphDatabaseService db )
    {
        this.output = output;
        if ( db instanceof CoreGraphDatabase )
        {
            this.coreDatabase = (CoreGraphDatabase) db;
        }
        else
        {
            this.coreDatabase = null;
        }
    }

    @GET
    public Response discover() throws BadInputException
    {
        if ( coreDatabase == null )
        {
            return status( FORBIDDEN ).build();
        }

        String isSlaveUri = IS_WRITABLE_PATH;

        return output.ok( new CoreDatabaseAvailabilityDiscoveryRepresentation( BASE_PATH, isSlaveUri ) );
    }

    @GET
    @Path(IS_WRITABLE_PATH)
    public Response isWritable() throws BadInputException
    {
        if ( coreDatabase == null )
        {
            return status( FORBIDDEN ).build();
        }

        if ( coreDatabase.getRole() == Role.LEADER )
        {
            return positiveResponse();
        }

        return negativeResponse();
    }

    @GET
    @Path( IS_AVAILABLE_PATH )
    public Response isAvailable()
    {
        if ( coreDatabase == null )
        {
            return status( FORBIDDEN ).build();
        }

        return positiveResponse();
    }

    private Response negativeResponse()
    {
        return plainTextResponse( NOT_FOUND, "false" );
    }

    private Response positiveResponse()
    {
        return plainTextResponse( OK, "true" );
    }

    private Response plainTextResponse( Response.Status status, String entityBody )
    {
        return status( status ).type( TEXT_PLAIN_TYPE ).entity( entityBody ).build();
    }

    @Override
    public String getName()
    {
        return "core";
    }

    @Override
    public String getServerPath()
    {
        return BASE_PATH;
    }
}
