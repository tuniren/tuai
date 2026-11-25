package com.tuniren.tuai

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.tuniren.tuai.http.HttpApp
import com.tuniren.tuai.knowledge.*
import io.circe.Json
import io.circe.parser.parse
import org.http4s.{Method, Request, Uri}
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class LibraryRoutesTest:
  private def get(f: TestFixture, path: String): IO[(Int, Json)] =
    HttpApp.create(f.service, f.memories, f.store.healthy, "")(Request[IO](Method.GET, Uri.unsafeFromString(path)))
      .flatMap(r => r.as[String].map(body => (r.status.code, parse(body).toOption.get)))

  @Test
  def publicNamespaceCountsAndServerSearchPagination(): Unit = (for
    f <- TestFixture.create
    first <- f.service.upsert(UpsertRequest("alpha", "README.md", "Readme", "literal %_ guide"))
    _ <- f.service.upsert(UpsertRequest("alpha", "other.MD", "Other", "body needle"))
    _ <- f.service.upsert(UpsertRequest("alpha", "code.scala", "Code", "needle"))
    _ <- f.service.upsert(UpsertRequest("beta", "README.markdown", "Beta", "body"))
    counts <- get(f, "/api/namespaces")
    _ = assertEquals(200, counts._1)
    alpha = counts._2.asArray.get.head.hcursor
    _ = assertEquals("alpha", alpha.get[String]("namespace").toOption.get)
    _ = assertEquals(3L, alpha.get[Long]("documents").toOption.get)
    _ = assertEquals(2L, alpha.get[Long]("markdownDocuments").toOption.get)
    page <- get(f, "/api/documents?namespace=alpha&limit=1&offset=1")
    _ = assertEquals(2L, page._2.hcursor.get[Long]("total").toOption.get)
    _ = assertEquals("other.MD", page._2.hcursor.downField("items").downArray.get[String]("source").toOption.get)
    found <- get(f, "/api/documents?namespace=alpha&query=NEEDLE")
    _ = assertEquals(1L, found._2.hcursor.get[Long]("total").toOption.get)
    literal <- get(f, "/api/documents?namespace=alpha&query=%25_&limit=10")
    _ = assertEquals(1L, literal._2.hcursor.get[Long]("total").toOption.get)
    resolved <- get(f, "/api/documents/resolve?namespace=alpha&source=README.md")
    _ = assertEquals(first.documentId.toString, resolved._2.hcursor.get[String]("id").toOption.get)
    wrong <- get(f, s"/api/documents/${first.documentId}?namespace=beta")
    _ = assertEquals(404, wrong._1)
    empty <- get(f, "/api/documents?namespace=missing")
    _ = assertEquals(0L, empty._2.hcursor.get[Long]("total").toOption.get)
    _ <- f.calls.get.map(calls => assertEquals(4, calls)) // browsing never invokes embeddings
  yield ()).unsafeRunSync()

  @Test
  def rejectsInvalidQueriesAndPaging(): Unit = (for
    f <- TestFixture.create
    _ <- Vector("/api/documents", "/api/documents?namespace=alpha&limit=nope",
      "/api/documents?namespace=alpha&limit=101", "/api/documents?namespace=alpha&offset=-1",
      "/api/documents?namespace=alpha&query=%00", "/api/documents/not-a-uuid?namespace=alpha")
      .foldLeft(IO.unit)((previous, path) => previous *> get(f, path).map(result => assertEquals(400, result._1, path)))
  yield ()).unsafeRunSync()
