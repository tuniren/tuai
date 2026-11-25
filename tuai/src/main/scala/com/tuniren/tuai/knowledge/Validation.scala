package com.tuniren.tuai.knowledge

import io.circe.{Json, Printer}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/** Pure functions: a result or a typed error, without network, database or runtime effects. */
object Validation:
  private def check(valid: Boolean, message: String): Either[BadInput, Unit] =
    Either.cond(valid, (), BadInput(message))

  private def text(value: String, maximum: Int, field: String): Either[BadInput, Unit] =
    check(value != null && !value.isBlank && value.length <= maximum && !value.contains('\u0000'),
      s"$field must contain 1..$maximum characters and no NUL")

  def namespace(value: String): Either[BadInput, Unit] = text(value, 128, "namespace")

  def upsert(input: UpsertRequest): Either[BadInput, UpsertRequest] = for
    _ <- namespace(input.namespace)
    _ <- text(input.source, 1024, "source")
    _ <- text(input.title, 512, "title")
    _ <- text(input.content, 400000, "content")
    metadata = metadataJson(input.metadata)
    _ <- check(metadata.length <= 16384 && !metadata.contains("\\u0000"), "metadata exceeds 16384 characters or contains NUL")
  yield input.copy(content = input.content.replace("\r\n", "\n").replace('\r', '\n'))

  def search(input: SearchRequest, allNamespaces: Boolean = false): Either[BadInput, SearchRequest] = for
    _ <- if allNamespaces then Right(()) else namespace(input.namespace)
    _ <- text(input.query, 8000, "query")
    _ <- check(input.topK >= 1 && input.topK <= 30, "topK must be 1..30")
    _ <- check(java.lang.Double.isFinite(input.minScore) && input.minScore >= 0 && input.minScore <= 1,
      "minScore must be finite and 0..1")
  yield input

  def pagination(scope: String, limit: Int, offset: Int): Either[BadInput, Unit] = for
    _ <- namespace(scope)
    _ <- check(limit >= 1 && limit <= 100 && offset >= 0, "limit must be 1..100 and offset >= 0")
  yield ()

  def readLines(document: Document, start: Int, end: Int): Either[BadInput, DocumentLines] =
    val lines = document.content.split("\n", -1)
    for
      _ <- check(start >= 1 && end >= start && end.toLong - start < 200, "Request 1..200 lines starting at line 1 or later")
      _ <- check(start <= lines.length, "startLine exceeds document length")
      last = math.min(end, lines.length)
      content = lines.slice(start - 1, last).mkString("\n")
      // Avoid cutting a UTF-16 surrogate pair at the response limit.
      cut = if content.length > 20000 && Character.isHighSurrogate(content(19999)) then 19999 else 20000
    yield DocumentLines(document.summary.id, document.summary.source, start, last, lines.length,
      content.take(cut), content.length > cut)

  def metadataJson(metadata: Map[String, Json]): String =
    Printer.noSpaces.copy(sortKeys = true).print(Json.obj(metadata.toSeq*))

  def hash(text: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))
