package com.tuniren.tuai.knowledge

final case class Page[T](items: Vector[T], limit: Int, offset: Int)
