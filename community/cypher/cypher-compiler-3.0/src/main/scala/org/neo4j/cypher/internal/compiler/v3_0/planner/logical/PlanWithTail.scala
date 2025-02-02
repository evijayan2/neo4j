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
package org.neo4j.cypher.internal.compiler.v3_0.planner.logical

import org.neo4j.cypher.internal.compiler.v3_0.planner.{MergePlannerQuery, PlannerQuery}
import org.neo4j.cypher.internal.compiler.v3_0.planner.logical.plans.LogicalPlan
import org.neo4j.cypher.internal.frontend.v3_0.Rewriter

/*
This class ties together disparate query graphs through their event horizons. It does so by using Apply,
which in most cases is then rewritten away by LogicalPlan rewriting.

In cases where the preceding PlannerQuery has updates we must make the Apply an EagerApply if there
are overlaps between the previous update and the reads of any of the tails. We must also make Reads
in the tail into RepeatableReads if there is overlap between the read and update within the PlannerQuery.

For example:

    +Apply2
    |\
    | +Update3
    | |
    | +Read3
    |
    +Apply1
    |\
    | +Update2
    | |
    | +Read2
    |
    +Update1
    |
    +Read1

In this case the following must hold
  - Apply1 is eager if updates from Update1 will be matched by Reads2 or Reads3.
  - Apply2 is eager if updates from Update2 will be matched by Reads3
  - If Update2 affects Read2, Read2 must use RepeatableRead
  - If Update3 affects Read3, Read2 must use RepeatableRead

*/
case class PlanWithTail(expressionRewriterFactory: (LogicalPlanningContext => Rewriter) = ExpressionRewriterFactory,
                        planEventHorizon: LogicalPlanningFunction2[PlannerQuery, LogicalPlan, LogicalPlan] = PlanEventHorizon,
                        planPart: (PlannerQuery, LogicalPlanningContext, Option[LogicalPlan]) => LogicalPlan = planPart,
                        planUpdates: LogicalPlanningFunction2[PlannerQuery, LogicalPlan, LogicalPlan] = PlanUpdates)
  extends LogicalPlanningFunction2[LogicalPlan, Option[PlannerQuery], LogicalPlan] {

  override def apply(lhs: LogicalPlan, remaining: Option[PlannerQuery])(implicit context: LogicalPlanningContext): LogicalPlan = {
    remaining match {
      case Some(plannerQuery) =>
        val lhsContext = context.recurse(lhs)
        val partPlan = planPart(plannerQuery, lhsContext, Some(context.logicalPlanProducer.planQueryArgumentRow(plannerQuery.queryGraph)))
        ///use eager if configured to do so
        val alwaysEager = context.config.updateStrategy.alwaysEager
        //If reads interfere with writes, make it a RepeatableRead
        var shouldPlanEagerBeforeTail = false
        val planWithEffects =
          if (!plannerQuery.isInstanceOf[MergePlannerQuery] &&
            (alwaysEager || Eagerness.conflictInTail(plannerQuery, plannerQuery)))
            context.logicalPlanProducer.planEager(partPlan)
          else if (plannerQuery.isInstanceOf[MergePlannerQuery] &&
            (alwaysEager ||
              (plannerQuery.tail.isDefined &&
                Eagerness.conflictInTail(plannerQuery, plannerQuery.tail.get)))) {
            // For a MergePlannerQuery the merge have to be able to read its own writes,
            // so we need to plan an eager between this and its tail instead
            shouldPlanEagerBeforeTail = true
            partPlan
          }
          else partPlan

        val planWithUpdates = planUpdates(plannerQuery, planWithEffects)(context)

        //If previous update interferes with any of the reads here or in tail, add Eager
        val applyPlan = {
          val lastPlannerQuery = lhs.solved.last
          val b = plannerQuery.allQueryGraphs.exists(lastPlannerQuery.queryGraph.deleteOverlapWithMergeIn)
          val c = !lastPlannerQuery.queryGraph.writeOnly
          val d = plannerQuery.allQueryGraphs.exists(lastPlannerQuery.queryGraph.overlaps)
          val newLhs =
            if (alwaysEager || b || (c && d))
              context.logicalPlanProducer.planEager(lhs)
            else
              lhs

          context.logicalPlanProducer.planTailApply(newLhs, planWithUpdates)
        }

        val eagerPlan =
          if (shouldPlanEagerBeforeTail)
            context.logicalPlanProducer.planEager(applyPlan)
          else applyPlan

        val applyContext = lhsContext.recurse(eagerPlan)
        val projectedPlan = planEventHorizon(plannerQuery, eagerPlan)(applyContext)
        val projectedContext = applyContext.recurse(projectedPlan)

        val completePlan = {
          val expressionRewriter = expressionRewriterFactory(projectedContext)

          projectedPlan.endoRewrite(expressionRewriter)
        }

        val superCompletePlan = completePlan.endoRewrite(Eagerness.unnestEager)

        this.apply(superCompletePlan, plannerQuery.tail)(projectedContext)

      case None =>
        lhs
    }
  }
}
