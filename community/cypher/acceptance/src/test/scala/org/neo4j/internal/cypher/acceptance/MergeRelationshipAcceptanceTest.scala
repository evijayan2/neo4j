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
package org.neo4j.internal.cypher.acceptance

import org.neo4j.cypher.{NewPlannerTestSupport, CypherExecutionException, ExecutionEngineFunSuite, QueryStatisticsTestSupport, SyntaxException}
import org.neo4j.graphdb.{Path, Relationship}

class MergeRelationshipAcceptanceTest extends ExecutionEngineFunSuite with QueryStatisticsTestSupport with NewPlannerTestSupport {

  test("should be able to create relationship") {
    // given
    val a = createNode("A")
    val b = createNode("B")

    // when
    val result = updateWithBothPlanners("MATCH (a {name:'A'}), (b {name:'B'}) MERGE (a)-[r:TYPE]->(b) RETURN count(*)")

    // then
    assertStats(result, relationshipsCreated = 1)
    result.toList should equal(List(Map("count(*)" -> 1)))
    executeWithAllPlanners("MATCH (a {name:'A'})-[:TYPE]->(b {name:'B'}) RETURN a.name").toList should have size 1
  }

  test("should be able to find a relationship") {
    // given
    val a = createNode("A")
    val b = createNode("B")
    relate(a, b, "TYPE")

    executeWithAllPlanners("MATCH (a {name:'A'})-[r:TYPE]->(b {name:'B'}) RETURN *")
    // when
    val result = updateWithBothPlanners("MATCH (a {name:'A'}), (b {name:'B'}) MERGE (a)-[r:TYPE]->(b) RETURN count(r)")

    // then
   assertStats(result, relationshipsCreated = 0)
    result.toList should equal(List(Map("count(r)" -> 1)))
  }

  test("should be able to find two existing relationships") {
    // given
    val a = createNode("A")
    val b = createNode("B")
    relate(a, b, "TYPE")
    relate(a, b, "TYPE")

    // when
    val result = updateWithBothPlanners("MATCH (a {name:'A'}), (b {name:'B'}) MERGE (a)-[r:TYPE]->(b) RETURN count(r)")

    // then
    assertStats(result, relationshipsCreated = 0)
    result.toList should equal(List(Map("count(r)" -> 2)))
  }

  test("should be able to filter out relationships") {
    // given
    val a = createNode("A")
    val b = createNode("B")
    relate(a, b, "TYPE", "r1")
    val r = relate(a, b, "TYPE", "r2")

    // when
    val result = updateWithBothPlanners("MATCH (a {name:'A'}), (b {name:'B'}) MERGE (a)-[r:TYPE {name:'r2'}]->(b) RETURN id(r)")

    // then
    assertStats(result, relationshipsCreated = 0)
    result.toList should equal(List(Map("id(r)" -> r.getId)))
  }

  test("should be able to create when nothing matches") {
    // given
    val a = createNode("A")
    val b = createNode("B")
    relate(a, b, "TYPE", "r1")

    // when
    val result = updateWithBothPlanners("MATCH (a {name:'A'}), (b {name:'B'}) MERGE (a)-[r:TYPE {name:'r2'}]->(b) RETURN count(r)")

    // then
    assertStats(result, relationshipsCreated = 1, propertiesWritten = 1)
    result.toList should equal(List(Map("count(r)" -> 1)))
    executeWithAllPlanners("MATCH (a {name:'A'})-[r:TYPE {name:'r2'}]->(b {name:'B'}) RETURN a.name, b.name, r.name")
      .toList should equal(
      List(Map("a.name" -> "A", "b.name" -> "B", "r.name" -> "r2"))
    )
  }

  test("should not be fooled by direction") {
    // given
    val a = createNode("A")
    val b = createNode("B")
    val r = relate(b, a, "TYPE")
    relate(a, b, "TYPE")

    // when
    val result = updateWithBothPlanners("MATCH (a {name:'A'}), (b {name:'B'}) MERGE (a)<-[r:TYPE]-(b) RETURN id(r)")

    // then
    assertStats(result, relationshipsCreated = 0)
    result.toList should equal(List(Map("id(r)" -> r.getId)))
  }

  test("should create relationship with property") {
    // given
    val a = createNode("A")
    val b = createNode("B")

    // when
    val result = updateWithBothPlanners("MATCH (a {name:'A'}), (b {name:'B'}) MERGE (a)-[r:TYPE {name:'Lola'}]->(b) RETURN count(r)")

    // then

    assertStats(result, relationshipsCreated = 1, propertiesWritten = 1)
    executeWithAllPlanners("MATCH (a {name:'A'})-[r:TYPE {name:'Lola'}]->(b {name:'B'}) RETURN a, b").toList should equal(
      List(Map("a" -> a, "b" -> b)))
  }

  test("should handle on create") {
    // given
    val a = createNode("A")
    val b = createNode("B")

    // when
    val result = updateWithBothPlanners("MATCH (a {name:'A'}), (b {name:'B'}) MERGE (a)-[r:TYPE]->(b) ON CREATE SET r.name = 'Lola' RETURN count(r)")

    // then

    assertStats(result, relationshipsCreated = 1, propertiesWritten = 1)
    executeWithAllPlanners("MATCH (a {name:'A'})-[r]->(b) RETURN r.name").toList should equal(List(Map("r.name" -> "Lola")))
  }

  test("should handle on match") {
    // given
    val a = createNode("A")
    val b = createNode("B")
    relate(a, b, "TYPE")

    // when
    val result = updateWithBothPlanners("MATCH (a {name:'A'}), (b {name:'B'}) MERGE (a)-[r:TYPE]->(b) ON MATCH SET r.name = 'Lola' RETURN count(r)")

    // then
    assertStats(result, relationshipsCreated = 0, propertiesWritten = 1)
    executeScalarWithAllPlanners[String]("MATCH (a {name:'A'})-[r:TYPE]->(b {name:'B'}) RETURN r.name") should equal("Lola")
  }

  test("should handle on create and on match") {
    // given
    relate(createNode("name" -> "A", "id" -> 1), createNode("name" -> "B", "id" -> 2), "TYPE")
    createNode("name" -> "A", "id" -> 3)
    createNode("name" -> "B", "id" -> 4)

    // when
    val result = updateWithBothPlanners(
      "MATCH (a {name:'A'}), (b {name:'B'}) MERGE (a)-[r:TYPE]->(b) ON CREATE SET r.name = 'Lola' ON MATCH set r.name = 'RUN' RETURN count(r)")

    // then
    assertStats(result, relationshipsCreated = 3, propertiesWritten = 4)
    executeWithAllPlanners("MATCH (a {name:'A'})-[r]->(b) RETURN a.id, r.name, b.id").toSet should equal(Set(
      Map("a.id" -> 1, "b.id" -> 2, "r.name" -> "RUN"),
      Map("a.id" -> 3, "b.id" -> 2, "r.name" -> "Lola"),
      Map("a.id" -> 1, "b.id" -> 4, "r.name" -> "Lola"),
      Map("a.id" -> 3, "b.id" -> 4, "r.name" -> "Lola")
    ))
  }

  test("should work with single bound node") {
    // given
    val a = createNode("A")

    // when
    val result = updateWithBothPlanners("MATCH (a {name:'A'}) MERGE (a)-[r:TYPE]->() RETURN count(r)")

    // then
    assertStats(result, relationshipsCreated = 1, nodesCreated = 1)
    executeWithAllPlanners("MATCH (a)-[r:TYPE]->() RETURN a").toList should equal(List(Map("a" -> a)))
  }

  test("should handle longer patterns") {
    // given
    val a = createNode("A")

    // when
    val result = updateWithBothPlanners("MATCH (a {name:'A'}) MERGE (a)-[r:TYPE]->()<-[:TYPE]-(b) RETURN count(r)")

    // then
    assertStats(result, relationshipsCreated = 2, nodesCreated = 2)
    executeWithAllPlanners("MATCH (a {name:'A'})-[r:TYPE]->()<-[:TYPE]-(b) RETURN a").toList should equal(List(Map("a" -> a)))
  }

  test("should handle nodes bound in the middle") {
    // given
    val b = createNode("B")

    // when
    val result = updateWithBothPlanners("MATCH (b {name:'B'}) MERGE (a)-[r1:TYPE]->(b)<-[r2:TYPE]-(c) RETURN type(r1), type(r2)")

    // then
    assertStats(result, relationshipsCreated = 2, nodesCreated = 2)
    result.toList should equal(List(Map("type(r1)"-> "TYPE", "type(r2)" -> "TYPE")))
  }

  test("should handle nodes bound in the middle when half pattern is matching") {
    // given
    val a = createLabeledNode("A")
    val b = createLabeledNode("B")
    relate(a, b, "TYPE")

    // when
    val result = updateWithBothPlanners("MATCH (b:B) MERGE (a:A)-[r1:TYPE]->(b)<-[r2:TYPE]-(c:C) RETURN type(r1), type(r2)")

    // then
    assertStats(result, relationshipsCreated = 2, nodesCreated = 2, labelsAdded = 2)
    result.toList should equal(List(Map("type(r1)"-> "TYPE", "type(r2)" -> "TYPE")))
  }

  test("should handle first declaring nodes and then creating relationships between them") {
    // given
    val a = createLabeledNode("A")
    val b = createLabeledNode("B")

    // when
    val result = updateWithBothPlanners("MERGE (a:A) MERGE (b:B) MERGE (a)-[:FOO]->(b)")

    // then
    assertStats(result, relationshipsCreated = 1)
  }

  test("should handle building links mixing create with merge pattern") {
    // given

    // when
    val result = updateWithBothPlanners("CREATE (a:A) MERGE (a)-[:KNOWS]->(b:B) CREATE (b)-[:KNOWS]->(c:C) RETURN count(*)")

    // then
    assertStats(result, relationshipsCreated = 2, nodesCreated = 3, labelsAdded = 3)
  }

  test("when merging a pattern that includes a unique node constraint violation fail") {
    // given
    graph.createConstraint("Person", "id")
    createLabeledNode(Map("id"->666), "Person")

    // when then fails
    intercept[CypherExecutionException](executeWithCostPlannerOnly("CREATE (a:A) MERGE (a)-[:KNOWS]->(b:Person {id:666})"))
  }

  test("should work well inside foreach") {
    val a = createLabeledNode("Start")
    relate(a, createNode("prop" -> 2), "FOO")

    val result = executeWithRulePlanner("match (a:Start) foreach(x in [1,2,3] | merge (a)-[:FOO]->({prop: x}) )")
    assertStats(result, nodesCreated = 2, propertiesWritten = 2, relationshipsCreated = 2)
  }

  test("should_handle_two_merges_inside_foreach") {
    val a = createLabeledNode("Start")
    val b = createLabeledNode(Map("prop" -> 42), "End")


    val result = executeWithRulePlanner("match (a:Start) foreach(x in [42] | merge (b:End {prop: x}) merge (a)-[:FOO]->(b) )")
    assertStats(result, nodesCreated = 0, propertiesWritten = 0, relationshipsCreated = 1)

    graph.inTx {
      val rel = a.getRelationships.iterator().next()
      rel.getStartNode should equal(a)
      rel.getEndNode should equal(b)
    }
  }

  test("should_handle_two_merges_inside_bare_foreach") {
    createNode("x" -> 1)

    val result = executeWithRulePlanner("foreach(v in [1, 2] | merge (a {x: v}) merge (b {y: v}) merge (a)-[:FOO]->(b))")
    assertStats(result, nodesCreated = 3, propertiesWritten = 3, relationshipsCreated = 2)
  }

  test("should_handle_two_merges_inside_foreach_after_with") {
    val result = executeWithRulePlanner("with 3 as y " +
      "foreach(x in [1, 2] | " +
      "merge (a {x: x, y: y}) " +
      "merge (b {x: x+1, y: y}) " +
      "merge (a)-[:FOO]->(b))")
    assertStats(result, nodesCreated = 3, propertiesWritten = 6, relationshipsCreated = 2)
  }

  test("should introduce named paths1") {
    val result = executeWithCostPlannerOnly("merge (a) merge p = (a)-[:R]->() return p")
    assertStats(result, relationshipsCreated = 1, nodesCreated = 2)
    val resultList = result.toList
    result should have size 1
    resultList.head.head._2.isInstanceOf[Path] should be(true)
  }


  test("should introduce named paths2") {
    val result = executeWithCostPlannerOnly("merge (a { x:1 }) merge (b { x:2 }) merge p = (a)-[:R]->(b) return p")
    assertStats(result, relationshipsCreated = 1, nodesCreated = 2, propertiesWritten = 2)
    val resultList = result.toList
    result should have size 1
    resultList.head.head._2.isInstanceOf[Path] should be(true)
  }


  test("should introduce named paths3") {
    val result = executeWithCostPlannerOnly("merge p = (a { x:1 }) return p")
    assertStats(result, nodesCreated = 1, propertiesWritten = 1)
    val resultList = result.toList
    result should have size 1
    resultList.head.head._2.isInstanceOf[Path] should be(true)
  }

  test("should_handle_foreach_in_foreach_game_of_life_ftw") {

    /* creates a grid 4 nodes wide and 4 nodes deep.
     o-o-o-o
     | | | |
     o-o-o-o
     | | | |
     o-o-o-o
     | | | |
     o-o-o-o
     */

    val result = executeWithRulePlanner(
      "foreach(x in [0,1,2] |" +
        "foreach(y in [0,1,2] |" +
        "  merge (a {x:x, y:y})" +
        "  merge (b {x:x+1, y:y})" +
        "  merge (c {x:x, y:y+1})" +
        "  merge (d {x:x+1, y:y+1})" +
        "  merge (a)-[:R]->(b)" +
        "  merge (a)-[:R]->(c)" +
        "  merge (b)-[:R]->(d)" +
        "  merge (c)-[:R]->(d)))")

    assertStats(result, nodesCreated = 16, relationshipsCreated = 24, propertiesWritten = 16 * 2)
  }

  test("should handle merge with no known points") {
    val result = updateWithBothPlanners("merge ({name:'Andres'})-[:R]->({name:'Emil'})")

    assertStats(result, nodesCreated = 2, relationshipsCreated = 1, propertiesWritten = 2)
  }

  test("should_handle_foreach_in_foreach_game_without_known_points") {

    /* creates a grid 4 nodes wide and 4 nodes deep.
     o-o o-o o-o
     | | | | | |
     o-o o-o o-o
     | | | | | |
     o-o o-o o-o
     | | | | | |
     o-o o-o o-o
     */

    val result = executeWithRulePlanner(
      "foreach(x in [0,1,2] |" +
        "foreach(y in [0,1,2] |" +
        "  merge (a {x:x, y:y})-[:R]->(b {x:x+1, y:y})" +
        "  merge (c {x:x, y:y+1})-[:R]->(d {x:x+1, y:y+1})" +
        "  merge (a)-[:R]->(c)" +
        "  merge (b)-[:R]->(d)))")

    assertStats(result, nodesCreated = 6*4, relationshipsCreated = 3*4+6*3, propertiesWritten = 6*4*2)
  }

  test("should handle on create on created nodes") {
    val result = updateWithBothPlanners("merge (a)-[:KNOWS]->(b) ON CREATE SET b.created = timestamp()")

    assertStats(result, nodesCreated = 2, relationshipsCreated = 1, propertiesWritten = 1)
  }

  test("should handle on match on created nodes") {
    val result = updateWithBothPlanners("merge (a)-[:KNOWS]->(b) ON MATCH SET b.created = timestamp()")

    assertStats(result, nodesCreated = 2, relationshipsCreated = 1, propertiesWritten = 0)
  }

  test("should handle on create on created rels") {
    val result = updateWithBothPlanners("merge (a)-[r:KNOWS]->(b) ON CREATE SET r.created = timestamp()")

    assertStats(result, nodesCreated = 2, relationshipsCreated = 1, propertiesWritten = 1)
  }

  test("should handle on match on created rels") {
    val result = updateWithBothPlanners("merge (a)-[r:KNOWS]->(b) ON MATCH SET r.created = timestamp()")

    assertStats(result, nodesCreated = 2, relationshipsCreated = 1, propertiesWritten = 0)
  }

  test("should use left to right direction when creating based on pattern with undirected relationship") {
    val result = executeScalar[Relationship]("merge (a {id: 2})-[r:KNOWS]-(b {id: 1}) RETURN r")

    graph.inTx {
      result.getEndNode.getProperty("id") should equal(1)
      result.getStartNode.getProperty("id") should equal(2)
    }
  }

  test("should find existing right to left relationship when matching with undirected relationship") {
    val r = relate(createNode("id" -> 1), createNode("id" -> 2), "KNOWS")
    val result = executeScalar[Relationship]("merge (a {id: 2})-[r:KNOWS]-(b {id: 1}) RETURN r")

    result should equal(r)
  }

  test("should_find_existing_left_to_right_relationship_when_matching_with_undirected_relationship") {
    val r = relate(createNode("id" -> 2), createNode("id" -> 1), "KNOWS")
    val result = executeScalar[Relationship]("merge (a {id: 2})-[r:KNOWS]-(b {id: 1}) RETURN r")

    result should equal(r)
  }

  test("should find existing relationships when matching with undirected relationship") {
    val r1 = relate(createNode("id" -> 2), createNode("id" -> 1), "KNOWS")
    val r2 = relate(createNode("id" -> 1), createNode("id" -> 2), "KNOWS")
    val result = updateWithBothPlanners("merge (a {id: 2})-[r:KNOWS]-(b {id: 1}) RETURN r").columnAs[Relationship]("r").toSet

    result should equal(Set(r1, r2))
  }

  test("should_reject_merging_nodes_having_the_same_id_but_different_labels") {
    intercept[SyntaxException]{
      executeWithRulePlanner("merge (a: Foo)-[r:KNOWS]->(a: Bar)")
    }
  }

  test("merge should handle array properties properly from variable") {
    val query =
      """
        |CREATE (a:Foo),(b:Bar) WITH a,b
        |UNWIND ["a,b","a,b"] AS str WITH a,b,split(str,",") AS roles
        |MERGE (a)-[r:FB {foobar:roles}]->(b)
        |RETURN count(*)""".stripMargin


    val result = updateWithBothPlanners(query)
    assertStats(result, nodesCreated = 2, relationshipsCreated = 1, propertiesWritten = 1, labelsAdded = 2)
  }

  test("merge should handle array properties properly") {
    relate(createLabeledNode("A"), createLabeledNode("B"), "T", Map("prop" -> Array(42, 43)))

    val result = updateWithBothPlanners("MATCH (a:A),(b:B) MERGE (a)-[r:T {prop: [42,43]}]->(b) RETURN count(*)")
    assertStats(result, nodesCreated = 0, relationshipsCreated = 0, propertiesWritten = 0)
  }

  test("merge should see variables introduced by other update actions") {
    // when
    val result = updateWithBothPlanners("CREATE (a) MERGE (a)-[:X]->() RETURN count(a)")

    // then
    assertStats(result, nodesCreated = 2, relationshipsCreated = 1)
  }

  test("merge should see variables introduced by update actions") {
    // when
    val result = updateWithBothPlanners("CREATE (a) MERGE (a)-[:X]->() RETURN count(*)")

    // then
    assertStats(result, nodesCreated = 2, relationshipsCreated = 1)
  }

  test("unwind combined with multiple merges") {
    val query = """ UNWIND ['Keanu Reeves','Hugo Weaving','Carrie-Anne Moss','Laurence Fishburne'] AS actor
       |MERGE (m:Movie  {name: 'The Matrix'})
       |MERGE (p:Person {name: actor})
       |MERGE (p)-[:ACTED_IN]->(m)""".stripMargin

    val result = updateWithBothPlanners(query)

    assertStats(result, nodesCreated = 5, relationshipsCreated = 4, propertiesWritten = 5, labelsAdded = 5)
  }

  test("a merge following a delete of multiple rows should not match on a deleted entity") {
    // GIVEN
    val a = createLabeledNode("A")
    val branches = 2
    val b = (0 until branches).map(n => createLabeledNode(Map("value" -> n), "B"))
    val c = (0 until branches).map(_ => createLabeledNode("C"))
    (0 until branches).foreach(n => {
      relate(a, b(n))
      relate(b(n), c(n))
    })

    val query =
      """
        |MATCH (a:A) -[ab]-> (b:B) -[bc]-> (c:C)
        |DELETE ab, bc, b, c
        |MERGE (newB:B { value: 1 })
        |MERGE (a) -[:REL]->  (newB)
        |MERGE (newC:C)
        |MERGE (newB) -[:REL]-> (newC)
      """.stripMargin

    // WHEN
    updateWithBothPlanners(query)

    // THEN
    assert(true)
  }

  test("variables of deleted nodes should not be able to cause errors in later merge actions that do not refer to them")
  {
    // GIVEN
    val a = createLabeledNode("A")
    val b = createLabeledNode("B")
    val c = createLabeledNode("C")
    relate(a, b)
    relate(b, c)

    val query =
      """
        |MATCH (a:A) -[ab]-> (b:B) -[bc]-> (c:C)
        |DELETE ab, bc, b, c
        |MERGE (newB:B)
        |MERGE (a) -[:REL]->  (newB)
        |MERGE (newC:C)
        |MERGE (newB) -[:REL]-> (newC)
      """.stripMargin

    // WHEN
    updateWithBothPlanners(query)

    // THEN query should not crash
    assert(true)
  }

  test("merges should not be able to match on deleted relationships") {
    // GIVEN
    val a = createNode()
    val b = createNode()
    val rel1 = relate(a, b, "T", Map("name" -> "rel1"))
    val rel2 = relate(a, b, "T", Map("name" -> "rel2"))

    val query = """
                  |MATCH (a)-[t:T]->(b)
                  |DELETE t
                  |MERGE (a)-[t2:T {name:'rel3'}]->(b)
                  |RETURN t2.name
                """.stripMargin

    // WHEN
    val result = updateWithBothPlanners(query)

    // THEN
    result.toList should not contain Map("t2.name" -> "rel1")
    result.toList should not contain Map("t2.name" -> "rel2")
  }
}
