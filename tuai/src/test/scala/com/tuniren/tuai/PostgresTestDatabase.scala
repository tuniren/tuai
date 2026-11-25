package com.tuniren.tuai

import cats.effect.{IO, Resource}
import java.net.URI
import java.sql.DriverManager
import java.util.UUID
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/** An opt-in external admin connection creates a disposable database; never migrate the admin database. */
object PostgresTestDatabase:
  def config: Resource[IO, AppConfig] =
    val database: Resource[IO, Map[String, String]] = sys.env.get("TUAI_TEST_POSTGRES_ADMIN_URL") match
      case None => Resource.make(IO.blocking:
        val db = new PostgreSQLContainer[Nothing](DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
        db.start()
        db
      )(db => IO.blocking(db.stop())).map(db => Map(
        "DB_URL" -> db.getJdbcUrl, "DB_USERNAME" -> db.getUsername, "DB_PASSWORD" -> db.getPassword))
      case Some(adminUrl) =>
        val user = sys.env.getOrElse("TUAI_TEST_POSTGRES_USERNAME", "postgres")
        val password = sys.env.getOrElse("TUAI_TEST_POSTGRES_PASSWORD",
          throw new IllegalArgumentException("TUAI_TEST_POSTGRES_PASSWORD is required for external integration tests"))
        val name = "tuai_memory_it_" + UUID.randomUUID().toString.replace("-", "")
        def execute(sql: String): IO[Unit] = IO.blocking:
          val connection = DriverManager.getConnection(adminUrl, user, password)
          try
            val statement = connection.createStatement()
            try
              statement.setQueryTimeout(30)
              statement.execute(sql)
              ()
            finally statement.close()
          finally connection.close()
        Resource.make(execute(s"CREATE DATABASE $name").as(name))(
          created => execute(s"DROP DATABASE $created WITH (FORCE)")).map: created =>
            val uri = URI.create(adminUrl.stripPrefix("jdbc:"))
            val url = "jdbc:" + new URI(uri.getScheme, uri.getAuthority, "/" + created, uri.getQuery, null).toString
            Map("DB_URL" -> url, "DB_USERNAME" -> user, "DB_PASSWORD" -> password)
    database.evalMap(env => IO.fromEither(AppConfig.parse(env ++ Map(
      "EMBEDDING_DIMENSIONS" -> "3", "CHUNK_SIZE" -> "128", "CHUNK_OVERLAP" -> "20"))
      .left.map(new IllegalArgumentException(_))))
