package com.tuniren.tuai

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.tuniren.tuai.http.HttpApp
import com.tuniren.tuai.memory.*
import com.tuniren.tuai.memory.MemoryCodecs.given
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import org.http4s.{Header, Method, Request, Status, Uri}
import org.http4s.circe.*
import org.typelevel.ci.CIString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class MemoryRoutesTest:
  private val input = MemoryInput("user-liyd", "preferences/language", "回答语言", "用户偏好中文回答。",
    "preference", "用户在当前会话明确要求记住", 0)

  private def tool(f: TestFixture, name: String, arguments: Json): IO[Json] =
    val request = Request[IO](Method.POST, Uri.unsafeFromString("/mcp")).withEntity(Json.obj(
      "jsonrpc" -> "2.0".asJson, "id" -> 1.asJson, "method" -> "tools/call".asJson,
      "params" -> Json.obj("name" -> name.asJson, "arguments" -> arguments)))
      .putHeaders(Header.Raw(CIString("Accept"), "application/json, text/event-stream"),
        Header.Raw(CIString("Authorization"), "Bearer token"))
    HttpApp.create(f.service, f.memories, f.store.healthy, "token")(request).flatMap: response =>
      assertEquals(Status.Ok, response.status)
      response.as[Json]

  private def value(response: Json): Json =
    assertEquals(Some(false), response.hcursor.downField("result").get[Boolean]("isError").toOption, response.toString)
    parse(response.hcursor.downField("result").downField("content").downArray.get[String]("text").toOption.get).toOption.get

  private def error(response: Json): String =
    assertEquals(Some(true), response.hcursor.downField("result").get[Boolean]("isError").toOption, response.toString)
    response.hcursor.downField("result").downField("content").downArray.get[String]("text").toOption.get

  private def address(namespace: String = input.namespace): Json =
    Json.obj("namespace" -> namespace.asJson, "key" -> input.key.asJson)

  @Test
  def remembersRecallsCorrectsAndForgetsThroughMcp(): Unit = (for
    f <- TestFixture.create
    created <- tool(f, "memory_upsert", input.asJson)
    first = value(created).as[Memory].toOption.get
    duplicate <- tool(f, "memory_upsert", input.asJson)
    _ = assertTrue(error(duplicate).contains("revision conflict"))
    read <- tool(f, "memory_read", address())
    _ = assertEquals(first, value(read).as[Memory].toOption.get)
    wrong <- tool(f, "memory_read", address("project-tuai"))
    _ = assertEquals(MissingMemory.getMessage, error(wrong))
    _ <- f.memories.upsert(input.copy(namespace = "project-tuai", content = "另一个分组"))
    listed <- tool(f, "memory_list", Json.obj("namespace" -> input.namespace.asJson, "kind" -> "preference".asJson))
    summaries = value(listed).hcursor.downField("items").focus.get.asArray.get
    _ = assertEquals(1, summaries.size)
    _ = assertFalse(summaries.head.hcursor.downField("content").succeeded)
    searched <- tool(f, "memory_search", Json.obj("namespace" -> input.namespace.asJson, "query" -> "语言偏好".asJson))
    hits = value(searched).as[MemoryResults].toOption.get.hits
    _ = assertEquals(Vector(input.content), hits.map(_.memory.content))
    corrected <- tool(f, "memory_upsert", input.copy(content = "用户偏好简洁的中文回答。", expectedRevision = first.summary.revision).asJson)
    second = value(corrected).as[Memory].toOption.get
    _ = assertTrue(second.summary.revision > first.summary.revision)
    stale <- tool(f, "memory_delete", address().deepMerge(Json.obj("expectedRevision" -> first.summary.revision.asJson)))
    _ = assertTrue(error(stale).contains("revision conflict"))
    deleted <- tool(f, "memory_delete", address().deepMerge(Json.obj("expectedRevision" -> second.summary.revision.asJson)))
    _ = assertEquals(Some(true), value(deleted).hcursor.get[Boolean]("deleted").toOption)
    missing <- tool(f, "memory_read", address())
    _ = assertEquals(MissingMemory.getMessage, error(missing))
    isolated <- f.memories.read("project-tuai", input.key)
    _ = assertEquals("另一个分组", isolated.content)
  yield ()).unsafeRunSync()

  @Test
  def modelOutagePreservesMemoryAndKeywordReadListDeleteRemainAvailable(): Unit = (for
    f <- TestFixture.create
    first <- f.memories.upsert(input)
    _ <- f.fail.set(true)
    failed <- tool(f, "memory_upsert", input.copy(content = "replacement", expectedRevision = first.summary.revision).asJson)
    _ = assertEquals("Provider unavailable", error(failed))
    before <- f.calls.get
    result <- tool(f, "memory_search", MemorySearch(input.namespace, "中文", mode = "keyword").asJson)
    _ = assertEquals(Vector(first), value(result).as[MemoryResults].toOption.get.hits.map(_.memory))
    _ = assertEquals(None, value(result).as[MemoryResults].toOption.get.hits.head.score)
    literal <- tool(f, "memory_search", MemorySearch(input.namespace, "%_", mode = "keyword").asJson)
    _ = assertTrue(value(literal).as[MemoryResults].toOption.get.hits.isEmpty)
    _ <- tool(f, "memory_read", address()).map(value)
    _ <- tool(f, "memory_list", Json.obj("namespace" -> input.namespace.asJson)).map(value)
    _ <- tool(f, "memory_delete", address().deepMerge(Json.obj("expectedRevision" -> first.summary.revision.asJson))).map(value)
    after <- f.calls.get
    _ = assertEquals(before, after)
    semantic <- tool(f, "memory_search", MemorySearch(input.namespace, "语言").asJson)
    _ = assertEquals("Provider unavailable", error(semantic))
  yield ()).unsafeRunSync()

  @Test
  def invalidInputsHaveNoEmbeddingEffectsAndMalformedArgumentsAreProtocolErrors(): Unit = (for
    f <- TestFixture.create
    _ <- Vector(input.copy(namespace = " "), input.copy(key = "\u0000"), input.copy(kind = "instruction"),
      input.copy(source = ""), input.copy(content = "x".repeat(8001)), input.copy(expectedRevision = -1))
      .traverse_(bad => tool(f, "memory_upsert", bad.asJson).map(error))
    _ <- Vector(MemorySearch(input.namespace, " "), MemorySearch(input.namespace, "x", mode = "bad"),
      MemorySearch(input.namespace, "x", topK = 21), MemorySearch(input.namespace, "x", kind = Some("bad")),
      MemorySearch(input.namespace, "x", mode = "keyword", minScore = 0.5))
      .traverse_(bad => tool(f, "memory_search", bad.asJson).map(error))
    _ <- tool(f, "memory_list", Json.obj("namespace" -> input.namespace.asJson, "limit" -> 0.asJson)).map(error)
    malformed <- tool(f, "memory_upsert", input.asJson.mapObject(_.remove("expectedRevision")))
    _ = assertEquals(Some(-32602), malformed.hcursor.downField("error").get[Int]("code").toOption)
    wrongType <- tool(f, "memory_search", Json.obj("namespace" -> input.namespace.asJson, "query" -> "x".asJson, "topK" -> "five".asJson))
    _ = assertEquals(Some(-32602), wrongType.hcursor.downField("error").get[Int]("code").toOption)
    calls <- f.calls.get
    _ = assertEquals(0, calls)
  yield ()).unsafeRunSync()

  @Test
  def concurrentSessionsCannotOverwriteOrDeleteRecreatedMemories(): Unit = (for
    f <- TestFixture.create
    first <- f.memories.upsert(input)
    attempts <- Vector("correction A", "correction B").parTraverse(content =>
      f.memories.upsert(input.copy(content = content, expectedRevision = first.summary.revision)).attempt)
    _ = assertEquals(1, attempts.count(_.isRight))
    _ = assertEquals(Vector(MemoryConflict), attempts.collect { case Left(e) => e })
    winner <- f.memories.read(input.namespace, input.key)
    _ <- f.memories.delete(input.namespace, input.key, winner.summary.revision)
    recreated <- f.memories.upsert(input)
    _ = assertTrue(recreated.summary.revision > winner.summary.revision)
    staleUpdate <- f.memories.upsert(input.copy(expectedRevision = first.summary.revision)).attempt
    staleDelete <- f.memories.delete(input.namespace, input.key, first.summary.revision).attempt
    _ = assertEquals(Left(MemoryConflict), staleUpdate)
    _ = assertEquals(Left(MemoryConflict), staleDelete)
  yield ()).unsafeRunSync()
