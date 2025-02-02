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
package org.neo4j.cypher.internal.compiler.v3_0.pipes

import org.neo4j.cypher.internal.compiler.v3_0.ExecutionContext
import org.neo4j.cypher.internal.compiler.v3_0.executionplan.Effects
import org.neo4j.cypher.internal.compiler.v3_0.planDescription.InternalPlanDescription.Arguments.CountRelationshipsExpression
import org.neo4j.cypher.internal.compiler.v3_0.planDescription.{NoChildren, PlanDescriptionImpl}
import org.neo4j.cypher.internal.compiler.v3_0.symbols.SymbolTable
import org.neo4j.cypher.internal.frontend.v3_0.NameId
import org.neo4j.cypher.internal.frontend.v3_0.symbols._

case class RelationshipCountFromCountStorePipe(ident: String, startLabel: Option[LazyLabel],
                                                 typeNames: LazyTypes, endLabel: Option[LazyLabel],
                                                 bothDirections: Boolean)
                                                (val estimatedCardinality: Option[Double] = None)
                                                (implicit pipeMonitor: PipeMonitor) extends Pipe with RonjaPipe {

  protected def internalCreateResults(state: QueryState): Iterator[ExecutionContext] = {
    val maybeStartLabelId = getLabelId(startLabel, state)
    val maybeEndLabelId = getLabelId(endLabel, state)

    val count = (maybeStartLabelId, bothDirections, maybeEndLabelId) match {
      case (Some(startLabelId), false, Some(endLabelId)) =>
        countOneDirection(state, typeNames, startLabelId, endLabelId)

      case (Some(startLabelId), true, Some(endLabelId)) =>
        countOneDirection(state, typeNames, startLabelId, endLabelId) +
          countOneDirection(state, typeNames, endLabelId, startLabelId)

      // If any of the specified labels does not exist the count is zero
      case _ =>
        0
    }

    val baseContext = state.initialContext.getOrElse(ExecutionContext.empty)
    Seq(baseContext.newWith1(ident, count)).iterator
  }

  private def getLabelId(lazyLabel: Option[LazyLabel], state: QueryState): Option[Int] = lazyLabel match {
      case Some(label) => label.id(state.query).map(_.id)
      case _ => Some(NameId.WILDCARD)
    }

  private def countOneDirection(state: QueryState, typeNames: LazyTypes, startLabelId: Int, endLabelId: Int) =
    typeNames.types(state.query) match {
      case None => state.query.relationshipCountByCountStore(startLabelId, NameId.WILDCARD, endLabelId)
      case Some(types) => types.foldLeft(0L) { (count, typeId) =>
        count + state.query.relationshipCountByCountStore(startLabelId, typeId, endLabelId)
      }
    }

  def exists(predicate: Pipe => Boolean): Boolean = predicate(this)

  def planDescriptionWithoutCardinality = PlanDescriptionImpl(
    this.id, "RelationshipCountFromCountStore", NoChildren,
    Seq(CountRelationshipsExpression(ident, startLabel, typeNames, endLabel, bothDirections)), variables)

  def symbols = new SymbolTable(Map(ident -> CTInteger))

  override def monitor = pipeMonitor

  override def localEffects: Effects = Effects()

  def dup(sources: List[Pipe]): Pipe = {
    require(sources.isEmpty)
    this
  }

  def sources: Seq[Pipe] = Seq.empty

  def withEstimatedCardinality(estimated: Double) = copy()(Some(estimated))
}
