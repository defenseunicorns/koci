package com.defenseunicorns.koci.api.errors

/**
   * The repository component of a reference is invalid.
   *
   * @param repository The invalid repository value
   * @param reason Why the repository is invalid
   */
  class InvalidRepository(val repository: String, val reason: String) : KociError {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is InvalidRepository) return false
      if (repository != other.repository) return false
      return reason == other.reason
    }

    override fun hashCode(): Int {
      var result = repository.hashCode()
      result = 31 * result + reason.hashCode()
      return result
    }

    override fun toString(): String = "InvalidRepository(repository='$repository', reason='$reason')"
  }
