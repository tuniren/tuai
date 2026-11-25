package com.tuniren.tuai

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.tuniren.tuai.knowledge.*
import com.tuniren.tuai.knowledge.documents.PostgresDocumentStore
import com.tuniren.tuai.knowledge.embedding.Embeddings
import doobie.implicits.*
import doobie.postgres.implicits.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class KnowledgeBasePostgresIT:
  @Test
  def migratesSearchesAndRollsBackWholeIndexes(): Unit = PostgresTestDatabase.config.use: config =>
    val embeddings = new Embeddings:
      val model = "test"
      val profile = "test-profile"
      def embed(texts: Vector[String]): IO[Vector[Vector[Float]]] = IO.pure(texts.map(_ => Vector(1f, 0f, 0f)))
    Resources.database(config).use: xa =>
      val store = new PostgresDocumentStore(xa)
      val service = new KnowledgeBase(store, embeddings, config.chunking)
      val original = UpsertRequest("alpha", "README.md", "Readme", "original")
      for
        first <- service.upsert(original)
        repeated <- service.upsert(original)
        _ = assertTrue(repeated.unchanged)
        _ <- service.upsert(original.copy(namespace = "beta", content = "other project"))
        namespaces <- service.namespaces
        _ = assertEquals(Vector(NamespaceSummary("alpha", 1, 1), NamespaceSummary("beta", 1, 1)), namespaces)
        page <- service.browse("alpha", "ORIGINAL", 1, 0)
        _ = assertEquals(1L, page.total)
        _ = assertEquals(Vector(first.documentId), page.items.map(_.id))
        emptyPage <- service.browse("alpha", "", 1, 1)
        _ = assertEquals(1L, emptyPage.total)
        _ = assertTrue(emptyPage.items.isEmpty)
        literal <- service.browse("alpha", "%_", 20, 0)
        _ = assertEquals(0L, literal.total)
        resolved <- service.resolve("alpha", "README.md")
        _ = assertEquals(first.documentId, resolved.id)
        found <- service.search(SearchRequest("alpha", "original"))
        _ = assertEquals(1, found.hits.size)
        _ = assertEquals(first.documentId, found.hits.head.documentId)
        _ = assertEquals(1.0, found.hits.head.score, 0.00001)
        global <- service.search(SearchRequest("", "original"), allNamespaces = true)
        _ = assertEquals(Set("alpha", "beta"), global.hits.map(_.namespace).toSet)
        globalKeyword <- service.keywordSearch(SearchRequest("", "Readme"), allNamespaces = true)
        _ = assertEquals(Set("alpha", "beta"), globalKeyword.hits.map(_.namespace).toSet)
        globalLimit <- service.search(SearchRequest("", "original", topK = 1), allNamespaces = true)
        _ = assertEquals(1, globalLimit.hits.size)
        code <- service.upsert(UpsertRequest("alpha", "Main.scala", "Code", "中文 literal %_ Example"))
        _ <- sql"UPDATE tuai.rag_document SET embedding_profile = 'old-profile' WHERE id = ${code.documentId}".update.run.transact(xa)
        keyword <- service.keywordSearch(SearchRequest("alpha", "%_"))
        _ = assertEquals(Vector(code.documentId), keyword.hits.map(_.documentId))
        _ = assertEquals("中文 literal %_ Example", keyword.hits.head.content)
        chinese <- service.keywordSearch(SearchRequest("alpha", "中文"))
        _ = assertEquals(Vector(code.documentId), chinese.hits.map(_.documentId))
        uppercase <- service.keywordSearch(SearchRequest("alpha", "EXAMPLE"))
        _ = assertEquals(Vector(code.documentId), uppercase.hits.map(_.documentId))
        excluded <- service.keywordSearch(SearchRequest("beta", "%_"))
        _ = assertTrue(excluded.hits.isEmpty)
        _ <- service.delete("alpha", code.documentId)
        wrong <- service.get("beta", first.documentId).attempt
        _ = assertEquals(Left(MissingDocument), wrong)
        _ <- sql"ALTER TABLE tuai.rag_chunk ADD CONSTRAINT reject_marker CHECK(content NOT LIKE '%reject-me%')".update.run.transact(xa)
        failed <- service.upsert(original.copy(content = "reject-me")).attempt
        _ = assertEquals(Left(StorageFailure), failed)
        preserved <- service.get("alpha", first.documentId)
        _ = assertEquals("original", preserved.content)
        _ <- sql"ALTER TABLE tuai.rag_chunk DROP CONSTRAINT reject_marker".update.run.transact(xa)
        // Two replacements of one source must commit complete, unmixed chunk sets.
        _ <- Vector("new-a\n".repeat(80), "new-b\n".repeat(80)).parTraverse_(content => service.upsert(original.copy(content = content)))
        updated <- service.get("alpha", first.documentId)
        chunks <- sql"SELECT content FROM tuai.rag_chunk WHERE document_id = ${first.documentId}".query[String].to[Vector].transact(xa)
        _ = assertTrue(chunks.nonEmpty)
        _ = assertTrue(chunks.forall(updated.content.contains))
        _ <- service.delete("alpha", first.documentId)
        count <- sql"SELECT count(*) FROM tuai.rag_chunk WHERE document_id = ${first.documentId}".query[Int].unique.transact(xa)
        _ = assertEquals(0, count)
      yield ()
  .unsafeRunSync()
