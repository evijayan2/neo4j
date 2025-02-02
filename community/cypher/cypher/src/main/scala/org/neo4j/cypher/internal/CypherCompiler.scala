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
package org.neo4j.cypher.internal

import org.neo4j.cypher.internal.compiler.v3_0._
import org.neo4j.cypher.internal.frontend.v3_0.InputPosition
import org.neo4j.cypher.{InvalidArgumentException, SyntaxException, _}
import org.neo4j.graphdb.GraphDatabaseService
import org.neo4j.graphdb.factory.GraphDatabaseSettings
import org.neo4j.helpers.Clock
import org.neo4j.kernel.api.KernelAPI
import org.neo4j.kernel.impl.factory.GraphDatabaseFacade
import org.neo4j.kernel.monitoring.{Monitors => KernelMonitors}
import org.neo4j.logging.{Log, LogProvider}

object CypherCompiler {
  val DEFAULT_QUERY_CACHE_SIZE: Int = 128
  val DEFAULT_QUERY_PLAN_TTL: Long = 1000 // 1 second
  val CLOCK = Clock.SYSTEM_CLOCK
  val DEFAULT_STATISTICS_DIVERGENCE_THRESHOLD = 0.5
  val DEFAULT_NON_INDEXED_LABEL_WARNING_THRESHOLD = 10000
}

case class PreParsedQuery(statement: String, rawStatement: String, version: CypherVersion,
                          executionMode: CypherExecutionMode, planner: CypherPlanner, runtime: CypherRuntime,
                          updateStrategy: CypherUpdateStrategy,
                          notificationLogger: InternalNotificationLogger)
                         (val offset: InputPosition) {
  val statementWithVersionAndPlanner = {
    val plannerInfo = planner match {
      case CypherPlanner.default => ""
      case _ => s"planner=${planner.name}"
    }
    val runtimeInfo = runtime match {
      case CypherRuntime.default => ""
      case _ => s"runtime=${runtime.name}"
    }
    val updateStrategyInfo = updateStrategy match {
      case CypherUpdateStrategy.default => ""
      case _ => s"strategy=${updateStrategy.name}"
    }

    s"CYPHER ${version.name} $plannerInfo $runtimeInfo $updateStrategyInfo $statement".replaceAll("\\s+", " ")
  }
}

class CypherCompiler(graph: GraphDatabaseService,
                     kernelAPI: KernelAPI,
                     kernelMonitors: KernelMonitors,
                     configuredVersion: CypherVersion,
                     configuredPlanner: CypherPlanner,
                     configuredRuntime: CypherRuntime,
                     useErrorsOverWarnings: Boolean,
                     logProvider: LogProvider) {
  import org.neo4j.cypher.internal.CypherCompiler._

  private val log: Log = logProvider.getLog(getClass)

  private val config = CypherCompilerConfiguration(
    queryCacheSize = getQueryCacheSize,
    statsDivergenceThreshold = getStatisticsDivergenceThreshold,
    queryPlanTTL = getMinimumTimeBeforeReplanning,
    useErrorsOverWarnings = useErrorsOverWarnings,
    nonIndexedLabelWarningThreshold = getNonIndexedLabelWarningThreshold
  )

  private val factory = new PlannerFactory(graph, kernelAPI, kernelMonitors, log, config)
  private val planners: PlannerCache = new PlannerCache(factory)


  private final val ILLEGAL_PLANNER_RUNTIME_COMBINATIONS: Set[(CypherPlanner, CypherRuntime)] = Set((CypherPlanner.rule, CypherRuntime.compiled))

  @throws(classOf[SyntaxException])
  def preParseQuery(queryText: String): PreParsedQuery = {
    val logger = new RecordingNotificationLogger
    val preParsedStatement = CypherPreParser(queryText)
    val CypherStatementWithOptions(statement, offset, version, planner, runtime, updateStrategy, mode) =
      CypherStatementWithOptions(preParsedStatement)

    val cypherVersion = version.getOrElse(configuredVersion)
    val pickedExecutionMode = mode.getOrElse(CypherExecutionMode.default)

    val pickedPlanner = pick(planner, CypherPlanner, if (cypherVersion == configuredVersion) Some(configuredPlanner) else None)
    val pickedRuntime = pick(runtime, CypherRuntime, if (cypherVersion == configuredVersion) Some(configuredRuntime) else None)
    val pickedUpdateStrategy = pick(updateStrategy, CypherUpdateStrategy, None)

    assertValidOptions(CypherStatementWithOptions(preParsedStatement), cypherVersion, pickedExecutionMode, pickedPlanner, pickedRuntime)

    PreParsedQuery(statement, queryText, cypherVersion, pickedExecutionMode,
      pickedPlanner, pickedRuntime, pickedUpdateStrategy, logger)(offset)
  }

  private def pick[O <: CypherOption](candidate: Option[O], companion: CypherOptionCompanion[O], configured: Option[O]): O = {
    val specified = candidate.getOrElse(companion.default)
    if (specified == companion.default) configured.getOrElse(specified) else specified
  }

  private def assertValidOptions(statementWithOption: CypherStatementWithOptions,
                                 cypherVersion: CypherVersion, executionMode: CypherExecutionMode,
                                 planner: CypherPlanner, runtime: CypherRuntime) {
    if (ILLEGAL_PLANNER_RUNTIME_COMBINATIONS((planner, runtime)))
      throw new InvalidArgumentException(s"Unsupported PLANNER - RUNTIME combination: ${planner.name} - ${runtime.name}")
  }

  @throws(classOf[SyntaxException])
  def parseQuery(preParsedQuery: PreParsedQuery, tracer: CompilationPhaseTracer): ParsedQuery = {
    import helpers.wrappersFor2_3._

    val planner = preParsedQuery.planner
    val runtime = preParsedQuery.runtime
    val updateStrategy = preParsedQuery.updateStrategy
    preParsedQuery.version match {
      case CypherVersion.v3_0 => planners(PlannerSpec_v3_0(planner, runtime, updateStrategy)).produceParsedQuery(preParsedQuery, tracer)
      case CypherVersion.v2_3 => planners(PlannerSpec_v2_3(planner, runtime)).produceParsedQuery(preParsedQuery, as2_3(tracer))
    }
  }

  private def getQueryCacheSize : Int =
    optGraphAs[GraphDatabaseFacade]
      .andThen(_.platformModule.config.get(GraphDatabaseSettings.query_cache_size).intValue())
      .applyOrElse(graph, (_: GraphDatabaseService) => DEFAULT_QUERY_CACHE_SIZE)


  private def getStatisticsDivergenceThreshold : Double =
    optGraphAs[GraphDatabaseFacade]
      .andThen(_.platformModule.config.get(GraphDatabaseSettings.query_statistics_divergence_threshold).doubleValue())
      .applyOrElse(graph, (_: GraphDatabaseService) => DEFAULT_STATISTICS_DIVERGENCE_THRESHOLD)

  private def getNonIndexedLabelWarningThreshold: Long =
    optGraphAs[GraphDatabaseFacade]
      .andThen(_.platformModule.config.get(GraphDatabaseSettings.query_non_indexed_label_warning_threshold).longValue())
      .applyOrElse(graph, (_: GraphDatabaseService) => DEFAULT_NON_INDEXED_LABEL_WARNING_THRESHOLD)

  private def getMinimumTimeBeforeReplanning: Long = {
    optGraphAs[GraphDatabaseFacade]
      .andThen(_.platformModule.config.get(GraphDatabaseSettings.cypher_min_replan_interval).longValue())
      .applyOrElse(graph, (_: GraphDatabaseService) => DEFAULT_QUERY_PLAN_TTL)
  }

  private def optGraphAs[T <: GraphDatabaseService : Manifest]: PartialFunction[GraphDatabaseService, T] = {
    case (db: T) => db
  }
}
