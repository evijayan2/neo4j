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

import org.mockito.Mockito._
import org.neo4j.cypher.internal.compiler.v3_0.spi.QueryContext
import org.neo4j.cypher.internal.frontend.v3_0.NameId._
import org.neo4j.cypher.internal.frontend.v3_0.{LabelId, RelTypeId, SemanticTable}
import org.neo4j.cypher.internal.frontend.v3_0.ast.{LabelName, RelTypeName, AstConstructionTestSupport}
import org.neo4j.cypher.internal.frontend.v3_0.test_helpers.CypherFunSuite

class RelationshipCountFromCountStorePipeTest extends CypherFunSuite with AstConstructionTestSupport {

  implicit val monitor = mock[PipeMonitor]

  test("should return a count for relationships without a type or any labels") {
    val pipe = RelationshipCountFromCountStorePipe("count(r)", None, LazyTypes.empty, None, bothDirections = false)()

    val queryState = QueryStateHelper.emptyWith(
      query = when(mock[QueryContext].relationshipCountByCountStore(WILDCARD, WILDCARD, WILDCARD)).thenReturn(42L).getMock[QueryContext]
    )
    pipe.createResults(queryState).map(_("count(r)")).toSet should equal(Set(42L))
  }

  test("should return a count for relationships with a type but no labels") {
    implicit val table = new SemanticTable()
    table.resolvedRelTypeNames.put("X", RelTypeId(22))

    val pipe = RelationshipCountFromCountStorePipe("count(r)", None, LazyTypes(Seq(RelTypeName("X")(pos))), None, bothDirections = false)()

    val queryState = QueryStateHelper.emptyWith(
      query = when(mock[QueryContext].relationshipCountByCountStore(WILDCARD, 22, WILDCARD)).thenReturn(42L).getMock[QueryContext]
    )
    pipe.createResults(queryState).map(_("count(r)")).toSet should equal(Set(42L))
  }

  test("should return a count for relationships with a type and start label") {
    implicit val table = new SemanticTable()
    table.resolvedRelTypeNames.put("X", RelTypeId(22))
    table.resolvedLabelIds.put("A", LabelId(12))

    val pipe = RelationshipCountFromCountStorePipe("count(r)", Some(LazyLabel(LabelName("A") _)), LazyTypes(Seq(RelTypeName("X")(pos))), None, bothDirections = false)()

    val queryState = QueryStateHelper.emptyWith(
      query = when(mock[QueryContext].relationshipCountByCountStore(12, 22, WILDCARD)).thenReturn(42L).getMock[QueryContext]
    )
    pipe.createResults(queryState).map(_("count(r)")).toSet should equal(Set(42L))
  }

  test("should return a count for relationships with a type and start label and un-directed relationship") {
    implicit val table = new SemanticTable()
    table.resolvedRelTypeNames.put("X", RelTypeId(22))
    table.resolvedLabelIds.put("A", LabelId(12))

    val pipe = RelationshipCountFromCountStorePipe("count(r)", Some(LazyLabel(LabelName("A") _)), LazyTypes(Seq(RelTypeName("X")(pos))), None, bothDirections = true)()

    val mockedContext: QueryContext = mock[QueryContext]
    when(mockedContext.relationshipCountByCountStore(12, 22, WILDCARD)).thenReturn(42L)
    when(mockedContext.relationshipCountByCountStore(WILDCARD, 22, 12)).thenReturn(38L)
    val queryState = QueryStateHelper.emptyWith(query = mockedContext)

    pipe.createResults(queryState).map(_("count(r)")).toSet should equal(Set(80L))
  }

  test("should return zero if rel-type is missing") {
    implicit val table = new SemanticTable()

    val pipe = RelationshipCountFromCountStorePipe("count(r)", None, LazyTypes(Seq("X")), Some(LazyLabel(LabelName("A") _)), bothDirections = false)()

    val mockedContext: QueryContext = mock[QueryContext]
    // try to guarantee that the mock won't be the reason for the exception
    when(mockedContext.relationshipCountByCountStore(WILDCARD, 22, 12)).thenReturn(38L)
    when(mockedContext.getOptLabelId("A")).thenReturn(None)
    val queryState = QueryStateHelper.emptyWith(query = mockedContext)

    pipe.createResults(queryState).map(_("count(r)")).toSet should equal(Set(0L))
  }

}
