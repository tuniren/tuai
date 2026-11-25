package com.tuniren.tuai.http

import cats.effect.IO
import com.tuniren.tuai.knowledge.{BadInput, EmbeddingFailure, KnowledgeBase, SearchRequest}
import com.tuniren.tuai.memory.{Memories, MemorySearch}
import io.circe.{Encoder, Json}
import io.circe.generic.semiauto.*
import io.circe.syntax.*
import java.time.Instant
import java.util.UUID
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.circe.*
import org.http4s.dsl.io.*

final case class SearchItem(id: String, namespace: String, title: String, source: String, content: String, score: Option[Double],
                            documentId: Option[UUID] = None, startLine: Option[Int] = None, endLine: Option[Int] = None,
                            key: Option[String] = None, kind: Option[String] = None, revision: Option[Long] = None,
                            updatedAt: Option[Instant] = None)
final case class SearchResponse(namespace: Option[String], query: String, `type`: String, mode: String, limit: Int, items: Vector[SearchItem])

/** Read-only browser search; reuse the same validated services as MCP. */
final class SearchRoutes(knowledge: KnowledgeBase, memories: Memories):
  private given Encoder.AsObject[SearchItem] = deriveEncoder
  private given Encoder.AsObject[SearchResponse] = deriveEncoder

  private def parameter(request: Request[IO], name: String, default: String = ""): String =
    request.uri.query.params.getOrElse(name, default)

  private def search(request: Request[IO]): IO[SearchResponse] =
    val namespace = request.uri.query.params.get("namespace").filter(_.nonEmpty)
    val query = parameter(request, "q")
    val category = parameter(request, "type", "documents")
    val mode = parameter(request, "mode", "semantic")
    val kind = request.uri.query.params.get("kind").filter(_.nonEmpty)
    for
      _ <- IO.raiseUnless(Set("documents", "memories").contains(category))(BadInput("type must be documents or memories"))
      _ <- IO.raiseUnless(Set("semantic", "keyword").contains(mode))(BadInput("mode must be semantic or keyword"))
      limit <- IO.fromOption(parameter(request, "limit", "10").toIntOption)(BadInput("limit must be an integer"))
      _ <- IO.raiseUnless(limit >= 1 && limit <= 20)(BadInput("limit must be 1..20"))
      minScore <- IO.fromOption(parameter(request, "minScore", "0").toDoubleOption)(BadInput("minScore must be a number"))
      _ <- IO.raiseWhen(category == "documents" && kind.nonEmpty)(BadInput("kind is only supported for memories"))
      items <- if category == "documents" then
        val input = SearchRequest(namespace.getOrElse(""), query, limit, minScore)
        (if mode == "semantic" then knowledge.search(input, allNamespaces = namespace.isEmpty)
          else knowledge.keywordSearch(input, allNamespaces = namespace.isEmpty)).map(_.hits.map(hit =>
          SearchItem(hit.chunkId.toString, hit.namespace, hit.title, hit.source, hit.content,
            if mode == "semantic" then Some(hit.score) else None,
            documentId = Some(hit.documentId), startLine = Some(hit.startLine), endLine = Some(hit.endLine))))
      else
        memories.search(MemorySearch(namespace.getOrElse(""), query, mode, kind, limit, minScore),
          allNamespaces = namespace.isEmpty).map(_.hits.map: hit =>
          val summary = hit.memory.summary
          SearchItem(summary.key, summary.namespace, summary.title, summary.source, hit.memory.content, hit.score,
            key = Some(summary.key), kind = Some(summary.kind), revision = Some(summary.revision), updatedAt = Some(summary.updatedAt)))
    yield SearchResponse(namespace, query, category, mode, limit, items)

  private def respond[A: Encoder](action: IO[A]): IO[Response[IO]] = action.flatMap(value => Ok(value.asJson)).handleErrorWith:
    case BadInput(detail) => BadRequest(Json.obj("message" -> detail.asJson))
    case _: EmbeddingFailure => ServiceUnavailable(Json.obj("message" -> "语义检索服务暂时不可用，请重试或切换关键词搜索。".asJson))
    case _ => ServiceUnavailable(Json.obj("message" -> "检索服务暂时不可用，请稍后重试。".asJson))

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO]:
    case request @ GET -> Root / "api" / "search" => respond(search(request))
    case GET -> Root / "api" / "search" / "namespaces" => respond:
      for
        documents <- knowledge.namespaces
        memoryNamespaces <- memories.namespaces
      yield (documents.map(_.namespace) ++ memoryNamespaces).distinct.sorted
