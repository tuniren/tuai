package com.tuniren.tuai.knowledge.embedding

import cats.effect.IO

trait Embeddings:
  def model: String
  def profile: String
  def embed(texts: Vector[String]): IO[Vector[Vector[Float]]]
