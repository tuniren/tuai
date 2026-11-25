package com.tuniren.tuai.knowledge

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.tuniren.tuai.knowledge.documents.PostgresDocumentStore
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/** H2 verifies actual ConnectionIO execution and rollback; PostgreSQL-specific queries use the opt-in IT. */
class DoobieStoreTest:
  private def database: Resource[IO, Transactor[IO]] =
    val xa = Transactor.fromDriverManager[IO]("org.h2.Driver",
      s"jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "", None)
    Resource.make(IO.pure(xa))(db => sql"DROP ALL OBJECTS".update.run.transact(db).void).evalTap: db =>
      Vector("CREATE SCHEMA tuai", "CREATE DOMAIN public.vector AS VARCHAR",
        "CREATE TABLE tuai.rag_document (id UUID PRIMARY KEY, namespace VARCHAR, source VARCHAR, content_hash VARCHAR, embedding_profile VARCHAR)",
        """CREATE TABLE tuai.rag_chunk (id UUID PRIMARY KEY, document_id UUID REFERENCES tuai.rag_document(id) ON DELETE CASCADE,
          ordinal INT, content VARCHAR CHECK(content != 'reject-me'), start_line INT, end_line INT, embedding public.vector)""")
        .traverse_(sql => Fragment.const(sql).update.run).transact(db)

  @Test
  def rollsBackDeleteAndPartialBatchWhenChunkFails(): Unit = database.use: xa =>
    val store = new PostgresDocumentStore(xa)
    val id = UUID.randomUUID()
    for
      _ <- sql"INSERT INTO tuai.rag_document VALUES ($id, 'alpha', 'README.md', 'hash', 'profile')".update.run.transact(xa)
      _ <- store.replaceChunks(id, Vector((UUID.randomUUID(), id, 0, "original", 1, 1, "[1,0,0]"))).transact(xa)
      failed <- store.replaceChunks(id, Vector(
        (UUID.randomUUID(), id, 0, "new", 1, 1, "[1,0,0]"),
        (UUID.randomUUID(), id, 1, "reject-me", 2, 2, "[1,0,0]")
      )).transact(xa).attempt
      remaining <- sql"SELECT content FROM tuai.rag_chunk WHERE document_id = $id".query[String].to[Vector].transact(xa)
      _ = assertTrue(failed.isLeft)
      _ = assertEquals(Vector("original"), remaining)
      existing <- store.existing("alpha", "README.md")
      _ = assertEquals(Some(ExistingDocument(id, "hash", "profile", 1)), existing)
      wrong <- store.delete("beta", id)
      _ = assertFalse(wrong)
      deleted <- store.delete("alpha", id)
      count <- sql"SELECT count(*) FROM tuai.rag_chunk".query[Int].unique.transact(xa)
      _ = assertTrue(deleted)
      _ = assertEquals(0, count)
    yield ()
  .unsafeRunSync()
