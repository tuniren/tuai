package com.tuniren.tuai

import cats.effect.{IO, Ref}
import com.tuniren.tuai.knowledge.*
import com.tuniren.tuai.knowledge.chunking.ChunkingConfig
import com.tuniren.tuai.knowledge.documents.DocumentStore
import com.tuniren.tuai.knowledge.embedding.Embeddings
import com.tuniren.tuai.memory.Memories
import java.time.Instant
import java.util.UUID

final case class Stored(id: UUID, index: DocumentIndex)
final class MemoryStore(state: Ref[IO, Map[(String, String), Stored]]) extends DocumentStore:
  override def existing(namespace: String, source: String): IO[Option[ExistingDocument]] =
    state.get.map(_.get((namespace, source)).map(s => ExistingDocument(s.id, s.index.contentHash, s.index.profile, s.index.chunks.size)))
  override def replace(index: DocumentIndex): IO[UUID] = IO(UUID.randomUUID()).flatMap: candidate =>
    state.modify: current =>
      val key = (index.request.namespace, index.request.source)
      val id = current.get(key).map(_.id).getOrElse(candidate)
      (current.updated(key, Stored(id, index)), id)
  private def summary(stored: Stored): DocumentSummary =
    val r = stored.index.request
    DocumentSummary(stored.id, r.namespace, r.source, r.title, r.metadata, stored.index.chunks.size, Instant.EPOCH)
  override def get(namespace: String, id: UUID): IO[Option[Document]] = state.get.map(_.values
    .find(s => s.id == id && s.index.request.namespace == namespace).map(s => Document(summary(s), s.index.request.content)))
  override def list(namespace: String, limit: Int, offset: Int): IO[Vector[DocumentSummary]] = state.get.map(_.values
    .filter(_.index.request.namespace == namespace).toVector.sortBy(_.index.request.source).slice(offset, offset + limit).map(summary))
  override def search(request: SearchRequest, vector: Vector[Float], profile: String, allNamespaces: Boolean = false): IO[Vector[SearchHit]] = state.get.map(_.values
    .filter(s => (allNamespaces || s.index.request.namespace == request.namespace) && s.index.profile == profile).toVector
    .flatMap(s => s.index.chunks.map(c => SearchHit(s.id, s.id, s.index.request.source, s.index.request.title,
      c.text.startLine, c.text.endLine, c.text.content, 1.0, s.index.request.metadata, s.index.request.namespace))).take(request.topK))
  override def keywordSearch(request: SearchRequest, allNamespaces: Boolean = false): IO[Vector[SearchHit]] = state.get.map(_.values.toVector
    .filter(s => allNamespaces || s.index.request.namespace == request.namespace).sortBy(_.index.request.source)
    .flatMap(s => s.index.chunks.filter(c => Vector(c.text.content, s.index.request.title, s.index.request.source)
      .exists(_.toLowerCase.contains(request.query.toLowerCase))).map(c =>
        SearchHit(s.id, s.id, s.index.request.source, s.index.request.title,
          c.text.startLine, c.text.endLine, c.text.content, 0, s.index.request.metadata, s.index.request.namespace))).take(request.topK))
  override def delete(namespace: String, id: UUID): IO[Boolean] = state.modify: current =>
    val key = current.collectFirst { case (key, value) if key._1 == namespace && value.id == id => key }
    (key.fold(current)(current - _), key.nonEmpty)
  override def healthy: IO[Boolean] = IO.pure(true)
  override def namespaces: IO[Vector[NamespaceSummary]] = state.get.map(_.values.toVector
    .groupBy(_.index.request.namespace).toVector.sortBy(_._1).map((namespace, docs) =>
      NamespaceSummary(namespace, docs.size.toLong, docs.count(s => markdown(s.index.request.source)).toLong)))
  private def markdown(source: String): Boolean = source.toLowerCase.endsWith(".md") || source.toLowerCase.endsWith(".markdown")
  override def browse(namespace: String, query: String, limit: Int, offset: Int): IO[DocumentPage] = state.get.map: current =>
    val matches = current.values.toVector.filter: s =>
      val r = s.index.request
      r.namespace == namespace && markdown(r.source) && Vector(r.title, r.source, r.content).exists(_.toLowerCase.contains(query.toLowerCase))
    val sorted = matches.sortBy(s => (if s.index.request.source.toLowerCase.matches("(.*/)?readme.md") then 0 else 1, s.index.request.source))
    DocumentPage(sorted.slice(offset, offset + limit).map(summary), sorted.size.toLong, limit, offset)
  override def resolve(namespace: String, source: String): IO[Option[DocumentSummary]] =
    state.get.map(_.get((namespace, source)).map(summary))

final case class TestFixture(store: MemoryStore, service: KnowledgeBase, calls: Ref[IO, Int], fail: Ref[IO, Boolean], memories: Memories)
object TestFixture:
  def create: IO[TestFixture] = for
    state <- Ref.of[IO, Map[(String, String), Stored]](Map.empty)
    calls <- Ref.of[IO, Int](0)
    fail <- Ref.of[IO, Boolean](false)
    store = new MemoryStore(state)
    embeddings = new Embeddings:
      val model = "test"
      val profile = "test-profile"
      def embed(texts: Vector[String]): IO[Vector[Vector[Float]]] = calls.update(_ + 1) *> fail.get.flatMap:
        case true => IO.raiseError(EmbeddingFailure("Provider unavailable"))
        case false => IO.pure(texts.map(_ => Vector(1f, 0f, 0f)))
    memoryStore <- TestMemoryStore.create
  yield TestFixture(store, new KnowledgeBase(store, embeddings, ChunkingConfig(128, 20)), calls, fail,
    new Memories(memoryStore, embeddings))
