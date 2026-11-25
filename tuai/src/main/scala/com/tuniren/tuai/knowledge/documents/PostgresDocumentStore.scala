package com.tuniren.tuai.knowledge.documents

import cats.effect.IO
import cats.syntax.all.*
import com.tuniren.tuai.knowledge.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json
import io.circe.parser.decode
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/** SQL values describe a ConnectionIO program. Only transact executes it and commits or rolls back. */
final class PostgresDocumentStore(xa: Transactor[IO]) extends DocumentStore:
  private type SummaryRow = (UUID, String, String, String, String, Int, Instant)
  private type ChunkRow = (UUID, UUID, Int, String, Int, Int, String)
  private val columns = fr"d.id, d.namespace, d.source, d.title, d.metadata::text, (SELECT count(*)::int FROM tuai.rag_chunk c WHERE c.document_id = d.id), d.updated_at"

  private def run[A](program: ConnectionIO[A]): IO[A] =
    program.transact(xa).adaptError { case _: SQLException => StorageFailure }

  override def existing(namespace: String, source: String): IO[Option[ExistingDocument]] = run:
    sql"""SELECT d.id, d.content_hash, d.embedding_profile,
      (SELECT count(*)::int FROM tuai.rag_chunk c WHERE c.document_id = d.id)
      FROM tuai.rag_document d WHERE namespace = $namespace AND source = $source"""
      .query[(UUID, String, String, Int)].option.map(_.map(ExistingDocument.apply.tupled))

  override def replace(index: DocumentIndex): IO[UUID] = for
    candidate <- IO(UUID.randomUUID())
    chunkIds <- IO(index.chunks.map(_ => UUID.randomUUID()))
    id <- run:
      val request = index.request
      val metadata = Validation.metadataJson(request.metadata)
      for
        id <- sql"""INSERT INTO tuai.rag_document
          (id, namespace, source, title, content, content_hash, embedding_model, embedding_profile, metadata)
          VALUES ($candidate, ${request.namespace}, ${request.source}, ${request.title}, ${request.content},
            ${index.contentHash}, ${index.model}, ${index.profile}, $metadata::jsonb)
          ON CONFLICT (namespace, source) DO UPDATE SET
            title = EXCLUDED.title, content = EXCLUDED.content, content_hash = EXCLUDED.content_hash,
            embedding_model = EXCLUDED.embedding_model, embedding_profile = EXCLUDED.embedding_profile,
            metadata = EXCLUDED.metadata, updated_at = now() RETURNING id""".query[UUID].unique
        _ <- replaceChunks(id, index.chunks.zip(chunkIds).map { case (chunk, chunkId) =>
          (chunkId, id, chunk.text.ordinal, chunk.text.content, chunk.text.startLine, chunk.text.endLine,
            chunk.embedding.mkString("[", ",", "]"))
        })
      yield id
  yield id

  /** Package-visible so a database test can verify the actual chunk write transaction independently. */
  private[knowledge] def replaceChunks(id: UUID, rows: Vector[ChunkRow]): ConnectionIO[Unit] = for
    _ <- sql"DELETE FROM tuai.rag_chunk WHERE document_id = $id".update.run
    _ <- Update[ChunkRow]("""INSERT INTO tuai.rag_chunk
      (id, document_id, ordinal, content, start_line, end_line, embedding) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS public.vector))""")
      .updateMany(rows)
  yield ()

  private def namespaceFilter(namespace: String, allNamespaces: Boolean): Fragment =
    if allNamespaces then Fragment.empty else fr"AND d.namespace = $namespace"

  override def search(request: SearchRequest, vector: Vector[Float], profile: String, allNamespaces: Boolean = false): IO[Vector[SearchHit]] =
    val literal = vector.mkString("[", ",", "]")
    run((fr"""SELECT c.id, d.id, d.source, d.title, c.start_line, c.end_line, c.content,
      1 - (c.embedding <=> $literal::public.vector) AS score, d.metadata::text, d.namespace
      FROM tuai.rag_chunk c JOIN tuai.rag_document d ON d.id = c.document_id
      WHERE d.embedding_profile = $profile""" ++ namespaceFilter(request.namespace, allNamespaces) ++ fr"""
        AND 1 - (c.embedding <=> $literal::public.vector) >= ${request.minScore}
      ORDER BY c.embedding <=> $literal::public.vector, c.id LIMIT ${request.topK}""")
      .query[(UUID, UUID, String, String, Int, Int, String, Double, String, String)].to[Vector]
      .map(_.map { case (chunkId, id, source, title, start, end, content, score, metadata, namespace) =>
        SearchHit(chunkId, id, source, title, start, end, content, math.max(-1, math.min(1, score)), parseMetadata(metadata), namespace)
      }))

  override def keywordSearch(request: SearchRequest, allNamespaces: Boolean = false): IO[Vector[SearchHit]] = run:
    (fr"""SELECT c.id, d.id, d.source, d.title, c.start_line, c.end_line, c.content, d.metadata::text, d.namespace
      FROM tuai.rag_chunk c JOIN tuai.rag_document d ON d.id = c.document_id
      WHERE TRUE""" ++ namespaceFilter(request.namespace, allNamespaces) ++ fr"""AND (
        strpos(lower(c.content), lower(${request.query})) > 0 OR strpos(lower(d.title), lower(${request.query})) > 0
        OR strpos(lower(d.source), lower(${request.query})) > 0)
      ORDER BY d.source COLLATE "C", d.namespace COLLATE "C", c.ordinal, c.id LIMIT ${request.topK}""")
      .query[(UUID, UUID, String, String, Int, Int, String, String, String)].to[Vector]
      .map(_.map { case (chunkId, id, source, title, start, end, content, metadata, namespace) =>
        SearchHit(chunkId, id, source, title, start, end, content, 0, parseMetadata(metadata), namespace)
      })

  override def list(namespace: String, limit: Int, offset: Int): IO[Vector[DocumentSummary]] = run:
    (fr"SELECT" ++ columns ++ fr"FROM tuai.rag_document d WHERE d.namespace = $namespace ORDER BY d.updated_at DESC, d.id LIMIT $limit OFFSET $offset")
      .query[SummaryRow].to[Vector].map(_.map(summary))

  override def get(namespace: String, id: UUID): IO[Option[Document]] = run:
    (fr"SELECT" ++ columns ++ fr", d.content FROM tuai.rag_document d WHERE d.namespace = $namespace AND d.id = $id")
      .query[(SummaryRow, String)].option.map(_.map((row, content) => Document(summary(row), content)))

  private val markdown = fr"(lower(d.source) LIKE '%.md' OR lower(d.source) LIKE '%.markdown')"

  override def namespaces: IO[Vector[NamespaceSummary]] = run:
    (fr"SELECT d.namespace, count(*), count(*) FILTER (WHERE" ++ markdown ++
      fr""") FROM tuai.rag_document d GROUP BY d.namespace ORDER BY d.namespace COLLATE "C"""")
      .query[(String, Long, Long)].to[Vector].map(_.map(NamespaceSummary.apply.tupled))

  override def browse(namespace: String, query: String, limit: Int, offset: Int): IO[DocumentPage] = run:
    val filter = fr"FROM tuai.rag_document d WHERE d.namespace = $namespace AND" ++ markdown ++
      fr"""AND ($query = '' OR strpos(lower(d.title), lower($query)) > 0
        OR strpos(lower(d.source), lower($query)) > 0 OR strpos(lower(d.content), lower($query)) > 0)"""
    for
      // The transaction snapshot keeps the count and page consistent during concurrent imports.
      _ <- sql"SET TRANSACTION ISOLATION LEVEL REPEATABLE READ".update.run
      total <- (fr"SELECT count(*)" ++ filter).query[Long].unique
      items <- (fr"SELECT" ++ columns ++ filter ++
        fr"""ORDER BY CASE WHEN lower(d.source) = 'readme.md' OR lower(d.source) LIKE '%/readme.md' THEN 0 ELSE 1 END,
          d.source COLLATE "C", d.id LIMIT $limit OFFSET $offset""")
        .query[SummaryRow].to[Vector].map(_.map(summary))
    yield DocumentPage(items, total, limit, offset)

  override def resolve(namespace: String, source: String): IO[Option[DocumentSummary]] = run:
    (fr"SELECT" ++ columns ++ fr"FROM tuai.rag_document d WHERE d.namespace = $namespace AND d.source = $source")
      .query[SummaryRow].option.map(_.map(summary))

  override def delete(namespace: String, id: UUID): IO[Boolean] =
    run(sql"DELETE FROM tuai.rag_document WHERE namespace = $namespace AND id = $id".update.run.map(_ > 0))

  override def healthy: IO[Boolean] = run(sql"SELECT 1".query[Int].unique.map(_ == 1)).handleError(_ => false)

  private def summary(row: SummaryRow): DocumentSummary =
    val (id, namespace, source, title, metadata, chunks, updated) = row
    DocumentSummary(id, namespace, source, title, parseMetadata(metadata), chunks, updated)

  private def parseMetadata(value: String): Map[String, Json] =
    decode[Map[String, Json]](value).fold(_ => throw StorageFailure, identity)
