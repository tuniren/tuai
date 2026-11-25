package com.tuniren.tuai.knowledge

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import com.tuniren.tuai.knowledge.embedding.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.{HttpApp, Response, Status}
import org.http4s.circe.*
import org.http4s.client.Client
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import scala.concurrent.duration.*

class EmbeddingTest:
  private def config = EmbeddingConfig("http://localhost/v1", "secret", "test", 3, batchSize = 2)

  @Test
  def batchesReordersAndDoesNotExecuteUntilRun(): Unit = (for
    requests <- Ref.of[IO, Vector[Json]](Vector.empty)
    app = HttpApp[IO](request => for
      body <- request.as[Json]
      _ <- requests.update(_ :+ body)
      texts = body.hcursor.downField("input").focus.get.asArray.get
      data = texts.indices.reverse.map(i => Json.obj("index" -> i.asJson,
        "embedding" -> Vector(i.toFloat + 1, 1f, 0f).asJson))
    yield Response[IO](Status.Ok).withEntity(Json.obj("data" -> data.asJson)))
    embeddings = new OpenAiEmbeddings(Client.fromHttpApp(app), config)
    program = embeddings.embed(Vector("a", "b", "c"))
    before <- requests.get
    _ = assertTrue(before.isEmpty, "An IO value must not execute the HTTP request when constructed")
    vectors <- program
    seen <- requests.get
    _ = assertEquals(Vector(Vector(1f, 1f, 0f), Vector(2f, 1f, 0f), Vector(1f, 1f, 0f)), vectors)
    _ = assertEquals(2, seen.size)
    _ = assertEquals(3, seen.head.hcursor.get[Int]("dimensions").toOption.get)
  yield ()).unsafeRunSync()

  @Test
  def rejectsInvalidProviderVectorsAndRedactsErrors(): Unit =
    val bodies = Vector(
      """{"data":[{"index":0,"embedding":[1,0,0]},{"index":0,"embedding":[1,0,0]}]}""",
      """{"data":[{"index":0,"embedding":[1,0]},{"index":1,"embedding":[1,0]}]}""",
      """{"data":[{"index":0,"embedding":[0,0,0]},{"index":1,"embedding":[0,0,0]}]}"""
    )
    bodies.foreach: body =>
      val client = Client.fromHttpApp(HttpApp[IO](_ => IO.pure(Response[IO](Status.Ok).withEntity(body))))
      val result = new OpenAiEmbeddings(client, config).embed(Vector("a", "b")).attempt.unsafeRunSync()
      assertTrue(result.left.toOption.get.isInstanceOf[EmbeddingFailure])
    val denied = Client.fromHttpApp(HttpApp[IO](_ => IO.pure(Response[IO](Status.Unauthorized).withEntity("private provider response secret"))))
    val error = new OpenAiEmbeddings(denied, config).embed(Vector("a")).attempt.unsafeRunSync().left.toOption.get
    assertFalse(error.getMessage.contains("private provider response"))
    assertFalse(error.getMessage.contains("secret"))

  @Test
  def releasesResponseOnFailureAndTimesOut(): Unit = (for
    released <- Ref.of[IO, Boolean](false)
    client = Client[IO](_ => cats.effect.Resource.make(IO.pure(Response[IO](Status.Ok).withEntity("broken JSON")))(_ => released.set(true)))
    failed <- new OpenAiEmbeddings(client, config).embed(Vector("a")).attempt
    closed <- released.get
    _ = assertTrue(failed.isLeft)
    _ = assertTrue(closed)
    slow = Client[IO](_ => cats.effect.Resource.eval(IO.never[Response[IO]]))
    timed <- new OpenAiEmbeddings(slow, config.copy(timeout = 30.millis)).embed(Vector("a")).attempt
    _ = assertTrue(timed.left.toOption.get.isInstanceOf[EmbeddingFailure])
  yield ()).unsafeRunSync()
