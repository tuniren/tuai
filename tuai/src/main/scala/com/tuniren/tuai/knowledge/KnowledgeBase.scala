package com.tuniren.tuai.knowledge

import cats.effect.IO
import com.tuniren.tuai.knowledge.Page
import com.tuniren.tuai.knowledge.chunking.{ChunkingConfig, TextChunker}
import com.tuniren.tuai.knowledge.documents.DocumentStore
import com.tuniren.tuai.knowledge.embedding.Embeddings
import java.util.UUID

/** Read top to bottom: validate pure values, compose effects, commit the completed index. */
final class KnowledgeBase(store: DocumentStore, embeddings: Embeddings, chunking: ChunkingConfig):
  def upsert(input: UpsertRequest): IO[UpsertResult] = for
    request <- IO.fromEither(Validation.upsert(input))
    hash = Validation.hash(chunking.fingerprint + "\u0000" + request.content + "\u0000" + request.title + "\u0000" + Validation.metadataJson(request.metadata))
    previous <- store.existing(request.namespace, request.source)
    result <- previous match
      case Some(doc) if doc.contentHash == hash && doc.embeddingProfile == embeddings.profile =>
        IO.pure(UpsertResult(doc.id, doc.chunks, unchanged = true))
      case _ =>
        for
          chunks <- IO.fromEither(TextChunker.split(request.content, chunking))
          _ <- IO.raiseWhen(chunks.isEmpty)(BadInput("content must contain non-whitespace text"))
          vectors <- embeddings.embed(chunks.map(_.content))
          _ <- IO.raiseWhen(vectors.size != chunks.size)(EmbeddingFailure("Each chunk must have an embedding"))
          index = DocumentIndex(request, hash, embeddings.model, embeddings.profile, chunks.zip(vectors).map(EmbeddedChunk.apply))
          id <- store.replace(index)
        yield UpsertResult(id, chunks.size, unchanged = false)
  yield result

  def search(input: SearchRequest, allNamespaces: Boolean = false): IO[SearchResult] = for
    request <- IO.fromEither(Validation.search(input, allNamespaces))
    vectors <- embeddings.embed(Vector(request.query))
    vector <- IO.fromOption(vectors.headOption)(EmbeddingFailure("Embedding provider returned no query vector"))
    hits <- store.search(request, vector, embeddings.profile, allNamespaces)
  yield SearchResult(request.namespace, request.query, hits)

  def keywordSearch(input: SearchRequest, allNamespaces: Boolean = false): IO[SearchResult] = for
    request <- IO.fromEither(Validation.search(input, allNamespaces))
    _ <- IO.raiseWhen(request.minScore != 0)(BadInput("minScore is only supported for semantic search"))
    hits <- store.keywordSearch(request, allNamespaces)
  yield SearchResult(request.namespace, request.query, hits)

  def list(namespace: String, limit: Int, offset: Int): IO[Page[DocumentSummary]] = for
    _ <- IO.fromEither(Validation.pagination(namespace, limit, offset))
    documents <- store.list(namespace, limit, offset)
  yield Page(documents, limit, offset)

  def get(namespace: String, id: UUID): IO[Document] = for
    _ <- IO.fromEither(Validation.namespace(namespace))
    document <- store.get(namespace, id).flatMap(IO.fromOption(_)(MissingDocument))
  yield document

  def namespaces: IO[Vector[NamespaceSummary]] = store.namespaces

  def browse(namespace: String, query: String, limit: Int, offset: Int): IO[DocumentPage] = for
    _ <- IO.fromEither(Validation.pagination(namespace, limit, offset))
    _ <- IO.raiseWhen(query.length > 1000 || query.contains('\u0000'))(BadInput("query must be at most 1000 characters and contain no NUL"))
    page <- store.browse(namespace, query.trim, limit, offset)
  yield page

  def resolve(namespace: String, source: String): IO[DocumentSummary] = for
    _ <- IO.fromEither(Validation.namespace(namespace))
    _ <- IO.raiseWhen(source.isBlank || source.length > 1024 || source.contains('\u0000'))(BadInput("Invalid document source"))
    document <- store.resolve(namespace, source).flatMap(IO.fromOption(_)(MissingDocument))
  yield document

  def delete(namespace: String, id: UUID): IO[Unit] = for
    _ <- IO.fromEither(Validation.namespace(namespace))
    deleted <- store.delete(namespace, id)
    _ <- IO.raiseUnless(deleted)(MissingDocument)
  yield ()

  def readLines(namespace: String, id: UUID, start: Int, end: Int): IO[DocumentLines] =
    get(namespace, id).flatMap(doc => IO.fromEither(Validation.readLines(doc, start, end)))
