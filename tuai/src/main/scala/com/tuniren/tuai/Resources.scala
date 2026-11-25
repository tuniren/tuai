package com.tuniren.tuai

import cats.effect.{IO, Resource}
import com.zaxxer.hikari.HikariConfig
import doobie.hikari.HikariTransactor
import org.flywaydb.core.Flyway

object Resources:
  def database(config: AppConfig): Resource[IO, HikariTransactor[IO]] = for
    settings <- Resource.eval(IO.blocking:
      val hikari = new HikariConfig()
      hikari.setJdbcUrl(config.database.url)
      hikari.setUsername(config.database.username)
      hikari.setPassword(config.database.password)
      hikari.setMaximumPoolSize(config.database.poolSize)
      hikari.setConnectionTimeout(10000)
      hikari.setPoolName("tuai-postgres")
      hikari
    )
    xa <- HikariTransactor.fromHikariConfig[IO](settings)
    _ <- Resource.eval(IO.whenA(config.database.migrate)(IO.blocking:
      Flyway.configure().dataSource(xa.kernel).defaultSchema("tuai").schemas("tuai")
        .placeholders(java.util.Map.of("embeddingDimensions", config.embedding.dimensions.toString))
        .load().migrate()
    ).void)
  yield xa
