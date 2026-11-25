package com.tuniren.tuai.mcp

import com.tuniren.tuai.knowledge.*
import com.tuniren.tuai.memory.{MissingMemory, MemoryConflict}

object ToolErrors:
  def message(error: Throwable): String = error match
    case error: BadInput => error.detail
    case MissingDocument => MissingDocument.getMessage
    case MissingMemory => MissingMemory.getMessage
    case MemoryConflict => MemoryConflict.getMessage
    case error: EmbeddingFailure => error.detail
    case StorageFailure => StorageFailure.getMessage
    case _ => "Internal server error"
