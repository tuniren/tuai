package com.tuniren.tuai.http

import cats.data.Kleisli
import cats.effect.IO
import cats.syntax.all.*
import com.tuniren.tuai.knowledge.KnowledgeBase
import com.tuniren.tuai.memory.Memories
import com.tuniren.tuai.mcp.McpRoutes
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.http4s.{HttpRoutes, Request, Response, Status}
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.typelevel.ci.CIString

object HttpApp:
  private val maximumBodyBytes = 2 * 1024 * 1024

  def create(service: KnowledgeBase, memories: Memories, healthy: IO[Boolean], apiKey: String): org.http4s.HttpApp[IO] =
    val health = HttpRoutes.of[IO]:
      case GET -> Root / "health" => healthResponse(healthy)
    val routes = (health <+> new SearchRoutes(service, memories).routes <+> new LibraryRoutes(service).routes <+>
      new McpRoutes(service, memories).routes).orNotFound

    Kleisli: request =>
      val public = request.uri.path.renderString == "/health"
      val authorized = apiKey.isBlank || public || request.headers.get(CIString("Authorization")).exists(header =>
        MessageDigest.isEqual(header.head.value.getBytes(StandardCharsets.UTF_8), ("Bearer " + apiKey).getBytes(StandardCharsets.UTF_8)))
      val response = if !authorized then
        IO.pure(Response[IO](Status.Unauthorized).withEntity(problem(401, "Invalid or missing bearer token")))
      else
        request.body.take(maximumBodyBytes.toLong + 1).compile.toVector.flatMap: bytes =>
          if bytes.size > maximumBodyBytes then
            IO.pure(Response[IO](Status.PayloadTooLarge).withEntity(problem(413, "Request body exceeds 2 MiB")))
          else routes(request.withBodyStream(Stream.emits(bytes).covary[IO]))
      response.handleError: _ =>
        Response[IO](Status.InternalServerError).withEntity(problem(500, "Internal server error"))

  private def problem(status: Int, detail: String): Json =
    Json.obj("status" -> status.asJson, "detail" -> detail.asJson, "type" -> "about:blank".asJson)

  private def healthResponse(healthy: IO[Boolean]): IO[Response[IO]] = healthy.handleError(_ => false).map: up =>
    Response[IO](if up then Status.Ok else Status.ServiceUnavailable)
      .withEntity(Json.obj("status" -> (if up then "UP" else "DOWN").asJson))
