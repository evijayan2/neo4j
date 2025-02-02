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
package org.neo4j.cypher.internal.compiler.v3_0.ast.rewriters

import org.neo4j.cypher.internal.compiler.v3_0.helpers.AggregationNameGenerator
import org.neo4j.cypher.internal.frontend.v3_0.ast._
import org.neo4j.cypher.internal.frontend.v3_0.helpers.fixedPoint
import org.neo4j.cypher.internal.frontend.v3_0.{Rewriter, replace, topDown}

/**
 * This rewriter makes sure that aggregations are on their own in RETURN/WITH clauses, so
 * the planner can have an easy time
 *
 * Example:
 *
 * MATCH (n)
 * RETURN { name: n.name, count: count(*) }, n.foo
 *
 * This query has a RETURN clause where the single expression contains both the aggregate key and
 * the aggregation expression. To make the job easier on the planner, this rewrite will change the query to:
 *
 * MATCH (n)
 * WITH n.name AS x1, count(*) AS x2, n.foo as X3
 * RETURN { name: x1, count: x2 }
 */
case object isolateAggregation extends Rewriter {
  def apply(that: AnyRef): AnyRef = instance(that)

  private val instance = replace(replacer => {

    case expr: Expression =>
      replacer.stop(expr)

    case q@SingleQuery(clauses) =>

      val newClauses = clauses.flatMap {
        case clause if !clauseNeedingWork(clause) => Some(clause)
        case clause =>
          val originalExpressions = getExpressions(clause)

          val expressionsToGoToWith: Set[Expression] = fixedPoint {
            (expressions: Set[Expression]) => expressions.flatMap {
              case e if hasAggregateButIsNotAggregate(e) =>
                e match {
                  case ReduceExpression(_, init, coll) => Seq(init, coll)
                  case FilterExpression(_, expr)       => Some(expr)
                  case ExtractExpression(_, expr)      => Some(expr)
                  case ListComprehension(_, expr)      => Some(expr)
                  case _                               => e.arguments
                }

              case e =>
                Some(e)

            }
          }(originalExpressions).filter {
            //Constant expressions should never be isolated
            case ConstantExpression(_) => false
            case expr => true
          }

          val withReturnItems: Set[ReturnItem] = expressionsToGoToWith.map {
            case id: Variable => AliasedReturnItem(id.copyId, id.copyId)(id.position)
            case e              => AliasedReturnItem(e, Variable(AggregationNameGenerator.name(e.position))(e.position))(e.position)
          }
          val pos = clause.position
          val withClause = With(distinct = false, ReturnItems(includeExisting = false, withReturnItems.toSeq)(pos), None, None, None, None)(pos)

          val resultClause = clause.endoRewrite(topDown(Rewriter.lift {
            case e: Expression =>
              withReturnItems.collectFirst {
                case AliasedReturnItem(expression, variable) if e == expression => variable.copyId
              }.getOrElse(e)
          }))

          Seq(withClause, resultClause)
      }

      q.copy(clauses = newClauses)(q.position)

    case astNode =>
      replacer.expand(astNode)
  })

  private def getExpressions(c: Clause): Set[Expression] = c match {
    case clause: Return => clause.returnItems.items.map(_.expression).toSet
    case clause: With => clause.returnItems.items.map(_.expression).toSet
    case _ => Set.empty
  }

  private def clauseNeedingWork(c: Clause): Boolean = c.exists {
    case e: Expression => hasAggregateButIsNotAggregate(e)
  }
}
