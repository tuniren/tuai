package com.tuniren.tuai

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.tuniren.tuai.knowledge.StorageFailure
import com.tuniren.tuai.knowledge.embedding.Embeddings
import com.tuniren.tuai.memory.*
import doobie.implicits.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class MemoryPostgresIT:
  private val embeddings = new Embeddings:
    val model = "test"
    val profile = "memory-test-profile"
    def embed(texts: Vector[String]): IO[Vector[Vector[Float]]] = IO.pure(texts.map(_ => Vector(1f, 0f, 0f)))
  private val input = MemoryInput("user-liyd", "preferences/language", "Language", "用户偏好中文回答。", "preference", "test conversation", 0)

  @Test
  def upgradesV1AndVerifiesMemorySqlConcurrencyRollbackAndPersistence(): Unit = PostgresTestDatabase.config.use { config =>
    // Exercise an upgrade from an already installed V1, not just an empty database.
    IO.blocking {
      Flyway.configure().dataSource(config.database.url, config.database.username, config.database.password)
        .defaultSchema("tuai").schemas("tuai").placeholders(java.util.Map.of("embeddingDimensions", "3"))
        .target("1").load().migrate()
    }.flatMap { _ =>
      Resources.database(config.copy(database = config.database.copy(migrate = false))).use: xa =>
        sql"""INSERT INTO tuai.rag_document (id, namespace, source, title, content, content_hash, embedding_model, embedding_profile)
          VALUES ('11111111-1111-4111-8111-111111111111', 'existing', 'before-memory.md', 'old', 'preserved', 'hash', 'test', 'profile')""".update.run.transact(xa)
    }.flatMap { _ => Resources.database(config).use { xa =>
      val store = new PostgresMemoryStore(xa)
      val service = new Memories(store, embeddings)
      for
        first <- service.upsert(input)
        _ = assertTrue(first.summary.revision > 0)
        restartRead <- new Memories(new PostgresMemoryStore(xa), embeddings).read(input.namespace, input.key)
        _ = assertEquals(first, restartRead)
        duplicate <- store.save(input, Vector(1f, 0f, 0f), embeddings.profile).attempt
        _ = assertEquals(Left(MemoryConflict), duplicate)
        createdRace <- Vector("created A", "created B").parTraverse(content =>
          store.save(input.copy(key = "concurrent-create", content = content), Vector(1f, 0f, 0f), embeddings.profile).attempt)
        _ = assertEquals(1, createdRace.count(_.isRight))
        createdWinner = createdRace.collectFirst { case Right(m) => m }.get
        _ <- service.delete(input.namespace, "concurrent-create", createdWinner.summary.revision)
        _ <- service.upsert(input.copy(namespace = "project-tuai", content = "隔离分组"))
        _ <- service.upsert(input.copy(key = "decision/database", kind = "decision", content = "使用 PostgreSQL"))
        scopes <- service.namespaces
        _ = assertEquals(Set("user-liyd", "project-tuai"), scopes.toSet)
        global <- service.search(MemorySearch("", "language", kind = Some("preference")), allNamespaces = true)
        _ = assertEquals(Set("user-liyd", "project-tuai"), global.hits.map(_.memory.summary.namespace).toSet)
        globalKeyword <- service.search(MemorySearch("", "test", mode = "keyword", kind = Some("preference")), allNamespaces = true)
        _ = assertEquals(Set("user-liyd", "project-tuai"), globalKeyword.hits.map(_.memory.summary.namespace).toSet)
        limited <- service.search(MemorySearch("", "language", topK = 1), allNamespaces = true)
        _ = assertEquals(1, limited.hits.size)
        preferences <- service.list(input.namespace, Some("preference"), 1, 0)
        _ = assertEquals(Vector(input.key), preferences.items.map(_.key))
        next <- service.list(input.namespace, Some("preference"), 1, 1)
        _ = assertTrue(next.items.isEmpty)
        keyword <- service.search(MemorySearch(input.namespace, "中文", mode = "keyword"))
        _ = assertEquals(Vector(first), keyword.hits.map(_.memory))
        _ = assertEquals(None, keyword.hits.head.score)
        literal <- service.search(MemorySearch(input.namespace, "%_", mode = "keyword"))
        _ = assertTrue(literal.hits.isEmpty)
        semantic <- service.search(MemorySearch(input.namespace, "语言偏好", kind = Some("preference")))
        _ = assertEquals(Vector(first), semantic.hits.map(_.memory))
        _ = assertEquals(1.0, semantic.hits.head.score.get, 0.00001)
        // Failed SQL must roll back all memory fields, revision and vector together.
        _ <- sql"ALTER TABLE tuai.codex_memory ADD CONSTRAINT reject_marker CHECK(content <> 'reject-me')".update.run.transact(xa)
        failure <- store.save(input.copy(title = "rejected title", content = "reject-me", expectedRevision = first.summary.revision),
          Vector(0f, 1f, 0f), embeddings.profile).attempt
        _ = assertEquals(Left(StorageFailure), failure)
        preserved <- service.read(input.namespace, input.key)
        _ = assertEquals(first, preserved)
        preservedVector <- service.search(MemorySearch(input.namespace, "语言", kind = Some("preference"), minScore = 0.9))
        _ = assertEquals(Vector(first), preservedVector.hits.map(_.memory))
        _ <- sql"ALTER TABLE tuai.codex_memory DROP CONSTRAINT reject_marker".update.run.transact(xa)
        // Direct store calls ensure the final atomic SQL revision check is exercised.
        raced <- Vector("correction A", "correction B").parTraverse(content =>
          store.save(input.copy(content = content, expectedRevision = first.summary.revision), Vector(1f, 0f, 0f), embeddings.profile).attempt)
        _ = assertEquals(1, raced.count(_.isRight))
        _ = assertEquals(Vector(MemoryConflict), raced.collect { case Left(e) => e })
        winner <- service.read(input.namespace, input.key)
        staleDelete <- service.delete(input.namespace, input.key, first.summary.revision).attempt
        _ = assertEquals(Left(MemoryConflict), staleDelete)
        wrongScope <- service.delete("other", input.key, winner.summary.revision).attempt
        _ = assertEquals(Left(MemoryConflict), wrongScope)
        _ <- service.delete(input.namespace, input.key, winner.summary.revision)
        recreated <- service.upsert(input)
        _ = assertTrue(recreated.summary.revision > winner.summary.revision)
        oldSession <- store.save(input.copy(expectedRevision = first.summary.revision), Vector(1f, 0f, 0f), embeddings.profile).attempt
        _ = assertEquals(Left(MemoryConflict), oldSession)
        // Changing the model profile hides old semantic vectors; keyword lookup still finds the record.
        _ <- sql"UPDATE tuai.codex_memory SET embedding_profile = 'old-profile' WHERE namespace = ${input.namespace} AND key = ${input.key}".update.run.transact(xa)
        hidden <- service.search(MemorySearch(input.namespace, "语言", kind = Some("preference")))
        _ = assertTrue(hidden.hits.isEmpty)
        visible <- service.search(MemorySearch(input.namespace, "中文", mode = "keyword"))
        _ = assertEquals(Vector(input.key), visible.hits.map(_.memory.summary.key))
        existing <- sql"SELECT content FROM tuai.rag_document WHERE namespace = 'existing'".query[String].unique.transact(xa)
        _ = assertEquals("preserved", existing)
        version <- sql"SELECT max(version::int) FROM tuai.flyway_schema_history WHERE success = true".query[Int].unique.transact(xa)
        _ = assertEquals(2, version)
      yield ()
    } }
  }.unsafeRunSync()
