package com.tuniren.tuai

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.tuniren.tuai.knowledge.*
import com.tuniren.tuai.knowledge.JsonCodecs.given
import com.tuniren.tuai.http.HttpApp
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import java.util.UUID
import org.http4s.{Header, Method, Request, Status, Uri}
import org.http4s.circe.*
import org.typelevel.ci.CIString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class McpRoutesTest:
  private def call(f: TestFixture, method: Method, path: String, body: Json = Json.Null,
                   token: Boolean = true): IO[(Int, String)] =
    val raw = Request[IO](method, Uri.unsafeFromString(path)).withEntity(body)
      .putHeaders(Header.Raw(CIString("Accept"), "application/json, text/event-stream"))
    val request = if token then raw.putHeaders(Header.Raw(CIString("Authorization"), "Bearer test-token")) else raw
    HttpApp.create(f.service, f.memories, f.store.healthy, "test-token")(request).flatMap(r => r.as[String].map(r.status.code -> _))
  private def json(text: String): Json = parse(text).toOption.get
  private def rpc(f: TestFixture, method: String, params: Json): IO[Json] =
    call(f, Method.POST, "/mcp", Json.obj("jsonrpc" -> "2.0".asJson, "id" -> 1.asJson,
      "method" -> method.asJson, "params" -> params)).map: (status, body) =>
        assertEquals(200, status, body)
        json(body)
  private def tool(f: TestFixture, name: String, arguments: Json): IO[Json] =
    rpc(f, "tools/call", Json.obj("name" -> name.asJson, "arguments" -> arguments))
  private def value(response: Json): Json =
    assertFalse(response.hcursor.downField("result").get[Boolean]("isError").toOption.get, response.toString)
    json(response.hcursor.downField("result").downField("content").downArray.get[String]("text").toOption.get)
  private def failed(response: Json): Boolean =
    response.hcursor.downField("result").get[Boolean]("isError").toOption.get

  @Test
  def completeKnowledgeLifecycleRunsEntirelyThroughMcp(): Unit = (for
    f <- TestFixture.create
    input = UpsertRequest("alpha", "README.md", "Readme", "first\r\nsecond")
    created <- tool(f, "kb_upsert_document", input.asJson)
    id = value(created).hcursor.get[UUID]("documentId").toOption.get
    repeated <- tool(f, "kb_upsert_document", input.asJson)
    _ = assertTrue(value(repeated).hcursor.get[Boolean]("unchanged").toOption.get)
    searched <- tool(f, "kb_search", Json.obj("namespace" -> "alpha".asJson, "query" -> "first".asJson))
    hit = value(searched).hcursor.downField("hits").downArray
    _ = assertEquals("README.md", hit.get[String]("source").toOption.get)
    _ = assertEquals(2, hit.get[Int]("endLine").toOption.get)
    read <- tool(f, "kb_read_document", Json.obj("namespace" -> "alpha".asJson, "documentId" -> id.asJson))
    _ = assertEquals("first\nsecond", value(read).hcursor.get[String]("content").toOption.get)
    updated <- tool(f, "kb_upsert_document", input.copy(content = "updated").asJson)
    _ = assertEquals(id, value(updated).hcursor.get[UUID]("documentId").toOption.get)
    listed <- tool(f, "kb_list_documents", Json.obj("namespace" -> "alpha".asJson))
    _ = assertEquals(20, value(listed).hcursor.get[Int]("limit").toOption.get)
    wrongScope <- tool(f, "kb_delete_document", Json.obj("namespace" -> "beta".asJson, "documentId" -> id.asJson))
    _ = assertTrue(failed(wrongScope))
    deleted <- tool(f, "kb_delete_document", Json.obj("namespace" -> "alpha".asJson, "documentId" -> id.asJson))
    _ = assertTrue(value(deleted).hcursor.get[Boolean]("deleted").toOption.get)
    missing <- tool(f, "kb_read_document", Json.obj("namespace" -> "alpha".asJson, "documentId" -> id.asJson))
    _ = assertTrue(failed(missing))
  yield ()).unsafeRunSync()

  @Test
  def validatesBeforeEffectsAndPreservesOldIndexAfterEmbeddingFailure(): Unit = (for
    f <- TestFixture.create
    input = UpsertRequest("alpha", "a", "a", "original")
    program = f.service.upsert(input)
    before <- f.calls.get
    _ = assertEquals(0, before)
    first <- program
    duplicate <- program
    _ = assertEquals(first.documentId, duplicate.documentId)
    _ = assertTrue(duplicate.unchanged)
    invalid <- tool(f, "kb_search", SearchRequest(" ", "query").asJson)
    _ = assertTrue(failed(invalid))
    afterInvalid <- f.calls.get
    _ = assertEquals(1, afterInvalid)
    _ <- f.fail.set(true)
    failure <- tool(f, "kb_upsert_document", input.copy(content = "replacement").asJson)
    _ = assertTrue(failed(failure))
    old <- f.service.get("alpha", first.documentId)
    _ = assertEquals("original", old.content)
  yield ()).unsafeRunSync()

  @Test
  def exposesOnlyMcpAndHealth(): Unit = (for
    f <- TestFixture.create
    mcp <- call(f, Method.POST, "/mcp", token = false)
    health <- call(f, Method.GET, "/health", token = false)
    _ = assertEquals(401, mcp._1)
    _ = assertEquals(200, health._1)
    oldPaths <- Vector("/docs", "/docs/docs.yaml", "/api/v1/rag/documents", "/api/v1/rag/search")
      .traverse(path => call(f, Method.GET, path).map(_._1))
    _ = assertTrue(oldPaths.forall(_ == 404))
  yield ()).unsafeRunSync()

  @Test
  def advertisesTenToolsWithAccurateMutationHintsAndProtocolErrors(): Unit = (for
    f <- TestFixture.create
    initialized <- rpc(f, "initialize", Json.obj("protocolVersion" -> "2025-06-18".asJson))
    _ = assertEquals("tuai-knowledge-base", initialized.hcursor.downField("result").downField("serverInfo").get[String]("name").toOption.get)
    listed <- rpc(f, "tools/list", Json.obj())
    tools = listed.hcursor.downField("result").get[Vector[Json]]("tools").toOption.get
    _ = assertEquals(10, tools.size)
    _ = tools.foreach: definition =>
      val name = definition.hcursor.get[String]("name").toOption.get
      val mutates = Set("kb_upsert_document", "kb_delete_document", "memory_upsert", "memory_delete").contains(name)
      assertEquals(!mutates, definition.hcursor.downField("annotations").get[Boolean]("readOnlyHint").toOption.get)
      assertEquals(mutates, definition.hcursor.downField("annotations").get[Boolean]("destructiveHint").toOption.get)
    unknown <- tool(f, "missing", Json.obj())
    _ = assertEquals(-32602, unknown.hcursor.downField("error").get[Int]("code").toOption.get)
    invalid <- tool(f, "kb_upsert_document", Json.obj("namespace" -> "alpha".asJson))
    _ = assertEquals(-32602, invalid.hcursor.downField("error").get[Int]("code").toOption.get)
  yield ()).unsafeRunSync()

  @Test
  def malformedJsonOversizedBodiesAndCrossOriginAreRejected(): Unit = (for
    f <- TestFixture.create
    app = HttpApp.create(f.service, f.memories, IO.pure(true), "")
    invalid <- app(Request[IO](Method.POST, Uri.unsafeFromString("/mcp")).withEntity("broken JSON")
      .putHeaders(Header.Raw(CIString("Content-Type"), "application/json"),
        Header.Raw(CIString("Accept"), "application/json, text/event-stream")))
    _ = assertEquals(Status.BadRequest, invalid.status)
    invalidBody <- invalid.as[Json]
    _ = assertEquals(-32700, invalidBody.hcursor.downField("error").get[Int]("code").toOption.get)
    large <- app(Request[IO](Method.POST, Uri.unsafeFromString("/mcp")).withEntity("x".repeat(2 * 1024 * 1024 + 1)))
    _ = assertEquals(Status.PayloadTooLarge, large.status)
    notification <- call(f, Method.POST, "/mcp", Json.obj("jsonrpc" -> "2.0".asJson, "method" -> "notifications/initialized".asJson))
    _ = assertEquals(202, notification._1)
    get <- call(f, Method.GET, "/mcp")
    _ = assertEquals(405, get._1)
    crossOrigin <- app(Request[IO](Method.POST, Uri.unsafeFromString("/mcp")).withEntity(Json.obj())
      .putHeaders(Header.Raw(CIString("Origin"), "https://other.example"), Header.Raw(CIString("Host"), "localhost")))
    _ = assertEquals(Status.Forbidden, crossOrigin.status)
  yield ()).unsafeRunSync()

  @Test
  def healthFailuresDoNotLeakConnectionDetails(): Unit = (for
    f <- TestFixture.create
    app = HttpApp.create(f.service, f.memories, IO.raiseError(new RuntimeException("private DB connection data")), "")
    response <- app(Request[IO](Method.GET, Uri.unsafeFromString("/health")))
    body <- response.as[String]
    _ = assertEquals(Status.ServiceUnavailable, response.status)
    _ = assertFalse(body.contains("private"))
    _ = assertEquals("Internal server error", com.tuniren.tuai.mcp.ToolErrors.message(new RuntimeException("private SQL")))
  yield ()).unsafeRunSync()
