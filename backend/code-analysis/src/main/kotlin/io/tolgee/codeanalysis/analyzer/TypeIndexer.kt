package io.tolgee.codeanalysis.analyzer

import io.tolgee.codeanalysis.model.EntityType
import io.tolgee.codeanalysis.model.TypeIndexEntry
import org.jetbrains.kotlin.psi.*

/**
 * First-pass indexer that collects all type names defined in the project.
 * This allows the second pass to determine if a referenced type is a project type.
 */
class TypeIndexer(private val analyzer: KotlinPsiAnalyzer) {

    /**
     * Indexed type with metadata
     */
    data class IndexedType(
        val fqn: String,
        val simpleName: String,
        val packageName: String,
        val moduleName: String,
        val sourceFile: String,
        val entityType: EntityType
    )

    /**
     * Scans all source files and builds an index of all declared types.
     */
    fun buildIndex(sourceFiles: List<ProjectScanner.SourceFile>): TypeIndex {
        val types = mutableMapOf<String, IndexedType>()  // FQN -> IndexedType
        val simpleNameToFqns = mutableMapOf<String, MutableSet<String>>()  // SimpleName -> Set<FQN>
        val functionSignatures = mutableMapOf<String, IndexedType>()  // Signature -> IndexedType
        val functionNameToSignatures = mutableMapOf<String, MutableSet<String>>()  // FunctionName -> Set<Signature>

        for (sourceFile in sourceFiles) {
            val ktFile = analyzer.parseFile(sourceFile.file) ?: continue
            val packageName = ktFile.packageFqName.asString()

            // Index classes, interfaces, objects, enums
            ktFile.declarations.filterIsInstance<KtClassOrObject>().forEach { classOrObject ->
                indexClassOrObject(classOrObject, packageName, sourceFile, types, simpleNameToFqns)
            }

            // Index top-level functions
            ktFile.declarations.filterIsInstance<KtNamedFunction>().forEach { function ->
                indexFunction(function, null, packageName, sourceFile, functionSignatures, functionNameToSignatures)
            }

            // Index type aliases
            ktFile.declarations.filterIsInstance<KtTypeAlias>().forEach { typeAlias ->
                val name = typeAlias.name ?: return@forEach
                val fqn = "$packageName.$name"

                val indexed = IndexedType(
                    fqn = fqn,
                    simpleName = name,
                    packageName = packageName,
                    moduleName = sourceFile.moduleName,
                    sourceFile = sourceFile.relativePath,
                    entityType = EntityType.CLASS  // Type aliases are treated as classes
                )

                types[fqn] = indexed
                simpleNameToFqns.getOrPut(name) { mutableSetOf() }.add(fqn)
            }
        }

        return TypeIndex(types, simpleNameToFqns, functionSignatures, functionNameToSignatures)
    }

    private fun indexClassOrObject(
        classOrObject: KtClassOrObject,
        packageName: String,
        sourceFile: ProjectScanner.SourceFile,
        types: MutableMap<String, IndexedType>,
        simpleNameToFqns: MutableMap<String, MutableSet<String>>,
        enclosingFqn: String? = null
    ) {
        val name = classOrObject.name ?: return
        val fqn = if (enclosingFqn != null) "$enclosingFqn.$name" else "$packageName.$name"

        val entityType = when {
            classOrObject is KtClass && classOrObject.isInterface() -> EntityType.INTERFACE
            classOrObject is KtClass && classOrObject.isEnum() -> EntityType.ENUM
            classOrObject is KtClass && classOrObject.isAnnotation() -> EntityType.ANNOTATION
            classOrObject is KtObjectDeclaration -> EntityType.OBJECT
            else -> EntityType.CLASS
        }

        val indexed = IndexedType(
            fqn = fqn,
            simpleName = name,
            packageName = packageName,
            moduleName = sourceFile.moduleName,
            sourceFile = sourceFile.relativePath,
            entityType = entityType
        )

        types[fqn] = indexed
        simpleNameToFqns.getOrPut(name) { mutableSetOf() }.add(fqn)

        // Index nested classes
        classOrObject.declarations.filterIsInstance<KtClassOrObject>().forEach { nested ->
            indexClassOrObject(nested, packageName, sourceFile, types, simpleNameToFqns, fqn)
        }

        // Index methods within the class
        classOrObject.declarations.filterIsInstance<KtNamedFunction>().forEach { function ->
            // Methods are indexed separately for call resolution
        }
    }

    private fun indexFunction(
        function: KtNamedFunction,
        containingClass: String?,
        packageName: String,
        sourceFile: ProjectScanner.SourceFile,
        functionSignatures: MutableMap<String, IndexedType>,
        functionNameToSignatures: MutableMap<String, MutableSet<String>>
    ) {
        val name = function.name ?: return

        // Build a simplified signature for indexing
        val params = function.valueParameters.mapNotNull { p ->
            p.typeReference?.text
        }.joinToString(",")

        val receiverType = function.receiverTypeReference?.text?.let { "($it)." } ?: ""
        val returnTypeText = function.typeReference?.text ?: "Unit"

        val signature = if (containingClass != null) {
            "$containingClass.$receiverType$name($params):$returnTypeText"
        } else {
            "$packageName.$receiverType$name($params):$returnTypeText"
        }

        val indexed = IndexedType(
            fqn = signature,
            simpleName = name,
            packageName = packageName,
            moduleName = sourceFile.moduleName,
            sourceFile = sourceFile.relativePath,
            entityType = EntityType.FUNCTION
        )

        functionSignatures[signature] = indexed
        functionNameToSignatures.getOrPut(name) { mutableSetOf() }.add(signature)
    }

    /**
     * The complete type index for the project.
     */
    data class TypeIndex(
        /** FQN -> IndexedType for classes/interfaces/objects */
        val types: Map<String, IndexedType>,
        /** SimpleName -> Set of FQNs (for ambiguous resolution) */
        val simpleNameToFqns: Map<String, Set<String>>,
        /** Function signature -> IndexedType */
        val functionSignatures: Map<String, IndexedType>,
        /** Function name -> Set of signatures */
        val functionNameToSignatures: Map<String, Set<String>>
    ) {
        /** All FQNs as a set for quick lookup */
        val allFqns: Set<String> by lazy { types.keys + simpleNames }

        /** All simple names */
        val simpleNames: Set<String> by lazy { simpleNameToFqns.keys }

        /** All function names */
        val functionNames: Set<String> by lazy { functionNameToSignatures.keys }

        /**
         * Checks if a type name (simple or FQN) is a project type.
         */
        fun isProjectType(name: String): Boolean {
            return types.containsKey(name) || simpleNameToFqns.containsKey(name)
        }

        /**
         * Tries to resolve a simple name to FQN(s).
         */
        fun resolveSimpleName(simpleName: String): Set<String> {
            return simpleNameToFqns[simpleName] ?: emptySet()
        }

        /**
         * Gets possible function signatures for a function name.
         */
        fun resolveFunctionName(functionName: String): Set<String> {
            return functionNameToSignatures[functionName] ?: emptySet()
        }

        /**
         * Converts to TypeIndexEntry map for output.
         */
        fun toTypeIndexEntryMap(): Map<String, TypeIndexEntry> {
            return types.mapValues { (_, indexed) ->
                TypeIndexEntry(
                    entityType = indexed.entityType,
                    moduleName = indexed.moduleName,
                    packageName = indexed.packageName,
                    sourceFile = indexed.sourceFile
                )
            }
        }
    }
}
