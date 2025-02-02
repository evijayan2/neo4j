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
package org.neo4j.metrics.source;

import java.io.IOException;
import java.util.function.Supplier;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;

import org.neo4j.coreedge.raft.CoreMetaData;
import org.neo4j.kernel.impl.annotations.Documented;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.kernel.monitoring.Monitors;

import static com.codahale.metrics.MetricRegistry.name;

@Documented(".Core Edge Metrics")
public class CoreMetrics extends LifecycleAdapter
{
    private static final String CORE_EDGE_PREFIX = "neo4j.core_edge";

    @Documented("Append index of the RAFT log")
    public static final String APPEND_INDEX = name( CORE_EDGE_PREFIX, "append_index" );
    @Documented("Commit index of the RAFT log")
    public static final String COMMIT_INDEX = name( CORE_EDGE_PREFIX, "commit_index" );
    @Documented("RAFT Term of this server")
    public static final String TERM = name( CORE_EDGE_PREFIX, "term" );
    @Documented("Leader was not found while attempting to commit a transaction")
    public static final String LEADER_NOT_FOUND = name( CORE_EDGE_PREFIX, "leader_not_found" );
    @Documented("TX pull requests received from edge servers")
    public static final String TX_PULL_REQUESTS_RECEIVED = name( CORE_EDGE_PREFIX, "tx_pull_requests_received" );
    @Documented("Transaction retries")
    public static final String TX_RETRIES = name( CORE_EDGE_PREFIX, "tx_retries" );
    @Documented("Is this server the leader?")
    public static final String IS_LEADER = name( CORE_EDGE_PREFIX, "is_leader" );

    private Monitors monitors;
    private MetricRegistry registry;
    private Supplier<CoreMetaData> coreMetaData;

    private final RaftLogCommitIndexMetric raftLogCommitIndexMetric = new RaftLogCommitIndexMetric();
    private final RaftLogAppendIndexMetric raftLogAppendIndexMetric = new RaftLogAppendIndexMetric();
    private final RaftTermMetric raftTermMetric = new RaftTermMetric();
    private final LeaderNotFoundMetric leaderNotFoundMetric = new LeaderNotFoundMetric();
    private final TxPullRequestsMetric txPullRequestsMetric = new TxPullRequestsMetric();
    private final TxRetryMetric txRetryMetric = new TxRetryMetric();

    public CoreMetrics( Monitors monitors, MetricRegistry registry, Supplier<CoreMetaData> coreMetaData )
    {
        this.monitors = monitors;
        this.registry = registry;
        this.coreMetaData = coreMetaData;
    }

    @Override
    public void start() throws Throwable
    {
        monitors.addMonitorListener( raftLogCommitIndexMetric );
        monitors.addMonitorListener( raftLogAppendIndexMetric );
        monitors.addMonitorListener( raftTermMetric );
        monitors.addMonitorListener( leaderNotFoundMetric );
        monitors.addMonitorListener( txPullRequestsMetric );
        monitors.addMonitorListener( txRetryMetric );

        registry.register( COMMIT_INDEX, (Gauge<Long>) raftLogCommitIndexMetric::commitIndex );
        registry.register( APPEND_INDEX, (Gauge<Long>) raftLogAppendIndexMetric::appendIndex );
        registry.register( TERM, (Gauge<Long>) raftTermMetric::term );
        registry.register( LEADER_NOT_FOUND, (Gauge<Long>) leaderNotFoundMetric::leaderNotFoundExceptions );
        registry.register( TX_PULL_REQUESTS_RECEIVED, (Gauge<Long>) txPullRequestsMetric::txPullRequestsReceived );
        registry.register( TX_RETRIES, (Gauge<Long>) txRetryMetric::transactionsRetries );
        registry.register( IS_LEADER, new LeaderGauge() );
    }

    @Override
    public void stop() throws IOException
    {
        registry.remove( COMMIT_INDEX );
        registry.remove( APPEND_INDEX );
        registry.remove( TERM );
        registry.remove( LEADER_NOT_FOUND );
        registry.remove( TX_PULL_REQUESTS_RECEIVED );
        registry.remove( TX_RETRIES );
        registry.remove( IS_LEADER );

        monitors.removeMonitorListener( raftLogCommitIndexMetric );
        monitors.removeMonitorListener( raftLogAppendIndexMetric );
        monitors.removeMonitorListener( raftTermMetric );
        monitors.removeMonitorListener( leaderNotFoundMetric );
        monitors.removeMonitorListener( txPullRequestsMetric );
        monitors.removeMonitorListener( txRetryMetric );
    }

    private class LeaderGauge implements Gauge<Integer>
    {
        @Override
        public Integer getValue()
        {
            return coreMetaData.get().isLeader() ? 1 : 0;
        }
    }
}
