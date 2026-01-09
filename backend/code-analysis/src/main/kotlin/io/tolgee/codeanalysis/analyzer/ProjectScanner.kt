package io.tolgee.codeanalysis.analyzer

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.io.path.relativeTo

/**
 * Scans the project to find all Kotlin source files and determine module structure.
 */
class ProjectScanner(private val projectRoot: File) {

    data class SourceFile(
        val file: File,
        val relativePath: String,
        val moduleName: String,
        val modulePath: String
    )

    data class ModuleDefinition(
        val name: String,
        val path: String,
        val sourceRoots: List<File>
    )

    // Known backend modules from settings.gradle
    private val moduleDefinitions = listOf(
        ModuleDefinition("server-app", "backend/app", listOf(File(projectRoot, "backend/app/src/main/kotlin"))),
        ModuleDefinition("data", "backend/data", listOf(File(projectRoot, "backend/data/src/main/kotlin"))),
        ModuleDefinition("api", "backend/api", listOf(File(projectRoot, "backend/api/src/main/kotlin"))),
        ModuleDefinition("security", "backend/security", listOf(File(projectRoot, "backend/security/src/main/kotlin"))),
        ModuleDefinition("testing", "backend/testing", listOf(File(projectRoot, "backend/testing/src/main/kotlin"))),
        ModuleDefinition("misc", "backend/misc", listOf(File(projectRoot, "backend/misc/src/main/kotlin"))),
        ModuleDefinition("development", "backend/development", listOf(File(projectRoot, "backend/development/src/main/kotlin"))),
        ModuleDefinition("ktlint", "backend/ktlint", listOf(File(projectRoot, "backend/ktlint/src/main/kotlin")))
    ).filter { it.sourceRoots.any { root -> root.exists() } }

    /**
     * Scans for all Kotlin source files in the project's backend modules.
     */
    fun scanKotlinFiles(): List<SourceFile> {
        val result = mutableListOf<SourceFile>()

        for (module in moduleDefinitions) {
            for (sourceRoot in module.sourceRoots) {
                if (!sourceRoot.exists()) continue

                Files.walk(sourceRoot.toPath())
                    .filter { it.isRegularFile() && it.name.endsWith(".kt") }
                    .forEach { path ->
                        result.add(
                            SourceFile(
                                file = path.toFile(),
                                relativePath = path.relativeTo(projectRoot.toPath()).pathString,
                                moduleName = module.name,
                                modulePath = module.path
                            )
                        )
                    }
            }
        }

        return result
    }

    /**
     * Gets all module definitions.
     */
    fun getModules(): List<ModuleDefinition> = moduleDefinitions

    /**
     * Extracts the package name from a Kotlin file by reading the package declaration.
     */
    fun extractPackageName(file: File): String? {
        return try {
            file.useLines { lines ->
                lines.firstOrNull { it.trim().startsWith("package ") }
                    ?.trim()
                    ?.removePrefix("package ")
                    ?.trim()
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /**
         * Quick check if a type is likely a project type based on package name.
         */
        fun isProjectPackage(packageName: String?): Boolean {
            if (packageName == null) return false
            return packageName.startsWith("io.tolgee")
        }
    }
}
