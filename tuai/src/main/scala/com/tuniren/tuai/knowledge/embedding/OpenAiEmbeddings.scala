package com.tuniren.tuai.knowledge.embedding

import cats.effect.IO
import cats.syntax.all.*
import com.tuniren.tuai.knowledge.{EmbeddingFailure, Validation}
import io.circe.Json
import io.circe.syntax.*
import org.http4s.{Header, Method, Request, Uri}
import org.http4s.client.Client
import org.http4s.circe.*
import org.typelevel.ci.CIString
import scala.concurrent.duration.*

final case class EmbeddingConfig(baseUrl: String, apiKey: String, model: String, dimensions: Int,
                                  sendDimensions: Boolean = true, batchSize: Int = 32,
                                  timeout: FiniteDuration = 60.seconds):
  def validate: Either[String, EmbeddingConfig] =
    Either.cond(Uri.fromString(baseUrl).exists(u => u.scheme.exists(s => Set("http", "https").contains(s.value)) && u.host.nonEmpty)
      && model.nonEmpty && model.length <= 256 && dimensions >= 1 && dimensions <= 2000
      && batchSize >= 1 && batchSize <= 128 && timeout > Duration.Zero,
      this, "Invalid embedding configuration: dimensions 1..2000, batch 1..128 and HTTP(S) URL required")

final class OpenAiEmbeddings(client: Client[IO], config: EmbeddingConfig) extends Embeddings:
  override val model: String = config.model
  override val profile: String = Validation.hash(config.baseUrl.replaceAll("/+$", "") + "\n" + model + "\n" + config.dimensions)

  override def embed(texts: Vector[String]): IO[Vector[Vector[Float]]] =
    texts.grouped(config.batchSize).toVector.traverse(batch).map(_.flatten)

  private def batch(texts: Vector[String]): IO[Vector[Vector[Float]]] =
    val fields = Vector("model" -> model.asJson, "input" -> texts.asJson, "encoding_format" -> "float".asJson) ++
      Option.when(config.sendDimensions)("dimensions" -> config.dimensions.asJson)
    val program = for
      uri <- IO.fromEither(Uri.fromString(config.baseUrl.replaceAll("/+$", "") + "/embeddings"))
      request = Request[IO](Method.POST, uri).withEntity(Json.obj(fields*))
      authorized = if config.apiKey.isBlank then request
        else request.putHeaders(Header.Raw(CIString("Authorization"), "Bearer " + config.apiKey))
      json <- client.run(authorized).use: response =>
        if response.status.isSuccess then response.as[Json]
        else IO.raiseError(EmbeddingFailure("Embedding provider request failed; check availability and configuration"))
      vectors <- IO.fromEither(decode(json, texts.size))
    yield vectors
    program.timeout(config.timeout).handleErrorWith:
      case error: EmbeddingFailure => IO.raiseError(error)
      case _ => IO.raiseError(EmbeddingFailure("Embedding provider request failed; check availability and configuration"))

  private def decode(json: Json, expected: Int): Either[EmbeddingFailure, Vector[Vector[Float]]] =
    val invalid = EmbeddingFailure("Embedding provider returned invalid indexes, dimensions or vectors")
    for
      data <- json.hcursor.get[Vector[Json]]("data").leftMap(_ => invalid)
      items <- data.traverse(item => (item.hcursor.get[Int]("index"), item.hcursor.get[Vector[Float]]("embedding"))
        .mapN((index, vector) => (index, vector)).leftMap(_ => invalid))
      ordered = items.sortBy(_._1)
      _ <- Either.cond(ordered.map(_._1) == (0 until expected).toVector && ordered.forall { case (_, vector) =>
        vector.size == config.dimensions && vector.forall(java.lang.Float.isFinite) && vector.exists(_ != 0f)
      }, (), invalid)
    yield ordered.map(_._2)
