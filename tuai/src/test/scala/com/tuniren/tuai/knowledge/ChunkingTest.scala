package com.tuniren.tuai.knowledge

import com.tuniren.tuai.knowledge.chunking.*
import io.circe.parser.decode
import JsonCodecs.given
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ChunkingTest:
  @Test
  def coversLongLinesWithoutLosingTextOrSplittingEmoji(): Unit =
    val text = "abc😀中文".repeat(200)
    val chunks = TextChunker.split(text, ChunkingConfig(129, 0)).toOption.get
    assertTrue(chunks.size > 1)
    assertEquals(text, chunks.map(_.content).mkString)
    chunks.foreach: chunk =>
      assertFalse(Character.isLowSurrogate(chunk.content.head))
      assertFalse(Character.isHighSurrogate(chunk.content.last))
      assertEquals(1, chunk.startLine)
      assertEquals(1, chunk.endLine)

  @Test
  def tracksNormalizedLineNumbersAndOverlap(): Unit =
    val raw = (1 to 40).map(i => s"line $i: " + "x".repeat(30)).mkString("\r\n")
    val text = Validation.upsert(UpsertRequest("project", "a", "a", raw)).toOption.get.content
    val chunks = TextChunker.split(text, ChunkingConfig(160, 30)).toOption.get
    val end = chunks.foldLeft(0): (furthest, chunk) =>
      val at = text.indexOf(chunk.content, math.max(0, furthest - 30))
      assertTrue(at >= math.max(0, furthest - 30) && at <= furthest)
      assertEquals(text.take(at).count(_ == '\n') + 1, chunk.startLine)
      val last = at + chunk.content.length - (if chunk.content.endsWith("\n") then 2 else 1)
      assertEquals(text.take(last).count(_ == '\n') + 1, chunk.endLine)
      at + chunk.content.length
    assertEquals(text.length, end)

  @Test
  def rejectsInvalidRulesAndHandlesWhitespace(): Unit =
    assertTrue(TextChunker.split("text", ChunkingConfig(128, 128)).isLeft)
    assertEquals(Vector.empty, TextChunker.split(" \n\t", ChunkingConfig()).toOption.get)
    assertTrue(Validation.upsert(UpsertRequest("project", "a", "a", "bad\u0000text")).isLeft)
    assertTrue(Validation.search(SearchRequest(" ", "query")).isLeft)
    assertTrue(Validation.search(SearchRequest("project", "query", 31)).isLeft)
    assertTrue(Validation.search(SearchRequest("project", "query", 8, Double.NaN)).isLeft)

  @Test
  def jsonPreservesAbsentAndNullDefaults(): Unit =
    val absent = decode[SearchRequest]("""{"namespace":"p","query":"q"}""").toOption.get
    val nulled = decode[SearchRequest]("""{"namespace":"p","query":"q","topK":null,"minScore":null}""").toOption.get
    assertEquals(SearchRequest("p", "q"), absent)
    assertEquals(absent, nulled)
    assertEquals(Map.empty, decode[UpsertRequest]("""{"namespace":"p","source":"a","title":"a","content":"x"}""").toOption.get.metadata)
