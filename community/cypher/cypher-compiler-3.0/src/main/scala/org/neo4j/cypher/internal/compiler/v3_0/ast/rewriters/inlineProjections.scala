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

import org.neo4j.cypher.internal.frontend.v3_0.ast._
import org.neo4j.cypher.internal.compiler.v3_0.planner.CantHandleQueryException
import org.neo4j.cypher.internal.frontend.v3_0.helpers.fixedPoint
import org.neo4j.cypher.internal.frontend.v3_0.{replace, Rewriter, TypedRewriter}

case object inlineProjections extends Rewriter {

  def apply(in: AnyRef): AnyRef = instance.apply(in)

  val instance = Rewriter.lift { case input: Statement =>
    val context = inliningContextCreator(input)

    val inlineVariables = TypedRewriter[ASTNode](context.variableRewriter)
    val inlinePatterns = TypedRewriter[Pattern](context.patternRewriter)
    val inlineReturnItemsInWith = Rewriter.lift(aliasedReturnItemRewriter(inlineVariables.narrowed, context, inlineAliases = true))
    val inlineReturnItemsInReturn = Rewriter.lift(aliasedReturnItemRewriter(inlineVariables.narrowed, context, inlineAliases = false))

    val inliningRewriter: Rewriter = replace(replacer => {
      case expr: Expression =>
        replacer.stop(expr)

      case withClause: With if !withClause.distinct =>
        withClause.copy(
          returnItems = withClause.returnItems.rewrite(inlineReturnItemsInWith).asInstanceOf[ReturnItems],
          where = withClause.where.map(inlineVariables.narrowed)
        )(withClause.position)

      case returnClause: Return =>
        returnClause.copy(
          returnItems = returnClause.returnItems.rewrite(inlineReturnItemsInReturn).asInstanceOf[ReturnItems]
        )(returnClause.position)

      case m @ Match(_, mPattern, mHints, mOptWhere) =>
        val newOptWhere = mOptWhere.map(inlineVariables.narrowed)
        val newHints = mHints.map(inlineVariables.narrowed)
        // no need to inline expressions in patterns since all expressions have been moved to WHERE prior to
        // calling inlineProjections
        val newPattern = inlinePatterns(mPattern)
        m.copy(pattern = newPattern, hints = newHints, where = newOptWhere)(m.position)

      case _: UpdateClause  =>
        throw new CantHandleQueryException

      case clause: Clause =>
        inlineVariables.narrowed(clause)

      case astNode =>
        replacer.expand(astNode)
    })

    input.endoRewrite(inliningRewriter)
  }

  private def findAllDependencies(variable: Variable, context: InliningContext): Set[Variable] = {
    val (dependencies, _) = fixedPoint[(Set[Variable], List[Variable])]({
      case (deps, Nil) =>
        (deps, Nil)
      case (deps, queue) =>
        val id :: tail = queue
        context.projections.get(id) match {
          case Some(expr) =>
            val exprDependencies = expr.dependencies
            (deps - id ++ exprDependencies, (exprDependencies -- deps).toList ++ tail)
          case None =>
            (deps + id, queue)
        }
    })((Set(variable), List(variable)))
    dependencies
  }

  private def aliasedReturnItemRewriter(inlineExpressions: Expression => Expression, context: InliningContext,
                                        inlineAliases: Boolean): PartialFunction[AnyRef, AnyRef] = {
    case ri: ReturnItems =>
      val newItems = ri.items.flatMap {
        case item: AliasedReturnItem
          if context.okToRewrite(item.variable) && inlineAliases =>
          val dependencies = findAllDependencies(item.variable, context)
          if (dependencies == Set(item.variable)) {
            Seq(item)
          } else {
            dependencies.map { id =>
              AliasedReturnItem(id.copyId, id.copyId)(item.position)
            }.toSeq
          }
        case item: AliasedReturnItem => Seq(
          item.copy(expression = inlineExpressions(item.expression))(item.position)
        )
      }
      ri.copy(items = newItems)(ri.position)
  }
}
