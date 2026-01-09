package io.tolgee.codeanalysis.analyzer

import io.tolgee.codeanalysis.model.*

/**
 * Builds the call graph and type flow graph from analyzed classes and functions.
 * This is a post-processing step that:
 * 1. Resolves function call references to actual signatures
 * 2. Builds reverse edges (calledFrom)
 * 3. Builds type flow graph
 */
class CallGraphBuilder(private val typeIndex: TypeIndexer.TypeIndex) {

    /**
     * Result of call graph resolution
     */
    data class CallGraphResult(
        val callGraph: CallGraph,
        val typeFlow: TypeFlow,
        /** Updated classes with resolved call information */
        val updatedClasses: List<ClassInfo>,
        /** Updated functions with resolved call information */
        val updatedFunctions: List<FunctionInfo>
    )

    /**
     * Builds the call graph from analyzed classes and functions.
     */
    fun build(
        classes: List<ClassInfo>,
        topLevelFunctions: List<FunctionInfo>
    ): CallGraphResult {
        // Collect all functions (from classes + top-level)
        val allFunctions = mutableListOf<FunctionInfo>()
        classes.forEach { classInfo ->
            allFunctions.addAll(classInfo.methods)
        }
        allFunctions.addAll(topLevelFunctions)

        // Build signature -> function map
        val signatureToFunction = allFunctions.associateBy { it.signature }

        // Build function name -> signatures map for this analysis
        val nameToSignatures = mutableMapOf<String, MutableSet<String>>()
        allFunctions.forEach { fn ->
            nameToSignatures.getOrPut(fn.name) { mutableSetOf() }.add(fn.signature)
        }

        // Resolve calls and build edges
        val edges = mutableMapOf<String, MutableSet<String>>()
        val reverseEdges = mutableMapOf<String, MutableSet<String>>()

        allFunctions.forEach { function ->
            val resolvedCalls = mutableSetOf<String>()

            function.callsTo.forEach { callName ->
                // Try to resolve the called function name to actual signatures
                val possibleSignatures = nameToSignatures[callName] ?: emptySet()

                // For now, add all possible matches (could be refined with type information)
                resolvedCalls.addAll(possibleSignatures)
            }

            if (resolvedCalls.isNotEmpty()) {
                edges[function.signature] = resolvedCalls

                // Build reverse edges
                resolvedCalls.forEach { callee ->
                    reverseEdges.getOrPut(callee) { mutableSetOf() }.add(function.signature)
                }
            }
        }

        // Update calledFrom in functions
        val updatedFunctions = allFunctions.map { fn ->
            fn.copy(
                calledFrom = reverseEdges[fn.signature]?.toMutableSet() ?: mutableSetOf()
            )
        }

        // Update classes with updated methods
        val signatureToUpdatedFunction = updatedFunctions.associateBy { it.signature }
        val updatedClasses = classes.map { classInfo ->
            classInfo.copy(
                methods = classInfo.methods.map { method ->
                    signatureToUpdatedFunction[method.signature] ?: method
                }
            )
        }

        // Build type flow graph
        val typeFlow = buildTypeFlow(updatedFunctions)

        // Build call graph
        val allNodes = allFunctions.map { it.signature }.toSet()
        val callGraph = CallGraph(
            nodes = allNodes,
            edges = edges.mapValues { it.value.toSet() },
            reverseEdges = reverseEdges.mapValues { it.value.toSet() }
        )

        // Get only top-level functions from updated list
        val topLevelSignatures = topLevelFunctions.map { it.signature }.toSet()
        val updatedTopLevelFunctions = updatedFunctions.filter { it.signature in topLevelSignatures }

        return CallGraphResult(
            callGraph = callGraph,
            typeFlow = typeFlow,
            updatedClasses = updatedClasses,
            updatedFunctions = updatedTopLevelFunctions
        )
    }

    /**
     * Builds the type flow graph showing how types flow between functions.
     */
    private fun buildTypeFlow(functions: List<FunctionInfo>): TypeFlow {
        val typeNodes = mutableSetOf<String>()
        val functionNodes = mutableSetOf<String>()
        val typeToFunction = mutableMapOf<String, MutableSet<String>>()
        val functionToType = mutableMapOf<String, MutableSet<String>>()

        functions.forEach { function ->
            functionNodes.add(function.signature)

            // Types flowing INTO the function (parameters, extension receiver)
            function.incomingTypes.forEach { typeRef ->
                if (typeRef.isProjectType) {
                    typeNodes.add(typeRef.fqn)
                    typeToFunction.getOrPut(typeRef.fqn) { mutableSetOf() }.add(function.signature)
                }
            }

            // Types flowing OUT of the function (return type)
            function.outgoingTypes.forEach { typeRef ->
                if (typeRef.isProjectType) {
                    typeNodes.add(typeRef.fqn)
                    functionToType.getOrPut(function.signature) { mutableSetOf() }.add(typeRef.fqn)
                }
            }
        }

        return TypeFlow(
            typeNodes = typeNodes,
            functionNodes = functionNodes,
            typeToFunction = typeToFunction.mapValues { it.value.toSet() },
            functionToType = functionToType.mapValues { it.value.toSet() }
        )
    }
}
