package org.tekfive.keep.migration

/**
 * Thrown when the migration list supplied to [MigrationRunner.run] is
 * structurally invalid (empty, duplicate versions, non-positive version).
 * Indicates a programming/deployment error, not a runtime failure.
 */
class MigrationConfigurationException(message: String) : RuntimeException(message)
