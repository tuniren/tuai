package com.tuniren.tuai

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ConfigTest:
  @Test
  def parsesDefaultsAndRejectsInvalidConfiguration(): Unit =
    val parsed = AppConfig.parse(Map.empty)
    assertTrue(parsed.isRight, parsed.toString)
    val config = parsed.toOption.get
    assertEquals("jdbc:postgresql://192.168.6.162:30433/tuai", config.database.url)
    assertEquals(1536, config.embedding.dimensions)
    assertEquals(1600, config.chunking.size)
    assertEquals("127.0.0.1", config.host.toString)
    Vector(Map("EMBEDDING_DIMENSIONS" -> "0"), Map("SERVER_PORT" -> "abc"),
      Map("CHUNK_SIZE" -> "128", "CHUNK_OVERLAP" -> "128"), Map("DB_POOL_SIZE" -> "0"),
      Map("EMBEDDING_SEND_DIMENSIONS" -> "maybe"), Map("DB_URL" -> "jdbc:mysql://localhost/a"))
      .foreach(env => assertTrue(AppConfig.parse(env).isLeft, env.toString))

