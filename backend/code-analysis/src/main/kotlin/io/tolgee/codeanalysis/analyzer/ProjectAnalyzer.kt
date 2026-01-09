package io.tolgee.codeanalysis.analyzer

import io.tolgee.codeanalysis.model.*
import java.io.File

/**
 * Main orchestrator for the code analysis.
 * Coordinates the two-pass analysis:
 * 1. First pass: Index all types
 * 2. Second pass: Analyze all files with type resolution
 */
class ProjectAnalyzer(private val projectRoot: File) {

    private val scanner = ProjectScanner(projectRoot)
    private val psiAnalyzer = KotlinPsiAnalyzer()
    private val typeIndexer = TypeIndexer(psiAnalyzer)

    /**
     * Runs the complete analysis and returns the ProjectAnalysis.
     */
    fun analyze(): ProjectAnalysis {
        println("Starting code analysis for: ${projectRoot.absolutePath}")

        // Scan for source files
        println("Scanning for Kotlin source files...")
        val sourceFiles = scanner.scanKotlinFiles()
        println("Found ${sourceFiles.size} Kotlin source files")

        // First pass: Build type index
        println("First pass: Building type index...")
        val typeIndex = typeIndexer.buildIndex(sourceFiles)
        println("Indexed ${typeIndex.types.size} types and ${typeIndex.functionSignatures.size} functions")

        // Get the set of all project type FQNs for reference resolution
        val projectTypeFqns = typeIndex.allFqns

        // Second pass: Analyze all files
        println("Second pass: Analyzing files...")
        val allClasses = mutableListOf<ClassInfo>()
        val allTopLevelFunctions = mutableListOf<FunctionInfo>()
        val packageToClasses = mutableMapOf<String, MutableSet<String>>()
        val packageToFunctions = mutableMapOf<String, MutableSet<String>>()
        val moduleToPackages = mutableMapOf<String, MutableSet<String>>()

        var processedCount = 0
        sourceFiles.forEach { sourceFile ->
            processedCount++
            if (processedCount % 100 == 0) {
                println("  Processed $processedCount / ${sourceFiles.size} files...")
            }

            val ktFile = psiAnalyzer.parseFile(sourceFile.file) ?: return@forEach
            val packageName = ktFile.packageFqName.asString()

            // Track module -> packages
            moduleToPackages.getOrPut(sourceFile.moduleName) { mutableSetOf() }.add(packageName)

            // Extract classes
            val classes = psiAnalyzer.extractClasses(ktFile, sourceFile, projectTypeFqns)
            allClasses.addAll(classes)

            // Track package -> classes
            classes.forEach { classInfo ->
                packageToClasses.getOrPut(packageName) { mutableSetOf() }.add(classInfo.fqn)
            }

            // Extract top-level functions
            val functions = psiAnalyzer.extractTopLevelFunctions(ktFile, sourceFile, projectTypeFqns)
            allTopLevelFunctions.addAll(functions)

            // Track package -> functions
            functions.forEach { fn ->
                packageToFunctions.getOrPut(packageName) { mutableSetOf() }.add(fn.signature)
            }
        }

        println("Analyzed ${allClasses.size} classes and ${allTopLevelFunctions.size} top-level functions")

        // Build call graph
        println("Building call graph...")
        val callGraphBuilder = CallGraphBuilder(typeIndex)
        val callGraphResult = callGraphBuilder.build(allClasses, allTopLevelFunctions)

        // Build module info
        val modules = scanner.getModules().map { moduleDef ->
            ModuleInfo(
                name = moduleDef.name,
                path = moduleDef.path,
                packageNames = moduleToPackages[moduleDef.name] ?: emptySet()
            )
        }

        // Build package info
        val packages = (packageToClasses.keys + packageToFunctions.keys).map { packageName ->
            // Find module for this package
            val moduleName = moduleToPackages.entries
                .find { (_, pkgs) -> packageName in pkgs }
                ?.key ?: "unknown"

            PackageInfo(
                name = packageName,
                moduleName = moduleName,
                classNames = packageToClasses[packageName] ?: emptySet(),
                topLevelFunctionSignatures = packageToFunctions[packageName] ?: emptySet()
            )
        }

        println("Analysis complete!")

        // Cleanup
        psiAnalyzer.dispose()

        return ProjectAnalysis(
            modules = modules,
            packages = packages,
            classes = callGraphResult.updatedClasses,
            topLevelFunctions = callGraphResult.updatedFunctions,
            typeIndex = typeIndex.toTypeIndexEntryMap(),
            callGraph = callGraphResult.callGraph,
            typeFlow = callGraphResult.typeFlow
        )
    }
}
