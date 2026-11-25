package com.tuniren.tuai.memory

import com.tuniren.tuai.knowledge.{BadInput, Validation}

object MemoryValidation:
  val kinds: Vector[String] = Vector("preference", "fact", "decision", "procedure", "context")

  private def check(valid: Boolean, detail: String): Either[BadInput, Unit] =
    Either.cond(valid, (), BadInput(detail))

  private def text(value: String, max: Int, field: String): Either[BadInput, Unit] =
    check(value != null && !value.isBlank && value.length <= max && !value.contains('\u0000'),
      s"$field must contain 1..$max characters and no NUL")

  def address(namespace: String, key: String): Either[BadInput, Unit] = for
    _ <- Validation.namespace(namespace)
    _ <- text(key, 256, "key")
  yield ()

  def kind(value: Option[String]): Either[BadInput, Unit] =
    check(value.forall(kinds.contains), s"kind must be one of ${kinds.mkString(", ")}")

  def revision(value: Long, create: Boolean): Either[BadInput, Unit] =
    check(value >= (if create then 0 else 1) && value < Long.MaxValue, "Invalid expectedRevision")

  def input(value: MemoryInput): Either[BadInput, MemoryInput] = for
    _ <- address(value.namespace, value.key)
    _ <- text(value.title, 256, "title")
    _ <- text(value.content, 8000, "content")
    _ <- text(value.source, 1024, "source")
    _ <- kind(Some(value.kind))
    _ <- revision(value.expectedRevision, create = true)
  yield value.copy(content = value.content.replace("\r\n", "\n").replace('\r', '\n'))

  def search(value: MemorySearch, allNamespaces: Boolean = false): Either[BadInput, MemorySearch] = for
    _ <- if allNamespaces then Right(()) else Validation.namespace(value.namespace)
    _ <- text(value.query, 8000, "query")
    _ <- kind(value.kind)
    _ <- check(Set("semantic", "keyword").contains(value.mode), "mode must be semantic or keyword")
    _ <- check(value.topK >= 1 && value.topK <= 20, "topK must be 1..20")
    _ <- check(java.lang.Double.isFinite(value.minScore) && value.minScore >= 0 && value.minScore <= 1,
      "minScore must be finite and 0..1")
    _ <- check(value.mode != "keyword" || value.minScore == 0, "minScore is only supported for semantic search")
  yield value
