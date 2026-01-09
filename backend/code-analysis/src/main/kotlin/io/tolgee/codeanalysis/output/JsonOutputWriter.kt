package io.tolgee.codeanalysis.output

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.tolgee.codeanalysis.model.*
import java.io.File

/**
 * Writes the analysis output to JSON files.
 *
 * Output structure:
 * - output/
 *   - project.json         (complete project analysis - can be large)
 *   - summary.json          (summary statistics and index)
 *   - modules/
 *     - {module-name}.json  (module details with classes)
 *   - packages/
 *     - {package-name}.json (package details)
 *   - classes/
 *     - {package}/{class-name}.json (individual class files)
 *   - call-graph.json       (optimized call graph for rendering)
 *   - type-flow.json        (type flow graph for rendering)
 */
class JsonOutputWriter(private val outputDir: File) {

    private val objectMapper = ObjectMapper()
        .registerKotlinModule()
        .enable(SerializationFeature.INDENT_OUTPUT)

    init {
        outputDir.mkdirs()
    }

    /**
     * Writes all output files from the analysis.
     */
    fun write(analysis: ProjectAnalysis) {
        println("Writing output to: ${outputDir.absolutePath}")

        // Write complete project file (might be large)
        writeProjectJson(analysis)

        // Write summary
        writeSummary(analysis)

        // Write per-module files
        writeModules(analysis)

        // Write per-package files
        writePackages(analysis)

        // Write per-class files (one file per class)
        writeClasses(analysis)

        // Write call graph (optimized for rendering)
        writeCallGraph(analysis.callGraph)

        // Write type flow graph
        writeTypeFlow(analysis.typeFlow)

        // Write top-level functions
        writeTopLevelFunctions(analysis.topLevelFunctions)

        println("Output complete!")
    }

    private fun writeProjectJson(analysis: ProjectAnalysis) {
        val file = File(outputDir, "project.json")
        objectMapper.writeValue(file, analysis)
        println("  Wrote project.json (${file.length() / 1024} KB)")
    }

    private fun writeSummary(analysis: ProjectAnalysis) {
        val summary = mapOf(
            "moduleCount" to analysis.modules.size,
            "packageCount" to analysis.packages.size,
            "classCount" to analysis.classes.size,
            "topLevelFunctionCount" to analysis.topLevelFunctions.size,
            "totalMethodCount" to analysis.classes.sumOf { it.methods.size },
            "callGraphNodeCount" to analysis.callGraph.nodes.size,
            "callGraphEdgeCount" to analysis.callGraph.edges.values.sumOf { it.size },
            "typeFlowTypeCount" to analysis.typeFlow.typeNodes.size,
            "dataWrapperCount" to analysis.classes.count { it.isDataWrapper },
            "modules" to analysis.modules.map { mapOf(
                "name" to it.name,
                "path" to it.path,
                "packageCount" to it.packageNames.size
            )},
            "packagesByModule" to analysis.modules.associate { module ->
                module.name to analysis.packages
                    .filter { it.moduleName == module.name }
                    .map { it.name }
                    .sorted()
            }
        )

        val file = File(outputDir, "summary.json")
        objectMapper.writeValue(file, summary)
        println("  Wrote summary.json")
    }

    private fun writeModules(analysis: ProjectAnalysis) {
        val modulesDir = File(outputDir, "modules")
        modulesDir.mkdirs()

        analysis.modules.forEach { module ->
            val moduleData = mapOf(
                "module" to module,
                "packages" to analysis.packages.filter { it.moduleName == module.name },
                "classes" to analysis.classes.filter { it.moduleName == module.name },
                "topLevelFunctions" to analysis.topLevelFunctions.filter { it.moduleName == module.name }
            )

            val file = File(modulesDir, "${module.name}.json")
            objectMapper.writeValue(file, moduleData)
        }
        println("  Wrote ${analysis.modules.size} module files")
    }

    private fun writePackages(analysis: ProjectAnalysis) {
        val packagesDir = File(outputDir, "packages")
        packagesDir.mkdirs()

        analysis.packages.forEach { pkg ->
            val packageData = mapOf(
                "package" to pkg,
                "classes" to analysis.classes.filter { it.packageName == pkg.name },
                "topLevelFunctions" to analysis.topLevelFunctions.filter { it.packageName == pkg.name }
            )

            // Use package name as filename (replace dots with slashes for directory structure)
            val fileName = pkg.name.replace(".", "_") + ".json"
            val file = File(packagesDir, fileName)
            objectMapper.writeValue(file, packageData)
        }
        println("  Wrote ${analysis.packages.size} package files")
    }

    private fun writeClasses(analysis: ProjectAnalysis) {
        val classesDir = File(outputDir, "classes")

        analysis.classes.forEach { classInfo ->
            // Create subdirectory based on package
            val packageDir = File(classesDir, classInfo.packageName.replace(".", "/"))
            packageDir.mkdirs()

            val file = File(packageDir, "${classInfo.name}.json")
            objectMapper.writeValue(file, classInfo)
        }
        println("  Wrote ${analysis.classes.size} class files")
    }

    private fun writeCallGraph(callGraph: CallGraph) {
        // Write the call graph in a format optimized for graph rendering
        // Include adjacency list format for efficient traversal

        val graphData = mapOf(
            "nodes" to callGraph.nodes.map { signature ->
                mapOf(
                    "id" to signature,
                    "outDegree" to (callGraph.edges[signature]?.size ?: 0),
                    "inDegree" to (callGraph.reverseEdges[signature]?.size ?: 0)
                )
            },
            "edges" to callGraph.edges.flatMap { (source, targets) ->
                targets.map { target ->
                    mapOf("source" to source, "target" to target)
                }
            },
            "adjacencyList" to callGraph.edges,
            "reverseAdjacencyList" to callGraph.reverseEdges
        )

        val file = File(outputDir, "call-graph.json")
        objectMapper.writeValue(file, graphData)
        println("  Wrote call-graph.json")
    }

    private fun writeTypeFlow(typeFlow: TypeFlow) {
        // Write type flow in bipartite graph format
        val graphData = mapOf(
            "types" to typeFlow.typeNodes.sorted(),
            "functions" to typeFlow.functionNodes.sorted(),
            "typeToFunctionEdges" to typeFlow.typeToFunction.flatMap { (type, functions) ->
                functions.map { fn -> mapOf("type" to type, "function" to fn) }
            },
            "functionToTypeEdges" to typeFlow.functionToType.flatMap { (fn, types) ->
                types.map { type -> mapOf("function" to fn, "type" to type) }
            },
            "typeToFunctionAdjacency" to typeFlow.typeToFunction,
            "functionToTypeAdjacency" to typeFlow.functionToType
        )

        val file = File(outputDir, "type-flow.json")
        objectMapper.writeValue(file, graphData)
        println("  Wrote type-flow.json")
    }

    private fun writeTopLevelFunctions(functions: List<FunctionInfo>) {
        val file = File(outputDir, "top-level-functions.json")
        objectMapper.writeValue(file, functions)
        println("  Wrote top-level-functions.json")
    }
}
