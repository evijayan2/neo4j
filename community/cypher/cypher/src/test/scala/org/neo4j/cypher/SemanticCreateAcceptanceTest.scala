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
package org.neo4j.cypher

import org.junit.Ignore
import org.neo4j.cypher.internal.frontend.v3_0.SemanticDirection
import org.scalacheck.{Shrink, Gen}

/*
 * Tests create on random patterns.
 *  - uses updateWithBothPlanners to verify that the statistics match the rule planner
 *  - makes sure that whatever pattern we create is returned when doing MATCH on pattern.
 */
class SemanticCreateAcceptanceTest extends ExecutionEngineFunSuite with PatternGen with NewPlannerTestSupport {

  //we don't want scala check to shrink patterns here and leave things in the database
  implicit val dontShrink: Shrink[List[Element]] = Shrink(s => Stream.empty)

  test("create and match random patterns") {
    forAll(patterns) { pattern =>
      // reset naming sequence number
      nameSeq.set(0)


      whenever(pattern.nonEmpty) {
        val patternString = pattern.map(_.string).mkString
        withClue(s"failing on pattern $patternString") {
          //update
          updateWithBothPlanners(s"CREATE $patternString")

          //find created pattern (cannot return * since everything might be unnamed)
          val result = executeWithAllPlannersAndRuntimes(s"MATCH $patternString RETURN 42")
          result.toList should have size 1

          //clean up
          updateWithBothPlanners(s"MATCH (n) DETACH DELETE n")
        }
      }
    }
  }

  override protected def numberOfTestRuns: Int = 20

  override def relGen = Gen.oneOf(typedRelGen, namedTypedRelGen, typedWithPropertiesRelGen, namedTypedWithPropertiesRelGen)

  override def nodeGen = Gen.oneOf(emptyNodeGen, namedNodeGen, labeledNodeGen, namedLabeledNodeGen, labeledWithPropertiesNodeGen, namedLabeledWithPropertiesNodeGen)

  override def relDirection = Gen.oneOf(SemanticDirection.INCOMING, SemanticDirection.OUTGOING)
}
