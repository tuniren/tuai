package com.tuniren.tuai

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.tuniren.tuai.http.HttpApp
import com.tuniren.tuai.knowledge.UpsertRequest
import com.tuniren.tuai.memory.MemoryInput
import io.circe.Json
import org.http4s.{Header, Method, Request, Uri}
import org.http4s.circe.*
import org.typelevel.ci.CIString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SearchRoutesTest:
  private def get(f: TestFixture, path: String, token: Boolean = true): IO[(Int, Json)] =
    val request = Request[IO](Method.GET, Uri.unsafeFromString(path))
    val authorized = if token then request.putHeaders(Header.Raw(CIString("Authorization"), "Bearer test-token")) else request
    HttpApp.create(f.service, f.memories, f.store.healthy, "test-token")(authorized)
      .flatMap(response => response.as[Json].map(response.status.code -> _))

  @Test
  def semanticSearchReturnsDocumentEvidenceAndMemoryProvenance(): Unit = (for
    f <- TestFixture.create
    saved <- f.service.upsert(UpsertRequest("alpha", "README.md", "Readme", "first\nsecond"))
    _ <- f.service.upsert(UpsertRequest("beta", "README.md", "Other", "isolated"))
    result <- get(f, "/api/search?namespace=alpha&q=question")
    _ = assertEquals(200, result._1)
    item = result._2.hcursor.downField("items").downArray
    _ = assertEquals(saved.documentId.toString, item.get[String]("documentId").toOption.get)
    _ = assertEquals("first\nsecond", item.get[String]("content").toOption.get)
    _ = assertEquals(Some(1), item.get[Int]("startLine").toOption)
    _ = assertEquals(Some(2), item.get[Int]("endLine").toOption)
    _ = assertEquals(Some(1.0), item.get[Double]("score").toOption)
    _ = assertEquals(1, result._2.hcursor.get[Vector[Json]]("items").toOption.get.size)
    memory <- f.memories.upsert(MemoryInput("personal", "answer-language", "Language", "Chinese", "preference", "user request", 0))
    recalled <- get(f, "/api/search?namespace=personal&type=memories&q=language&kind=preference")
    _ = assertEquals(200, recalled._1)
    hit = recalled._2.hcursor.downField("items").downArray
    _ = assertEquals(Some("answer-language"), hit.get[String]("key").toOption)
    _ = assertEquals(Some("user request"), hit.get[String]("source").toOption)
    _ = assertEquals(Some(memory.summary.revision), hit.get[Long]("revision").toOption)
  yield ()).unsafeRunSync()

  @Test
  def keywordSearchIncludesCodeAndWorksDuringModelOutage(): Unit = (for
    f <- TestFixture.create
    _ <- f.service.upsert(UpsertRequest("alpha", "main.scala", "Code", "literal %_ example"))
    _ <- f.memories.upsert(MemoryInput("alpha", "fact", "Fact", "literal %_", "fact", "test", 0))
    _ <- f.fail.set(true)
    before <- f.calls.get
    documents <- get(f, "/api/search?namespace=alpha&q=%25_&mode=keyword")
    _ = assertEquals(200, documents._1)
    item = documents._2.hcursor.downField("items").downArray
    _ = assertEquals(Some("main.scala"), item.get[String]("source").toOption)
    _ = assertEquals(Some(None), item.get[Option[Double]]("score").toOption)
    memories <- get(f, "/api/search?namespace=alpha&type=memories&q=%25_&mode=keyword")
    _ = assertEquals(200, memories._1)
    _ = assertEquals(1, memories._2.hcursor.get[Vector[Json]]("items").toOption.get.size)
    empty <- get(f, "/api/search?namespace=unknown&q=example&mode=keyword")
    _ = assertEquals(Vector.empty, empty._2.hcursor.get[Vector[Json]]("items").toOption.get)
    after <- f.calls.get
    _ = assertEquals(before, after)
    outage <- get(f, "/api/search?namespace=alpha&q=question")
    _ = assertEquals(503, outage._1)
    _ = assertFalse(outage._2.noSpaces.contains("Provider unavailable"))
  yield ()).unsafeRunSync()

  @Test
  def invalidFiltersAreRejectedBeforeCallingModels(): Unit = (for
    f <- TestFixture.create
    _ <- Vector("/api/search?namespace=alpha", "/api/search?namespace=%00&q=x", "/api/search?namespace=alpha&q=%00",
      "/api/search?namespace=alpha&q=x&type=invalid", "/api/search?namespace=alpha&q=x&mode=invalid",
      "/api/search?namespace=alpha&q=x&limit=0", "/api/search?namespace=alpha&q=x&limit=21",
      "/api/search?namespace=alpha&q=x&limit=nope", "/api/search?namespace=alpha&q=x&minScore=NaN",
      "/api/search?namespace=alpha&q=x&minScore=Infinity", "/api/search?namespace=alpha&q=x&minScore=-1",
      "/api/search?namespace=alpha&q=x&minScore=oops", "/api/search?namespace=alpha&q=x&kind=fact",
      "/api/search?namespace=alpha&q=x&type=memories&kind=invalid", "/api/search?namespace=alpha&q=x&mode=keyword&minScore=0.5")
      .traverse_(path => get(f, path).map(response => assertEquals(400, response._1, path)))
    count <- f.calls.get
    _ = assertEquals(0, count)
    unauthorized <- get(f, "/api/search?namespace=alpha&q=x", token = false)
    _ = assertEquals(401, unauthorized._1)
  yield ()).unsafeRunSync()

  @Test
  def optionalNamespaceSearchesGloballyAndPreservesEachResultNamespace(): Unit = (for
    f <- TestFixture.create
    _ <- Vector("alpha", "beta").traverse_(namespace =>
      f.service.upsert(UpsertRequest(namespace, "README.md", "Needle", "needle")))
    _ <- Vector("alpha", "beta", "memory-only").traverse_(namespace =>
      f.memories.upsert(MemoryInput(namespace, "same-key", "Needle", "needle", "fact", "test", 0)))
    choices <- get(f, "/api/search/namespaces")
    _ = assertEquals(200, choices._1)
    _ = assertEquals(Vector("alpha", "beta", "memory-only"), choices._2.as[Vector[String]].toOption.get)
    _ <- Vector("semantic", "keyword").traverse_ { mode =>
      for
        global <- get(f, s"/api/search?q=needle&mode=$mode")
        _ = assertEquals(200, global._1)
        _ = assertTrue(global._2.hcursor.downField("namespace").focus.get.isNull)
        items = global._2.hcursor.get[Vector[Json]]("items").toOption.get
        _ = assertEquals(Set("alpha", "beta"), items.map(_.hcursor.get[String]("namespace").toOption.get).toSet)
        scoped <- get(f, s"/api/search?namespace=beta&q=needle&mode=$mode")
        _ = assertEquals(Vector("beta"), scoped._2.hcursor.get[Vector[Json]]("items").toOption.get
          .map(_.hcursor.get[String]("namespace").toOption.get))
        limited <- get(f, s"/api/search?q=needle&mode=$mode&limit=1")
        _ = assertEquals(1, limited._2.hcursor.get[Vector[Json]]("items").toOption.get.size)
        memories <- get(f, s"/api/search?q=needle&type=memories&mode=$mode")
        _ = assertEquals(Set("alpha", "beta", "memory-only"), memories._2.hcursor.get[Vector[Json]]("items").toOption.get
          .map(_.hcursor.get[String]("namespace").toOption.get).toSet)
        selected <- get(f, s"/api/search?namespace=memory-only&q=needle&type=memories&mode=$mode")
        _ = assertEquals(Vector("memory-only"), selected._2.hcursor.get[Vector[Json]]("items").toOption.get
          .map(_.hcursor.get[String]("namespace").toOption.get))
      yield ()
    }
    blank <- get(f, "/api/search?namespace=&q=needle&mode=keyword")
    _ = assertEquals(2, blank._2.hcursor.get[Vector[Json]]("items").toOption.get.size)
    strict <- f.service.search(com.tuniren.tuai.knowledge.SearchRequest("", "needle")).attempt
    _ = assertTrue(strict.isLeft) // MCP services still require a namespace unless explicitly searching globally.
  yield ()).unsafeRunSync()
