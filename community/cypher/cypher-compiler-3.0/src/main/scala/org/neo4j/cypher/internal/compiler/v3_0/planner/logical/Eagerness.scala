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

import org.neo4j.cypher.internal.compiler.v3_0.planner.logical.plans._
import org.neo4j.cypher.internal.compiler.v3_0.planner.{PlannerQuery, QueryGraph}
import org.neo4j.cypher.internal.frontend.v3_0.helpers.fixedPoint
import org.neo4j.cypher.internal.frontend.v3_0.{bottomUp, Rewriter, SemanticDirection}

import scala.annotation.tailrec

object Eagerness {

  /**
   * Determines whether there is a conflict between the so-far planned LogicalPlan
   * and the remaining parts of the PlannerQuery. This function assumes that the
   * argument PlannerQuery is the very head of the PlannerQuery chain.
   */
  def conflictInHead(plan: LogicalPlan, plannerQuery: PlannerQuery): Boolean = {
    // The first leaf node is always reading through a stable iterator.
    // We will only consider this analysis for all other node iterators.
    val unstableLeaves = plan.leaves.collect {
      case n: NodeLogicalLeafPlan => n.idName
    }

    if (unstableLeaves.isEmpty)
      false // the query did not start with a read, possibly CREATE () ...
    else
      // Start recursion by checking the given plannerQuery against itself
      headConflicts(plannerQuery, plannerQuery, unstableLeaves.tail)
  }

  @tailrec
  private def headConflicts(head: PlannerQuery, tail: PlannerQuery, unstableLeaves: Seq[IdName]): Boolean = {
    val conflict = if (tail.queryGraph.readOnly) false
    else {
      //if we have unsafe rels we need to check relation overlap and delete
      //overlap immediately
      (hasUnsafeRelationships(head.queryGraph) &&
        (tail.queryGraph.relationshipOverlap(head.queryGraph) ||
          tail.queryGraph.deleteOverlap(head.queryGraph) ||
          tail.queryGraph.setPropertyOverlap(head.queryGraph))
        ) ||
        //otherwise only do checks if we have more that one leaf
        unstableLeaves.exists(
          nodeOverlap(_, head.queryGraph, tail) ||
            tail.queryGraph.relationshipOverlap(head.queryGraph) ||
            tail.queryGraph.setLabelOverlap(head.queryGraph) || // TODO:H Verify. Pontus did this a bit differently
            tail.queryGraph.setPropertyOverlap(head.queryGraph) ||
            tail.queryGraph.deleteOverlap(head.queryGraph))
    }
    if (conflict)
      true
    else if (tail.tail.isEmpty)
      false
    else
      headConflicts(head, tail.tail.get, unstableLeaves)
  }


  /**
    * Determines whether there is a conflict between the two PlannerQuery objects.
    * This function assumes that none of the argument PlannerQuery objects is
    * the head of the PlannerQuery chain.
    */
  @tailrec
  def conflictInTail(head: PlannerQuery, tail: PlannerQuery): Boolean = {
    val conflict = if (tail.queryGraph.readOnly) false
    else tail.queryGraph overlaps head.queryGraph
    if (conflict)
      true
    else if (tail.tail.isEmpty)
      false
    else
      conflictInTail(head, tail.tail.get)
  }

  /*
   * Check if the labels or properties of the node with the provided IdName overlaps
   * with the labels or properties updated in this query. This may cause the read to affected
   * by the writes.
   */
  private def nodeOverlap(currentNode: IdName, headQueryGraph: QueryGraph, tail: PlannerQuery): Boolean = {
    val labelsOnCurrentNode = headQueryGraph.allKnownLabelsOnNode(currentNode).toSet
    val propertiesOnCurrentNode = headQueryGraph.allKnownPropertiesOnIdentifier(currentNode).map(_.propertyKey)
    val labelsToCreate = tail.queryGraph.createLabels
    val propertiesToCreate = tail.queryGraph.createNodeProperties
    val labelsToRemove = tail.queryGraph.labelsToRemoveFromOtherNodes(currentNode)

    tail.queryGraph.updatesNodes &&
      (labelsOnCurrentNode.isEmpty && propertiesOnCurrentNode.isEmpty && tail.exists(_.queryGraph.createNodePatterns.nonEmpty) || //MATCH () CREATE (...)?
        (labelsOnCurrentNode intersect labelsToCreate).nonEmpty || //MATCH (:A) CREATE (:A)?
        propertiesOnCurrentNode.exists(propertiesToCreate.overlaps) || //MATCH ({prop:42}) CREATE ({prop:...})

        //MATCH (n:A), (m:B) REMOVE n:B
        //MATCH (n:A), (m:A) REMOVE m:A
        (labelsToRemove intersect labelsOnCurrentNode).nonEmpty
        )
  }

  /*
   * Unsafe relationships are what may cause unstable
   * iterators when expanding. The unsafe cases are:
   * - (a)-[r]-(b) (undirected)
   * - (a)-[r1]->(b)-[r2]->(c) (multi step)
   * - (a)-[r*]->(b) (variable length)
   */
  private def hasUnsafeRelationships(queryGraph: QueryGraph): Boolean = {
    val allPatterns = queryGraph.allPatternRelationships
    allPatterns.size > 1 || allPatterns.exists(r => r.dir == SemanticDirection.BOTH || !r.length.isSimple)
  }

  case object unnestEager extends Rewriter {

    /*
    Based on unnestApply (which references a paper)

    This rewriter does _not_ adhere to the contract of moving from a valid
    plan to a valid plan, but it is crucial to get eager plans placed correctly.

    Glossary:
      Ax : Apply
      L,R: Arbitrary operator, named Left and Right
      SR : SingleRow - operator that produces single row with no columns
      CN : CreateNode
      Dn : Delete node
      Dr : Delete relationship
      E : Eager
      Sp : SetProperty
      Sm : SetPropertiesFromMap
      Sl : SetLabels
      U : Unwind
     */

    private val instance: Rewriter = Rewriter.lift {

      // L Ax (E R) => E Ax (L R)
      case apply@Apply(lhs, eager@Eager(inner)) =>
        eager.copy(inner = Apply(lhs, inner)(apply.solved))(apply.solved)

      // L Ax (CN R) => CN Ax (L R)
      case apply@Apply(lhs, create@CreateNode(rhs, name, labels, props)) =>
        create.copy(source = Apply(lhs, rhs)(apply.solved), name, labels, props)(apply.solved)

      // L Ax (CR R) => CR Ax (L R)
      case apply@Apply(lhs, create@CreateRelationship(rhs, _, _, _, _, _)) =>
        create.copy(source = Apply(lhs, rhs)(apply.solved))(apply.solved)

      // L Ax (Dn R) => Dn Ax (L R)
      case apply@Apply(lhs, delete@DeleteNode(rhs, expr)) =>
        delete.copy(source = Apply(lhs, rhs)(apply.solved), expr)(apply.solved)

      // L Ax (Dr R) => Dr Ax (L R)
      case apply@Apply(lhs, delete@DeleteRelationship(rhs, expr)) =>
        delete.copy(source = Apply(lhs, rhs)(apply.solved), expr)(apply.solved)

      // L Ax (Sp R) => Sp Ax (L R)
      case apply@Apply(lhs, set@SetNodeProperty(rhs, idName, key, value)) =>
        set.copy(source = Apply(lhs, rhs)(apply.solved), idName, key, value)(apply.solved)

      // L Ax (Sm R) => Sm Ax (L R)
      case apply@Apply(lhs, set@SetNodePropertiesFromMap(rhs, idName, expr, removes)) =>
        set.copy(source = Apply(lhs, rhs)(apply.solved), idName, expr, removes)(apply.solved)

      // L Ax (Sl R) => Sl Ax (L R)
      case apply@Apply(lhs, set@SetLabels(rhs, idName, labelNames)) =>
        set.copy(source = Apply(lhs, rhs)(apply.solved), idName, labelNames)(apply.solved)

      // L Ax (Rl R) => Rl Ax (L R)
      case apply@Apply(lhs, remove@RemoveLabels(rhs, idName, labelNames)) =>
        remove.copy(source = Apply(lhs, rhs)(apply.solved), idName, labelNames)(apply.solved)

    }

    override def apply(input: AnyRef) = fixedPoint(bottomUp(instance)).apply(input)
  }
}
