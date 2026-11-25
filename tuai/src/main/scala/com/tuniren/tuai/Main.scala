package com.tuniren.tuai

import cats.effect.{IO, IOApp}
import com.tuniren.tuai.knowledge.KnowledgeBase
import com.tuniren.tuai.knowledge.documents.PostgresDocumentStore
import com.tuniren.tuai.knowledge.embedding.OpenAiEmbeddings
import com.tuniren.tuai.http.HttpApp
import com.tuniren.tuai.memory.{Memories, PostgresMemoryStore}
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import scala.concurrent.duration.*

/** The sole production entry point: acquire resources, assemble ordinary values, run until shutdown. */
object Main extends IOApp.Simple:
  override def run: IO[Unit] = AppConfig.load.flatMap: config =>
    val server = for
      xa <- Resources.database(config)
      client <- EmberClientBuilder.default[IO].withTimeout(config.embedding.timeout + 5.seconds).build
      store = new PostgresDocumentStore(xa)
      embeddings = new OpenAiEmbeddings(client, config.embedding)
      service = new KnowledgeBase(store, embeddings, config.chunking)
      memories = new Memories(new PostgresMemoryStore(xa), embeddings)
      running <- EmberServerBuilder.default[IO].withHost(config.host).withPort(config.port)
        .withHttpApp(HttpApp.create(service, memories, store.healthy, config.apiKey)).build
      _ <- cats.effect.Resource.eval(IO.println(s"TuAI Knowledge and Memory MCP listening on ${running.address}; MCP endpoint: /mcp"))
    yield running
    server.useForever
