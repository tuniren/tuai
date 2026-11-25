package com.tuniren.tuai.mcp

import cats.effect.IO
import io.circe.{Decoder, Json}
import io.circe.syntax.*

final case class McpTool(description: Json, execute: Json => IO[Json])

/** Both tool families use the same schema, argument decoding and dispatch rules. */
abstract class ToolSet:
  protected val string: Json = Json.obj("type" -> "string".asJson)
  protected val uuid: Json = string.deepMerge(Json.obj("format" -> "uuid".asJson))
  protected def integer(min: Int, max: Int): Json = Json.obj(
    "type" -> "integer".asJson, "minimum" -> min.asJson, "maximum" -> max.asJson)

  protected def tool(name: String, description: String, fields: Vector[(String, Json)], required: Vector[String],
                     readOnly: Boolean = true, destructive: Boolean = false, idempotent: Boolean = true)
                    (run: Json => IO[Json]): (String, McpTool) =
    name -> McpTool(Json.obj(
      "name" -> name.asJson, "description" -> description.asJson,
      "inputSchema" -> Json.obj("type" -> "object".asJson, "properties" -> Json.obj(fields*),
        "required" -> required.asJson),
      "annotations" -> Json.obj("readOnlyHint" -> readOnly.asJson, "destructiveHint" -> destructive.asJson,
        "idempotentHint" -> idempotent.asJson, "openWorldHint" -> false.asJson)
    ), run)

  protected def decode[A: Decoder](arguments: Json): IO[A] =
    IO.fromEither(arguments.as[A].left.map(_ => new IllegalArgumentException("Invalid or missing tool arguments")))

  protected def field[A: Decoder](arguments: Json, name: String): IO[A] =
    IO.fromEither(arguments.hcursor.get[A](name).left.map(_ => new IllegalArgumentException(s"Invalid or missing argument: $name")))

  protected def optional[A: Decoder](arguments: Json, name: String): IO[Option[A]] =
    IO.fromEither(arguments.hcursor.get[Option[A]](name).left.map(_ => new IllegalArgumentException(s"Invalid argument: $name")))

  protected def optionalInt(arguments: Json, name: String, default: Int): IO[Int] =
    optional[Int](arguments, name).map(_.getOrElse(default))

  protected def entries: Vector[(String, McpTool)]
  private lazy val registry = entries.toMap
  def descriptions: Vector[Json] = entries.map(_._2.description)
  def contains(name: String): Boolean = registry.contains(name)
  def call(name: String, arguments: Json): IO[Json] =
    IO.fromOption(registry.get(name))(new IllegalArgumentException("Unknown tool"))
      .flatMap(tool => IO.defer(tool.execute(arguments)))
