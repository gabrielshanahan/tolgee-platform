package io.tolgee.codeanalysis.model

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Represents the complete analysis output for the entire project.
 * This is the root object that will be serialized to JSON.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class ProjectAnalysis(
    /** All modules in the project */
    val modules: List<ModuleInfo>,
    /** All packages across all modules */
    val packages: List<PackageInfo>,
    /** All classes/interfaces/objects/enums */
    val classes: List<ClassInfo>,
    /** Top-level functions (not inside any class) */
    val topLevelFunctions: List<FunctionInfo>,
    /** Index for quick lookup - maps fully qualified name to entity type */
    val typeIndex: Map<String, TypeIndexEntry>,
    /** Call graph edges - for efficient rendering */
    val callGraph: CallGraph,
    /** Type flow edges - shows how types flow between methods */
    val typeFlow: TypeFlow
)

/**
 * Represents a Gradle module in the project
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class ModuleInfo(
    /** Module name (e.g., "data", "api", "server-app") */
    val name: String,
    /** Relative path from project root */
    val path: String,
    /** Packages contained in this module */
    val packageNames: Set<String>
)

/**
 * Represents a package
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class PackageInfo(
    /** Fully qualified package name */
    val name: String,
    /** Module this package belongs to */
    val moduleName: String,
    /** Classes in this package (FQNs) */
    val classNames: Set<String>,
    /** Top-level functions in this package (signatures) */
    val topLevelFunctionSignatures: Set<String>
)

/**
 * Represents a class, interface, object, or enum
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class ClassInfo(
    /** Simple name of the class */
    val name: String,
    /** Fully qualified name */
    val fqn: String,
    /** Package name */
    val packageName: String,
    /** Module this class belongs to */
    val moduleName: String,
    /** Source file path (relative to project root) */
    val sourceFile: String,
    /** Line number where class is declared */
    val lineNumber: Int,
    /** Type of declaration */
    val kind: ClassKind,
    /** Visibility modifier */
    val visibility: Visibility,
    /** Is it a data class? */
    val isDataClass: Boolean,
    /** Is it a sealed class? */
    val isSealedClass: Boolean,
    /** Is it an inner class? */
    val isInner: Boolean,
    /** Is it a companion object? */
    val isCompanion: Boolean,
    /** Whether this is primarily a data wrapper (data class, enum, simple DTO) */
    val isDataWrapper: Boolean,
    /** Parent class FQN (if nested/inner) */
    val enclosingClass: String?,
    /** Superclass FQN (if extends a project class) */
    val superClass: String?,
    /** Implemented interfaces (FQNs of project types only) */
    val interfaces: Set<String>,
    /** Type parameters (generics) */
    val typeParameters: List<String>,
    /** Constructor parameter types (FQNs of project types only) */
    val constructorParameterTypes: Set<TypeReference>,
    /** All types referenced in any constructor */
    val allConstructorTypes: List<ConstructorInfo>,
    /** Annotations (FQNs of project types only) */
    val annotations: Set<String>,
    /** Methods/functions in this class */
    val methods: List<FunctionInfo>,
    /** Properties in this class */
    val properties: List<PropertyInfo>,
    /** Nested/inner classes (FQNs) */
    val nestedClasses: Set<String>
)

/**
 * Information about a constructor
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class ConstructorInfo(
    /** Is it the primary constructor? */
    val isPrimary: Boolean,
    /** Parameter types (project types only) */
    val parameterTypes: List<ParameterInfo>,
    /** Visibility */
    val visibility: Visibility
)

/**
 * Information about a parameter
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class ParameterInfo(
    /** Parameter name */
    val name: String,
    /** Type reference */
    val type: TypeReference,
    /** Is it a val/var property? (for primary constructors) */
    val isProperty: Boolean,
    /** Has default value? */
    val hasDefault: Boolean
)

/**
 * Information about a property
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class PropertyInfo(
    /** Property name */
    val name: String,
    /** Type reference */
    val type: TypeReference,
    /** Is mutable (var vs val) */
    val isMutable: Boolean,
    /** Visibility */
    val visibility: Visibility,
    /** Is it a computed property (has getter body)? */
    val isComputed: Boolean,
    /** Line number */
    val lineNumber: Int
)

/**
 * Represents a function/method
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class FunctionInfo(
    /** Simple name of the function */
    val name: String,
    /** Unique signature (used as identifier) - format: "packageName.ClassName.methodName(ParamType1,ParamType2):ReturnType" */
    val signature: String,
    /** Containing class FQN (null for top-level functions) */
    val containingClass: String?,
    /** Package name */
    val packageName: String,
    /** Module name */
    val moduleName: String,
    /** Source file path */
    val sourceFile: String,
    /** Line number */
    val lineNumber: Int,
    /** Visibility */
    val visibility: Visibility,
    /** Is it a suspend function? */
    val isSuspend: Boolean,
    /** Is it an inline function? */
    val isInline: Boolean,
    /** Is it an extension function? */
    val isExtension: Boolean,
    /** Is it an operator function? */
    val isOperator: Boolean,
    /** Is it an infix function? */
    val isInfix: Boolean,
    /** Extension receiver type (project types only) */
    val extensionReceiverType: TypeReference?,
    /** Return type (project types only, null if not a project type) */
    val returnType: TypeReference?,
    /** Parameter types (project types only) */
    val parameters: List<ParameterInfo>,
    /** Type parameters (generics) */
    val typeParameters: List<String>,
    /** Annotations (FQNs of project types only) */
    val annotations: Set<String>,
    /** Functions called from this function (signatures) */
    val callsTo: Set<String>,
    /** Functions that call this function (signatures) - populated in post-processing */
    val calledFrom: MutableSet<String>,
    /** Types that flow into this function (parameters + extension receiver) */
    val incomingTypes: Set<TypeReference>,
    /** Types that flow out of this function (return type) */
    val outgoingTypes: Set<TypeReference>
)

/**
 * A reference to a type, including generic type arguments
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class TypeReference(
    /** Fully qualified name of the type */
    val fqn: String,
    /** Simple name */
    val simpleName: String,
    /** Is it nullable? */
    val isNullable: Boolean = false,
    /** Type arguments (for generics) */
    val typeArguments: List<TypeReference> = emptyList(),
    /** Is this a project type? (vs stdlib/external) */
    val isProjectType: Boolean = true
)

/**
 * Index entry for quick type lookup
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class TypeIndexEntry(
    /** The entity type */
    val entityType: EntityType,
    /** Module containing this type */
    val moduleName: String,
    /** Package containing this type */
    val packageName: String,
    /** Source file */
    val sourceFile: String
)

/**
 * Call graph for efficient rendering
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class CallGraph(
    /** All nodes (function signatures) */
    val nodes: Set<String>,
    /** Edges: caller -> set of callees */
    val edges: Map<String, Set<String>>,
    /** Reverse edges: callee -> set of callers */
    val reverseEdges: Map<String, Set<String>>
)

/**
 * Type flow graph
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class TypeFlow(
    /** Types as nodes (FQNs) */
    val typeNodes: Set<String>,
    /** Functions as nodes (signatures) */
    val functionNodes: Set<String>,
    /** Edges: type -> functions that accept it as parameter */
    val typeToFunction: Map<String, Set<String>>,
    /** Edges: function -> types it returns */
    val functionToType: Map<String, Set<String>>
)

enum class ClassKind {
    CLASS,
    INTERFACE,
    OBJECT,
    ENUM_CLASS,
    ENUM_ENTRY,
    ANNOTATION_CLASS
}

enum class Visibility {
    PUBLIC,
    INTERNAL,
    PROTECTED,
    PRIVATE
}

enum class EntityType {
    CLASS,
    INTERFACE,
    OBJECT,
    ENUM,
    ANNOTATION,
    FUNCTION,
    PROPERTY
}
