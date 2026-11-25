package com.tuniren.tuai

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import com.comcast.ip4s.{Host, Port}
import com.tuniren.tuai.http.HttpApp
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpSchema
import io.circe.parser.parse
import java.time.Duration
import org.http4s.ember.server.EmberServerBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import scala.concurrent.duration.*

/** The official MCP client connects over a real socket; server and client are finalized by Resource. */
class McpClientTest:
  @Test
  def officialClientInitializesAndCallsAllTools(): Unit = (for
    fixture <- TestFixture.create
    _ <- EmberServerBuilder.default[IO].withHost(Host.fromString("127.0.0.1").get)
      .withPort(Port.fromInt(0).get).withShutdownTimeout(1.second)
      .withHttpApp(HttpApp.create(fixture.service, fixture.memories, IO.pure(true), "token")).build.use: server =>
        val transport = HttpClientStreamableHttpTransport.builder(s"http://127.0.0.1:${server.address.getPort}")
          .endpoint("/mcp").customizeRequest(_.header("Authorization", "Bearer token")).build()
        Resource.make(IO.blocking(McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build()))(
          client => IO.blocking(client.close())).use { client => IO.blocking {
            val initialized = client.initialize()
            assertEquals("tuai-knowledge-base", initialized.serverInfo().name())
            assertEquals(10, client.listTools().tools().size())
            val created = client.callTool(new McpSchema.CallToolRequest("kb_upsert_document",
              java.util.Map.of[String, Object]("namespace", "test", "source", "README.md", "title", "Readme", "content", "first\nsecond")))
            assertFalse(created.isError())
            val id = parse(created.content().getFirst.asInstanceOf[McpSchema.TextContent].text()).toOption.get
              .hcursor.get[String]("documentId").toOption.get
            val searched = client.callTool(new McpSchema.CallToolRequest("kb_search",
              java.util.Map.of[String, Object]("namespace", "test", "query", "first")))
            assertFalse(searched.isError())
            assertTrue(searched.content().getFirst.asInstanceOf[McpSchema.TextContent].text().contains("README.md"))
            val listed = client.callTool(new McpSchema.CallToolRequest("kb_list_documents",
              java.util.Map.of[String, Object]("namespace", "test")))
            assertFalse(listed.isError())
            val read = client.callTool(new McpSchema.CallToolRequest("kb_read_document",
              java.util.Map.of[String, Object]("namespace", "test", "documentId", id)))
            assertFalse(read.isError())
            assertTrue(read.content().getFirst.asInstanceOf[McpSchema.TextContent].text().contains("second"))
            val deleted = client.callTool(new McpSchema.CallToolRequest("kb_delete_document",
              java.util.Map.of[String, Object]("namespace", "test", "documentId", id)))
            assertFalse(deleted.isError())
            val missing = client.callTool(new McpSchema.CallToolRequest("kb_read_document",
              java.util.Map.of[String, Object]("namespace", "test", "documentId", id)))
            assertTrue(missing.isError())
            val remembered = client.callTool(new McpSchema.CallToolRequest("memory_upsert",
              java.util.Map.of[String, Object]("namespace", "user-test", "key", "preferences/language", "title", "Language",
                "content", "Prefer Chinese answers", "kind", "preference", "source", "user request", "expectedRevision", Long.box(0))))
            assertFalse(remembered.isError())
            val revision = parse(remembered.content().getFirst.asInstanceOf[McpSchema.TextContent].text()).toOption.get
              .hcursor.downField("summary").get[Long]("revision").toOption.get
            val recalled = client.callTool(new McpSchema.CallToolRequest("memory_search",
              java.util.Map.of[String, Object]("namespace", "user-test", "query", "language")))
            assertFalse(recalled.isError())
            assertTrue(recalled.content().getFirst.asInstanceOf[McpSchema.TextContent].text().contains("Prefer Chinese"))
            val memories = client.callTool(new McpSchema.CallToolRequest("memory_list",
              java.util.Map.of[String, Object]("namespace", "user-test", "kind", "preference")))
            assertFalse(memories.isError())
            val memory = client.callTool(new McpSchema.CallToolRequest("memory_read",
              java.util.Map.of[String, Object]("namespace", "user-test", "key", "preferences/language")))
            assertFalse(memory.isError())
            val forgotten = client.callTool(new McpSchema.CallToolRequest("memory_delete",
              java.util.Map.of[String, Object]("namespace", "user-test", "key", "preferences/language", "expectedRevision", Long.box(revision))))
            assertFalse(forgotten.isError())
        } }
  yield ()).unsafeRunSync()
