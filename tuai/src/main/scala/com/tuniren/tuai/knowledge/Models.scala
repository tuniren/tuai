package com.tuniren.tuai.knowledge

import io.circe.{Codec, Decoder, Encoder, Json}
import io.circe.generic.semiauto.*
import java.time.Instant
import java.util.UUID

final case class UpsertRequest(namespace: String, source: String, title: String, content: String,
                               metadata: Map[String, Json] = Map.empty)
final case class SearchRequest(namespace: String, query: String, topK: Int = 8, minScore: Double = 0)
final case class UpsertResult(documentId: UUID, chunks: Int, unchanged: Boolean)
final case class TextChunk(ordinal: Int, content: String, startLine: Int, endLine: Int)
final case class DocumentSummary(id: UUID, namespace: String, source: String, title: String,
                                 metadata: Map[String, Json], chunks: Int, updatedAt: Instant)
final case class Document(summary: DocumentSummary, content: String)
final case class NamespaceSummary(namespace: String, documents: Long, markdownDocuments: Long)
final case class DocumentPage(items: Vector[DocumentSummary], total: Long, limit: Int, offset: Int)
final case class SearchHit(chunkId: UUID, documentId: UUID, source: String, title: String,
                           startLine: Int, endLine: Int, content: String, score: Double,
                           metadata: Map[String, Json], namespace: String)
final case class SearchResult(namespace: String, query: String, hits: Vector[SearchHit])
final case class ExistingDocument(id: UUID, contentHash: String, embeddingProfile: String, chunks: Int)
final case class EmbeddedChunk(text: TextChunk, embedding: Vector[Float])
final case class DocumentIndex(request: UpsertRequest, contentHash: String, model: String,
                               profile: String, chunks: Vector[EmbeddedChunk])
final case class DocumentLines(documentId: UUID, source: String, startLine: Int, endLine: Int,
                                totalLines: Int, content: String, truncated: Boolean)

sealed abstract class KnowledgeError(message: String) extends RuntimeException(message)
final case class BadInput(detail: String) extends KnowledgeError(detail)
case object MissingDocument extends KnowledgeError("Document not found in this namespace")
final case class EmbeddingFailure(detail: String) extends KnowledgeError(detail)
case object StorageFailure extends KnowledgeError("Database operation unavailable")

/** Explicit defaults preserve the original API, including optional fields sent as null. */
object JsonCodecs:
  given Codec.AsObject[UpsertRequest] = Codec.AsObject.from(
    Decoder.instance(c => for
      namespace <- c.get[String]("namespace")
      source <- c.get[String]("source")
      title <- c.get[String]("title")
      content <- c.get[String]("content")
      metadata <- c.get[Option[Map[String, Json]]]("metadata")
    yield UpsertRequest(namespace, source, title, content, metadata.getOrElse(Map.empty))), deriveEncoder)
  given Codec.AsObject[SearchRequest] = Codec.AsObject.from(
    Decoder.instance(c => for
      namespace <- c.get[String]("namespace")
      query <- c.get[String]("query")
      topK <- c.get[Option[Int]]("topK")
      minScore <- c.get[Option[Double]]("minScore")
    yield SearchRequest(namespace, query, topK.getOrElse(8), minScore.getOrElse(0))), deriveEncoder)
  given Codec.AsObject[UpsertResult] = deriveCodec
  given Codec.AsObject[DocumentSummary] = deriveCodec
  given Codec.AsObject[Document] = deriveCodec
  given Codec.AsObject[NamespaceSummary] = deriveCodec
  given Codec.AsObject[DocumentPage] = deriveCodec
  given Codec.AsObject[SearchHit] = deriveCodec
  given Codec.AsObject[SearchResult] = deriveCodec
  given Codec.AsObject[DocumentLines] = deriveCodec
  given [T: Encoder: Decoder]: Codec.AsObject[com.tuniren.tuai.knowledge.Page[T]] = deriveCodec
