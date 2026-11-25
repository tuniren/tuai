package com.tuniren.tuai.knowledge.documents

import cats.effect.IO
import com.tuniren.tuai.knowledge.*
import java.util.UUID

/** A small effectful boundary makes use cases testable without a database. */
trait DocumentStore:
  def existing(namespace: String, source: String): IO[Option[ExistingDocument]]
  def replace(index: DocumentIndex): IO[UUID]
  def search(request: SearchRequest, vector: Vector[Float], profile: String, allNamespaces: Boolean = false): IO[Vector[SearchHit]]
  def keywordSearch(request: SearchRequest, allNamespaces: Boolean = false): IO[Vector[SearchHit]]
  def list(namespace: String, limit: Int, offset: Int): IO[Vector[DocumentSummary]]
  def namespaces: IO[Vector[NamespaceSummary]]
  def browse(namespace: String, query: String, limit: Int, offset: Int): IO[DocumentPage]
  def resolve(namespace: String, source: String): IO[Option[DocumentSummary]]
  def get(namespace: String, id: UUID): IO[Option[Document]]
  def delete(namespace: String, id: UUID): IO[Boolean]
  def healthy: IO[Boolean]
