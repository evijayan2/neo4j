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
package org.neo4j.cypher.internal.compiler.v3_0.executionplan.procs

import java.io.PrintWriter
import java.util

import org.neo4j.cypher.internal.compiler.v3_0.executionplan.{AcceptingExecutionResult, InternalQueryType}
import org.neo4j.cypher.internal.compiler.v3_0.planDescription.InternalPlanDescription
import org.neo4j.cypher.internal.compiler.v3_0.spi.{InternalResultVisitor, QueryContext}
import org.neo4j.cypher.internal.compiler.v3_0.{ExecutionMode, InternalQueryStatistics}

/**
  * Empty result, as produced by a pure side-effect.
  */
case class PureSideEffectInternalExecutionResult(executionPlanDescription: InternalPlanDescription, ctx: QueryContext, executionType: InternalQueryType, executionMode: ExecutionMode)
  extends AcceptingExecutionResult(ctx) {

  override def javaColumns: util.List[String] = java.util.Collections.emptyList()

  override def accept[EX <: Exception](visitor: InternalResultVisitor[EX]) = {
    ctx.close(success = true)
  }

  override def queryStatistics() = ctx.getOptStatistics.getOrElse(InternalQueryStatistics())

  override def toList = List.empty

  override def dumpToString(writer: PrintWriter) = {
    writer.println("+-------------------+")
    writer.println("| No data returned. |")
    writer.println("+-------------------+")
    writer.print(queryStatistics().toString)
  }
}


