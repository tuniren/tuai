package com.tuniren.tuai

import cats.effect.{IO, Ref}
import com.tuniren.tuai.memory.*
import java.time.Instant

/** Protocol tests only; PostgreSQL tests exercise the production SQL separately. */
final class TestMemoryStore(state: Ref[IO, (Long, Map[(String, String), (Memory, String)])]) extends MemoryStore:
  override def get(namespace: String, key: String): IO[Option[Memory]] = state.get.map(_._2.get((namespace, key)).map(_._1))

  override def save(input: MemoryInput, vector: Vector[Float], profile: String): IO[Memory] = state.modify { (serial, current) =>
    val key = (input.namespace, input.key)
    val previous = current.get(key).map(_._1)
    if previous.fold(input.expectedRevision != 0)(_.summary.revision != input.expectedRevision) then
      ((serial, current), Left(MemoryConflict))
    else
      val timestamp = Instant.EPOCH.plusSeconds(serial + 1)
      val saved = Memory(MemorySummary(input.namespace, input.key, input.title, input.kind, input.source, serial + 1,
        previous.fold(timestamp)(_.summary.createdAt), timestamp), input.content)
      ((serial + 1, current.updated(key, (saved, profile))), Right(saved))
  }.flatMap(IO.fromEither)

  override def list(namespace: String, kind: Option[String], limit: Int, offset: Int): IO[Vector[MemorySummary]] =
    state.get.map(_._2.values.toVector.map(_._1.summary)
      .filter(m => m.namespace == namespace && kind.forall(_ == m.kind))
      .sortBy(m => (-m.revision, m.key)).drop(offset).take(limit))

  override def search(input: MemorySearch, vector: Option[Vector[Float]], profile: String, allNamespaces: Boolean = false): IO[Vector[MemoryHit]] =
    state.get.map(_._2.values.toVector.filter { (memory, storedProfile) =>
      val m = memory.summary
      (allNamespaces || m.namespace == input.namespace) && input.kind.forall(_ == m.kind) &&
        (if vector.nonEmpty then storedProfile == profile else
          Vector(m.key, m.title, memory.content, m.source).exists(_.toLowerCase.contains(input.query.toLowerCase)))
    }.sortBy(-_._1.summary.revision).take(input.topK).map((m, _) => MemoryHit(m, vector.map(_ => 1.0))))

  override def namespaces: IO[Vector[String]] = state.get.map(_._2.keys.map(_._1).toVector.distinct.sorted)

  override def delete(namespace: String, key: String, expectedRevision: Long): IO[Unit] = state.modify { (serial, current) =>
    val address = (namespace, key)
    if current.get(address).exists(_._1.summary.revision == expectedRevision) then
      ((serial, current - address), Right(()))
    else ((serial, current), Left(MemoryConflict))
  }.flatMap(IO.fromEither)

object TestMemoryStore:
  def create: IO[TestMemoryStore] = Ref.of[IO, (Long, Map[(String, String), (Memory, String)])]((0L, Map.empty)).map(new TestMemoryStore(_))
