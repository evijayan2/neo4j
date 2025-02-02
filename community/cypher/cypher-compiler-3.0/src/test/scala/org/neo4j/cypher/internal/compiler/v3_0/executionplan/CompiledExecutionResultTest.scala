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
package org.neo4j.cypher.internal.compiler.v3_0.executionplan

import java.util

import org.neo4j.cypher.internal.compiler.v3_0.codegen.ResultRowImpl
import org.neo4j.cypher.internal.compiler.v3_0.planDescription.InternalPlanDescription
import org.neo4j.cypher.internal.compiler.v3_0.spi.{InternalResultRow, InternalResultVisitor}
import org.neo4j.cypher.internal.compiler.v3_0.{ExecutionMode, NormalMode, TaskCloser}
import org.neo4j.cypher.internal.frontend.v3_0.test_helpers.CypherFunSuite
import org.neo4j.helpers.collection.Iterables._

import scala.collection.JavaConverters._

class CompiledExecutionResultTest extends CypherFunSuite {

  test("should return scala objects") {
    val result = newCompiledExecutionResult(javaMap("foo" -> "", "bar" -> ""))

    result.columns should equal(List("foo", "bar"))
  }

  test("should return scala objects for string") {
    val result = newCompiledExecutionResult(javaMap("foo" -> "bar"))

    result.columnAs[String]("foo").toList should equal(List("bar"))
  }

  test("should return scala objects for list") {
    val result = newCompiledExecutionResult(javaMap("foo" -> javaList(42)))

    result.columnAs[List[Integer]]("foo").toList should equal(List(List(42)))
  }

  test("should return scala objects for map") {
    val result = newCompiledExecutionResult(javaMap("foo" -> javaMap("key" -> "value")))

    result.columnAs[Map[String, Any]]("foo").toList should equal(List(Map("key" -> "value")))
  }

  test("should return java objects for string") {
    val result = newCompiledExecutionResult(javaMap("foo" -> "bar"))

    toList(result.javaColumnAs[String]("foo")) should equal(javaList("bar"))
  }

  test("should return java objects for list") {
    val result = newCompiledExecutionResult(javaMap("foo" -> javaList(42)))

    toList(result.javaColumnAs[List[Integer]]("foo")) should equal(javaList(javaList(42)))
  }

  test("should return java objects for map") {
    val result = newCompiledExecutionResult(javaMap("foo" -> javaMap("key" -> "value")))

    toList(result.javaColumnAs[Map[String, Any]]("foo")) should equal(javaList(javaMap("key" -> "value")))
  }

  test("result should be a scala iterator for string") {
    val result = newCompiledExecutionResult(javaMap("foo" -> "bar"))

    result.toList should equal(List(Map("foo" -> "bar")))
  }

  test("result should be a scala iterator for list") {
    val result = newCompiledExecutionResult(javaMap("foo" -> javaList(42)))

    result.toList should equal(List(Map("foo" -> List(42))))
  }

  test("result should be a scala iterator for map") {
    val result = newCompiledExecutionResult(javaMap("foo" -> javaMap("key" -> "value")))

    result.toList should equal(List(Map("foo" -> Map("key" -> "value"))))
  }

  test("should return a java iterator for string") {
    val result = newCompiledExecutionResult(javaMap("foo" -> "bar"))

    toList(result.javaIterator) should equal(javaList(javaMap("foo" -> "bar")))
  }

  test("should return a java iterator for list") {
    val result = newCompiledExecutionResult(javaMap("foo" -> javaList(42)))

    toList(result.javaIterator) should equal(javaList(javaMap("foo" -> javaList(42))))
  }

  test("should return a java iterator for map") {
    val result = newCompiledExecutionResult(javaMap("foo" -> javaMap("key" -> "value")))

    toList(result.javaIterator) should equal(javaList(javaMap("foo" -> javaMap("key" -> "value"))))
  }

  test("javaIterator hasNext should not call accept if results already consumed") {
    // given
    var timesCalled = 0
    val result = newCompiledExecutionResult(assertion = () => {
      // then
      timesCalled should equal(0)
      timesCalled += 1
    })

    // when
    result.accept(new InternalResultVisitor[Exception] {
      override def visit(row: InternalResultRow): Boolean = {
        false
      }
    })
  }

  test("close should work after result is consumed") {
    // given
    val result = newCompiledExecutionResult(javaMap("a" -> "1", "b" -> "2"))

    // when
    result.accept(new InternalResultVisitor[Exception] {
      override def visit(row: InternalResultRow): Boolean = {
        true
      }
    })

    result.close()

    // then
    // call of close actually worked
  }

  private def newCompiledExecutionResult(row: util.Map[String, Any] = new util.HashMap(),
                                         taskCloser: TaskCloser = new TaskCloser,
                                         assertion: () => Unit = () => {}) = {
    val noCompiledCode: GeneratedQueryExecution = new GeneratedQueryExecution {
      override def setSuccessfulCloseable(closeable: SuccessfulCloseable){}
      override def javaColumns(): util.List[String] = new util.ArrayList(row.keySet())
      override def executionMode(): ExecutionMode = NormalMode
      override def accept[E <: Exception](visitor: InternalResultVisitor[E]): Unit = {
        try {
          val rowImpl = new ResultRowImpl()
          row.asScala.foreach { case (k, v) => rowImpl.set(k, v) }
          visitor.visit(rowImpl)
          assertion()
        } finally {
          taskCloser.close(success = true)
        }
      }
      override def executionPlanDescription(): InternalPlanDescription = ???
    }

    new CompiledExecutionResult(taskCloser, null, noCompiledCode, null)
  }

  private def javaList[T](elements: T*): util.List[T] = elements.toList.asJava

  private def javaMap[K, V](pairs: (K, V)*): util.Map[K, V] = pairs.toMap.asJava
}
