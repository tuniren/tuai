package com.tuniren.tuai.http

import cats.effect.IO
import com.tuniren.tuai.knowledge.*
import com.tuniren.tuai.knowledge.JsonCodecs.given
import io.circe.Json
import io.circe.syntax.*
import java.util.UUID
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.circe.*
import org.http4s.dsl.io.*

/** Public read endpoints for the library UI; MCP tools remain unchanged. */
final class LibraryRoutes(service: KnowledgeBase):
  private def parameter(request: Request[IO], name: String, default: String = ""): String =
    request.uri.query.params.getOrElse(name, default)

  private def integer(request: Request[IO], name: String, default: Int): IO[Int] =
    IO.fromOption(parameter(request, name, default.toString).toIntOption)(BadInput(s"$name must be an integer"))

  private def respond[A: io.circe.Encoder](action: IO[A]): IO[Response[IO]] =
    action.flatMap(value => Ok(value.asJson)).handleErrorWith:
      case BadInput(detail) => BadRequest(Json.obj("message" -> detail.asJson))
      case MissingDocument => NotFound(Json.obj("message" -> "Document not found in this namespace".asJson))
      case _ => ServiceUnavailable(Json.obj("message" -> "Knowledge library unavailable".asJson))

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO]:
    case GET -> Root / "api" / "namespaces" => respond(service.namespaces)
    case request @ GET -> Root / "api" / "documents" => respond:
      for
        limit <- integer(request, "limit", 20)
        offset <- integer(request, "offset", 0)
        page <- service.browse(parameter(request, "namespace"), parameter(request, "query"), limit, offset)
      yield page
    case request @ GET -> Root / "api" / "documents" / "resolve" =>
      respond(service.resolve(parameter(request, "namespace"), parameter(request, "source")))
    case request @ GET -> Root / "api" / "documents" / id => respond:
      for
        uuid <- IO.fromEither(scala.util.Try(UUID.fromString(id)).toEither.left.map(_ => BadInput("Invalid document ID")))
        document <- service.get(parameter(request, "namespace"), uuid)
      yield document.summary
