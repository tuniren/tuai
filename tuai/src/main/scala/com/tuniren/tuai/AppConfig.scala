package com.tuniren.tuai

import cats.effect.IO
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port}
import com.tuniren.tuai.knowledge.chunking.ChunkingConfig
import com.tuniren.tuai.knowledge.embedding.EmbeddingConfig
import scala.concurrent.duration.*

final case class DatabaseConfig(url: String, username: String, password: String, poolSize: Int, migrate: Boolean)
final case class AppConfig(host: Host, port: Port, apiKey: String, database: DatabaseConfig,
                            embedding: EmbeddingConfig, chunking: ChunkingConfig)

object AppConfig:
  /** Environment access is delayed; parsing is pure and can be tested with an ordinary Map. */
  def load: IO[AppConfig] = IO(sys.env).flatMap(env => IO.fromEither(parse(env).leftMap(new IllegalArgumentException(_))))

  def parse(env: Map[String, String]): Either[String, AppConfig] =
    def value(name: String, default: String): String = env.getOrElse(name, default)
    def int(name: String, default: Int): Either[String, Int] = value(name, default.toString).toIntOption.toRight(s"$name must be an integer")
    def bool(name: String, default: Boolean): Either[String, Boolean] =
      value(name, default.toString).toBooleanOption.toRight(s"$name must be true or false")
    for
      host <- Host.fromString(value("SERVER_ADDRESS", "127.0.0.1")).toRight("Invalid SERVER_ADDRESS")
      number <- int("SERVER_PORT", 8080)
      port <- Port.fromInt(number).filter(_.value != 0).toRight("SERVER_PORT must be 1..65535")
      dimensions <- int("EMBEDDING_DIMENSIONS", 1536)
      batch <- int("EMBEDDING_BATCH_SIZE", 32)
      seconds <- int("EMBEDDING_TIMEOUT_SECONDS", 60)
      sendDimensions <- bool("EMBEDDING_SEND_DIMENSIONS", true)
      embedding <- EmbeddingConfig(value("EMBEDDING_BASE_URL", "https://api.openai.com/v1"),
        value("EMBEDDING_API_KEY", ""), value("EMBEDDING_MODEL", "text-embedding-3-small"), dimensions,
        sendDimensions, batch, seconds.seconds).validate
      size <- int("CHUNK_SIZE", 1600)
      overlap <- int("CHUNK_OVERLAP", 200)
      chunking <- ChunkingConfig(size, overlap).validate.leftMap(_.getMessage)
      pool <- int("DB_POOL_SIZE", 10)
      _ <- Either.cond(pool >= 1 && pool <= 100, (), "DB_POOL_SIZE must be 1..100")
      migrate <- bool("DB_MIGRATE", true)
      url = value("DB_URL", "jdbc:postgresql://192.168.6.162:30433/tuai")
      _ <- Either.cond(url.startsWith("jdbc:postgresql:"), (), "DB_URL must be a PostgreSQL JDBC URL")
    yield AppConfig(host, port, value("TUAI_API_KEY", ""),
      DatabaseConfig(url, value("DB_USERNAME", "postgres"), value("DB_PASSWORD", "123456"), pool, migrate), embedding, chunking)
