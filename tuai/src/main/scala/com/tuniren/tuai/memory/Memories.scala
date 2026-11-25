package com.tuniren.tuai.memory

import cats.effect.IO
import com.tuniren.tuai.knowledge.{EmbeddingFailure, Page, Validation}
import com.tuniren.tuai.knowledge.embedding.Embeddings

/** Durable facts and preferences, separate from imported knowledge documents. */
final class Memories(store: MemoryStore, embeddings: Embeddings):
  private def embed(text: String): IO[Vector[Float]] = for
    vectors <- embeddings.embed(Vector(text))
    vector <- IO.fromOption(vectors.headOption.filter(_ => vectors.size == 1))(
      EmbeddingFailure("Each memory or query must have exactly one embedding"))
    _ <- IO.raiseWhen(vector.isEmpty || vector.exists(v => !java.lang.Float.isFinite(v)) || !vector.exists(_ != 0f))(
      EmbeddingFailure("Invalid memory embedding"))
  yield vector

  def upsert(input: MemoryInput): IO[Memory] = for
    request <- IO.fromEither(MemoryValidation.input(input))
    previous <- store.get(request.namespace, request.key)
    _ <- IO.raiseUnless(previous.fold(request.expectedRevision == 0)(_.summary.revision == request.expectedRevision))(MemoryConflict)
    vector <- embed(s"${request.title}\n${request.content}")
    // The store checks the revision again atomically after the network call.
    saved <- store.save(request, vector, embeddings.profile)
  yield saved

  def read(namespace: String, key: String): IO[Memory] = for
    _ <- IO.fromEither(MemoryValidation.address(namespace, key))
    memory <- store.get(namespace, key).flatMap(IO.fromOption(_)(MissingMemory))
  yield memory

  def list(namespace: String, kind: Option[String], limit: Int, offset: Int): IO[Page[MemorySummary]] = for
    _ <- IO.fromEither(Validation.pagination(namespace, limit, offset))
    _ <- IO.fromEither(MemoryValidation.kind(kind))
    items <- store.list(namespace, kind, limit, offset)
  yield Page(items, limit, offset)

  def namespaces: IO[Vector[String]] = store.namespaces

  def search(input: MemorySearch, allNamespaces: Boolean = false): IO[MemoryResults] = for
    request <- IO.fromEither(MemoryValidation.search(input, allNamespaces))
    vector <- if request.mode == "semantic" then embed(request.query).map(Some(_)) else IO.pure(None)
    hits <- store.search(request, vector, embeddings.profile, allNamespaces)
  yield MemoryResults(request.namespace, request.query, request.mode, hits)

  def delete(namespace: String, key: String, expectedRevision: Long): IO[Unit] = for
    _ <- IO.fromEither(MemoryValidation.address(namespace, key))
    _ <- IO.fromEither(MemoryValidation.revision(expectedRevision, create = false))
    _ <- store.delete(namespace, key, expectedRevision)
  yield ()
