package com.tuniren.tuai.memory

import cats.effect.IO
import cats.syntax.all.*
import com.tuniren.tuai.knowledge.StorageFailure
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.sql.SQLException
import java.time.Instant

final class PostgresMemoryStore(xa: Transactor[IO]) extends MemoryStore:
  private type SummaryRow = (String, String, String, String, String, Long, Instant, Instant)
  private val columns = fr"namespace, key, title, kind, source, revision, created_at, updated_at"
  private def summary(row: SummaryRow): MemorySummary = MemorySummary.apply.tupled(row)
  private def memory(row: (SummaryRow, String)): Memory = Memory(summary(row._1), row._2)
  private def run[A](program: ConnectionIO[A]): IO[A] =
    program.transact(xa).adaptError { case _: SQLException => StorageFailure }

  override def get(namespace: String, key: String): IO[Option[Memory]] = run:
    (fr"SELECT" ++ columns ++ fr", content FROM tuai.codex_memory WHERE namespace = $namespace AND key = $key")
      .query[(SummaryRow, String)].option.map(_.map(memory))

  override def save(input: MemoryInput, vector: Vector[Float], profile: String): IO[Memory] = run:
    val literal = vector.mkString("[", ",", "]")
    val write = if input.expectedRevision == 0 then
      fr"""INSERT INTO tuai.codex_memory (namespace, key, title, content, kind, source, embedding_profile, embedding)
        VALUES (${input.namespace}, ${input.key}, ${input.title}, ${input.content}, ${input.kind}, ${input.source},
          $profile, $literal::public.vector) ON CONFLICT (namespace, key) DO NOTHING RETURNING""" ++ columns ++ fr", content"
    else
      fr"""UPDATE tuai.codex_memory SET title = ${input.title}, content = ${input.content}, kind = ${input.kind},
        source = ${input.source}, embedding_profile = $profile, embedding = $literal::public.vector,
        revision = nextval('tuai.codex_memory_revision'), updated_at = clock_timestamp()
        WHERE namespace = ${input.namespace} AND key = ${input.key} AND revision = ${input.expectedRevision}
        RETURNING""" ++ columns ++ fr", content"
    write.query[(SummaryRow, String)].option.flatMap:
      case Some(row) => memory(row).pure[ConnectionIO]
      case None => MemoryConflict.raiseError[ConnectionIO, Memory]

  override def list(namespace: String, kind: Option[String], limit: Int, offset: Int): IO[Vector[MemorySummary]] = run:
    (fr"SELECT" ++ columns ++ fr"FROM tuai.codex_memory WHERE namespace = $namespace" ++ kindFilter(kind) ++
      fr"ORDER BY updated_at DESC, key LIMIT $limit OFFSET $offset")
      .query[SummaryRow].to[Vector].map(_.map(summary))

  private def kindFilter(kind: Option[String]): Fragment = kind.fold(Fragment.empty)(value => fr"AND kind = $value")

  override def namespaces: IO[Vector[String]] = run:
    sql"SELECT DISTINCT namespace FROM tuai.codex_memory ORDER BY namespace".query[String].to[Vector]

  override def search(input: MemorySearch, vector: Option[Vector[Float]], profile: String, allNamespaces: Boolean = false): IO[Vector[MemoryHit]] =
    val scope = if allNamespaces then Fragment.empty else fr"AND namespace = ${input.namespace}"
    val base = fr"FROM tuai.codex_memory WHERE TRUE" ++ scope ++ kindFilter(input.kind)
    vector match
      case None => run:
        (fr"SELECT" ++ columns ++ fr", content" ++ base ++
          fr"""AND (strpos(lower(key), lower(${input.query})) > 0 OR strpos(lower(title), lower(${input.query})) > 0
            OR strpos(lower(content), lower(${input.query})) > 0 OR strpos(lower(source), lower(${input.query})) > 0)
            ORDER BY updated_at DESC, namespace, key LIMIT ${input.topK}""")
          .query[(SummaryRow, String)].to[Vector].map(_.map(row => MemoryHit(memory(row), None)))
      case Some(value) =>
        val literal = value.mkString("[", ",", "]")
        run:
          (fr"SELECT" ++ columns ++ fr", content, 1 - (embedding <=> $literal::public.vector)" ++ base ++
            fr"""AND embedding_profile = $profile AND 1 - (embedding <=> $literal::public.vector) >= ${input.minScore}
              ORDER BY embedding <=> $literal::public.vector, namespace, key LIMIT ${input.topK}""")
            .query[(SummaryRow, String, Double)].to[Vector].map(_.map((row, content, score) =>
              MemoryHit(Memory(summary(row), content), Some(math.max(-1, math.min(1, score))))))

  override def delete(namespace: String, key: String, expectedRevision: Long): IO[Unit] = run:
    sql"DELETE FROM tuai.codex_memory WHERE namespace = $namespace AND key = $key AND revision = $expectedRevision"
      .update.run.flatMap(count => if count == 1 then ().pure[ConnectionIO] else MemoryConflict.raiseError[ConnectionIO, Unit])
