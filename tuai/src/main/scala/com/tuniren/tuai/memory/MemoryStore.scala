package com.tuniren.tuai.memory

import cats.effect.IO

trait MemoryStore:
  def get(namespace: String, key: String): IO[Option[Memory]]
  def save(input: MemoryInput, vector: Vector[Float], profile: String): IO[Memory]
  def list(namespace: String, kind: Option[String], limit: Int, offset: Int): IO[Vector[MemorySummary]]
  def search(input: MemorySearch, vector: Option[Vector[Float]], profile: String, allNamespaces: Boolean = false): IO[Vector[MemoryHit]]
  def namespaces: IO[Vector[String]]
  def delete(namespace: String, key: String, expectedRevision: Long): IO[Unit]
