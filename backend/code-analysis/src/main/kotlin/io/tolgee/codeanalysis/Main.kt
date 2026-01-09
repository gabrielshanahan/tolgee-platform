package io.tolgee.codeanalysis

import io.tolgee.codeanalysis.analyzer.ProjectAnalyzer
import io.tolgee.codeanalysis.output.JsonOutputWriter
import java.io.File
import kotlin.system.exitProcess
import kotlin.system.measureTimeMillis

/**
 * Main entry point for the code analysis tool.
 *
 * Usage:
 *   ./gradlew :code-analysis:analyzeCode
 *   or
 *   ./gradlew :code-analysis:run --args="/path/to/project"
 *
 * The output will be written to build/code-analysis/ by default,
 * or to the directory specified by the output.dir system property.
 */
fun main(args: Array<String>) {
    println("=".repeat(60))
    println("Tolgee Code Analysis Tool")
    println("=".repeat(60))

    // Get project root from args or current directory
    val projectRoot = if (args.isNotEmpty()) {
        File(args[0])
    } else {
        File(System.getProperty("user.dir"))
    }

    if (!projectRoot.exists() || !projectRoot.isDirectory) {
        System.err.println("Error: Project root does not exist or is not a directory: ${projectRoot.absolutePath}")
        exitProcess(1)
    }

    // Get output directory from system property or default
    val outputDir = System.getProperty("output.dir")?.let { File(it) }
        ?: File(projectRoot, "build/code-analysis")

    println("Project root: ${projectRoot.absolutePath}")
    println("Output directory: ${outputDir.absolutePath}")
    println()

    try {
        val elapsed = measureTimeMillis {
            // Run analysis
            val analyzer = ProjectAnalyzer(projectRoot)
            val analysis = analyzer.analyze()

            // Write output
            val writer = JsonOutputWriter(outputDir)
            writer.write(analysis)
        }

        println()
        println("=".repeat(60))
        println("Analysis completed in ${elapsed / 1000.0} seconds")
        println("=".repeat(60))
    } catch (e: Exception) {
        System.err.println("Error during analysis: ${e.message}")
        e.printStackTrace()
        exitProcess(1)
    }
}
