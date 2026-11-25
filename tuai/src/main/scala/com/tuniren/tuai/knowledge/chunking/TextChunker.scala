package com.tuniren.tuai.knowledge.chunking

import com.tuniren.tuai.knowledge.{BadInput, TextChunk}

final case class ChunkingConfig(size: Int = 1600, overlap: Int = 200):
  def validate: Either[BadInput, ChunkingConfig] =
    Either.cond(size >= 128 && size <= 8000 && overlap >= 0 && overlap < size,
      this, BadInput("chunk size must be 128..8000 and overlap smaller than size"))
  def fingerprint: String = s"$size:$overlap"

/** Unfold describes successive windows; scanLeft computes line numbers without mutable state. */
object TextChunker:
  def split(text: String, config: ChunkingConfig): Either[BadInput, Vector[TextChunk]] =
    config.validate.map: valid =>
      val lineAt = text.scanLeft(1)((line, c) => if c == '\n' then line + 1 else line)
      Iterator.unfold(0): start =>
        Option.when(start < text.length):
          val limit = math.min(start + valid.size, text.length)
          val newline = text.lastIndexOf('\n', limit - 1)
          val boundary = if limit < text.length && newline >= start + valid.size / 2 then newline + 1 else limit
          val end = if boundary < text.length && Character.isHighSurrogate(text(boundary - 1)) then boundary - 1 else boundary
          val overlapStart = math.max(start + 1, end - valid.overlap)
          val next = if end == text.length then end
            else if Character.isLowSurrogate(text(overlapStart)) then overlapStart + 1 else overlapStart
          ((start, end), next)
      .filter((start, end) => !text.substring(start, end).isBlank)
        .zipWithIndex.map { case ((start, end), ordinal) =>
          val content = text.substring(start, end)
          val last = if content.endsWith("\n") then end - 2 else end - 1
          TextChunk(ordinal, content, lineAt(start), lineAt(math.max(start, last)))
        }.toVector
