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
package org.neo4j.server.rest.web;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import javax.ws.rs.core.Response;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.helpers.FakeClock;
import org.neo4j.helpers.collection.MapUtil;
import org.neo4j.kernel.GraphDatabaseAPI;
import org.neo4j.server.database.Database;
import org.neo4j.server.database.WrappedDatabase;
import org.neo4j.server.rest.domain.GraphDbHelper;
import org.neo4j.server.rest.domain.TraverserReturnType;
import org.neo4j.server.rest.paging.LeaseManager;
import org.neo4j.server.rest.repr.formats.JsonFormat;
import org.neo4j.test.TestGraphDatabaseFactory;
import org.neo4j.test.server.EntityOutputFormat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

public class RestfulGraphDatabasePagedTraversalTest
{

    private static final String BASE_URI = "http://neo4j.org:7474/";
    private RestfulGraphDatabase service;
    private Database database;
    private EntityOutputFormat output;
    private GraphDbHelper helper;
    private LeaseManager leaseManager;
    private GraphDatabaseAPI graph;

    @Before
    public void startDatabase() throws IOException
    {
        graph = (GraphDatabaseAPI)new TestGraphDatabaseFactory().newImpermanentDatabase();
        database = new WrappedDatabase(graph);
        helper = new GraphDbHelper( database );
        output = new EntityOutputFormat( new JsonFormat(), URI.create( BASE_URI ), null );
        leaseManager = new LeaseManager( new FakeClock() );
        service = new RestfulGraphDatabase( new JsonFormat(), output,
                new DatabaseActions( leaseManager, true, database.getGraph() ), null );
        service = new TransactionWrappingRestfulGraphDatabase( graph, service );
    }

    @After
    public void shutdownDatabase() throws Throwable
    {
        this.graph.shutdown();
    }

    @Test
    public void shouldLodgeAPagingTraverserAndTraverseTheFirstPageBeforeRespondingWith201()
    {
        Response response = createAPagedTraverser();
        assertEquals( 201, response.getStatus() );
        String responseUri = response.getMetadata()
                .get( "Location" )
                .get( 0 )
                .toString();
        assertThat( responseUri, containsString( "/node/0/paged/traverse/node/" ) );
        assertNotNull( response.getEntity() );
        assertThat( new String( (byte[]) response.getEntity() ), containsString( "\"name\" : \"19\"" ) );
    }

    @Test
    public void givenAPageTraversalHasBeenCreatedShouldYieldNextPageAndRespondWith200() throws Exception
    {
        Response response = createAPagedTraverser();

        String traverserId = parseTraverserIdFromLocationUri( response );

        response = service.pagedTraverse( traverserId, TraverserReturnType.node );

        assertEquals( 200, response.getStatus() );
        assertNotNull( response.getEntity() );
        assertThat( new String( (byte[]) response.getEntity() ), not( containsString( "\"name\" : \"19\"" ) ) );
        assertThat( new String( (byte[]) response.getEntity() ), containsString( "\"name\" : \"91\"" ) );
    }

    @Test
    public void shouldRespondWith404WhenNoSuchTraversalRegistered()
    {
        Response response = service.pagedTraverse( "anUnlikelyTraverserId", TraverserReturnType.node );
        assertEquals( 404, response.getStatus() );
    }

    @Test
    public void shouldRespondWith404WhenTraversalHasExpired()
    {
        Response response = createAPagedTraverser();
        ((FakeClock) leaseManager.getClock()).forward( 2, TimeUnit.MINUTES);

        String traverserId = parseTraverserIdFromLocationUri( response );

        response = service.pagedTraverse( traverserId, TraverserReturnType.node );

        assertEquals( 404, response.getStatus() );
    }

    @Test
    public void shouldRenewLeaseAtEachTraversal()
    {
        Response response = createAPagedTraverser();

        String traverserId = parseTraverserIdFromLocationUri( response );

        ((FakeClock) leaseManager.getClock()).forward( 30, TimeUnit.SECONDS );
        response = service.pagedTraverse( traverserId, TraverserReturnType.node );
        assertEquals( 200, response.getStatus() );

        ((FakeClock) leaseManager.getClock()).forward( 30, TimeUnit.SECONDS );
        response = service.pagedTraverse( traverserId, TraverserReturnType.node );
        assertEquals( 200, response.getStatus() );

        ((FakeClock) leaseManager.getClock()).forward( enoughMinutesToExpireTheLease(), TimeUnit.MINUTES );
        response = service.pagedTraverse( traverserId, TraverserReturnType.node );
        assertEquals( 404, response.getStatus() );
    }

    private int enoughMinutesToExpireTheLease()
    {
        return 10;
    }

    private Response createAPagedTraverser()
    {
        long startNodeId = createListOfNodes( 1000 );
        String description = "{"
                + "\"prune_evaluator\":{\"language\":\"builtin\",\"name\":\"none\"},"
                + "\"return_filter\":{\"language\":\"javascript\",\"body\":\"position.endNode().getProperty('name')" +
                ".contains('9');\"},"
                + "\"order\":\"depth first\","
                + "\"relationships\":{\"type\":\"PRECEDES\",\"direction\":\"out\"}" + "}";

        final int SIXTY_SECONDS = 60;
        final int PAGE_SIZE = 10;

        return service.createPagedTraverser( startNodeId, TraverserReturnType.node, PAGE_SIZE,
                SIXTY_SECONDS, description );
    }

    private long createListOfNodes( int numberOfNodes )
    {
        try ( Transaction tx = database.getGraph().beginTx() )
        {
            long zerothNode = helper.createNode( MapUtil.map( "name", String.valueOf( 0 ) ) );
            long previousNodeId = zerothNode;
            for ( int i = 1; i < numberOfNodes; i++ )
            {
                long currentNodeId = helper.createNode( MapUtil.map( "name", String.valueOf( i ) ) );
                database.getGraph().getNodeById( previousNodeId )
                        .createRelationshipTo( database.getGraph().getNodeById( currentNodeId ),
                                RelationshipType.withName( "PRECEDES" ) );
                previousNodeId = currentNodeId;
            }

            tx.success();
            return zerothNode;
        }
    }

    private String parseTraverserIdFromLocationUri( Response response )
    {
        String locationUri = response.getMetadata()
                .get( "Location" )
                .get( 0 )
                .toString();

        return locationUri.substring( locationUri.lastIndexOf( "/" ) + 1 );
    }
}
