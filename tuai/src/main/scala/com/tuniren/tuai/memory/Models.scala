package com.tuniren.tuai.memory

import io.circe.{Codec, Decoder}
import io.circe.generic.semiauto.*
import java.time.Instant

final case class MemoryInput(namespace: String, key: String, title: String, content: String,
                             kind: String, source: String, expectedRevision: Long)
final case class MemorySummary(namespace: String, key: String, title: String, kind: String, source: String,
                               revision: Long, createdAt: Instant, updatedAt: Instant)
final case class Memory(summary: MemorySummary, content: String)
final case class MemorySearch(namespace: String, query: String, mode: String = "semantic",
                              kind: Option[String] = None, topK: Int = 5, minScore: Double = 0)
final case class MemoryHit(memory: Memory, score: Option[Double])
final case class MemoryResults(namespace: String, query: String, mode: String, hits: Vector[MemoryHit])

sealed abstract class MemoryError(message: String) extends RuntimeException(message)
case object MissingMemory extends MemoryError("Memory not found in this namespace")
case object MemoryConflict extends MemoryError("Memory revision conflict. Read the current memory and reconcile before retrying; use expectedRevision=0 only to create a new key.")

object MemoryCodecs:
  given Codec.AsObject[MemoryInput] = deriveCodec
  given Codec.AsObject[MemorySummary] = deriveCodec
  given Codec.AsObject[Memory] = deriveCodec
  given Codec.AsObject[MemoryHit] = deriveCodec
  given Codec.AsObject[MemoryResults] = deriveCodec
  given Codec.AsObject[MemorySearch] = Codec.AsObject.from(
    Decoder.instance(c => for
      namespace <- c.get[String]("namespace")
      query <- c.get[String]("query")
      mode <- c.get[Option[String]]("mode")
      kind <- c.get[Option[String]]("kind")
      topK <- c.get[Option[Int]]("topK")
      minScore <- c.get[Option[Double]]("minScore")
    yield MemorySearch(namespace, query, mode.getOrElse("semantic"), kind, topK.getOrElse(5), minScore.getOrElse(0))), deriveEncoder)
