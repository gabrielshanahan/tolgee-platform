package io.tolgee.codeanalysis.analyzer

import io.tolgee.codeanalysis.model.*
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import java.io.File

/**
 * Analyzes Kotlin source files using PSI (Program Structure Interface).
 * This provides AST-level analysis without full semantic resolution.
 */
class KotlinPsiAnalyzer {

    private val disposable = Disposer.newDisposable()
    private val environment: KotlinCoreEnvironment

    init {
        val configuration = CompilerConfiguration()
        configuration.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)

        environment = KotlinCoreEnvironment.createForProduction(
            disposable,
            configuration,
            EnvironmentConfigFiles.JVM_CONFIG_FILES
        )
    }

    fun dispose() {
        Disposer.dispose(disposable)
    }

    /**
     * Parses a Kotlin file and returns the PSI tree.
     */
    fun parseFile(file: File): KtFile? {
        return try {
            val psiFactory = KtPsiFactory(environment.project)
            val text = file.readText()
            psiFactory.createFile(file.name, text)
        } catch (e: Exception) {
            System.err.println("Failed to parse ${file.path}: ${e.message}")
            null
        }
    }

    /**
     * Extracts all class declarations from a parsed file.
     */
    fun extractClasses(
        ktFile: KtFile,
        sourceFile: ProjectScanner.SourceFile,
        projectTypeIndex: Set<String>
    ): List<ClassInfo> {
        val result = mutableListOf<ClassInfo>()
        val packageName = ktFile.packageFqName.asString()

        ktFile.declarations.filterIsInstance<KtClassOrObject>().forEach { classOrObject ->
            extractClassInfo(classOrObject, packageName, sourceFile, projectTypeIndex, result)
        }

        return result
    }

    private fun extractClassInfo(
        classOrObject: KtClassOrObject,
        packageName: String,
        sourceFile: ProjectScanner.SourceFile,
        projectTypeIndex: Set<String>,
        result: MutableList<ClassInfo>,
        enclosingClass: String? = null
    ) {
        val name = classOrObject.name ?: return
        val fqn = if (enclosingClass != null) "$enclosingClass.$name" else "$packageName.$name"

        val kind = when {
            classOrObject is KtClass && classOrObject.isInterface() -> ClassKind.INTERFACE
            classOrObject is KtClass && classOrObject.isEnum() -> ClassKind.ENUM_CLASS
            classOrObject is KtClass && classOrObject.isAnnotation() -> ClassKind.ANNOTATION_CLASS
            classOrObject is KtObjectDeclaration && classOrObject.isCompanion() -> ClassKind.OBJECT
            classOrObject is KtObjectDeclaration -> ClassKind.OBJECT
            classOrObject is KtEnumEntry -> ClassKind.ENUM_ENTRY
            else -> ClassKind.CLASS
        }

        val isDataClass = classOrObject is KtClass && classOrObject.isData()
        val isSealed = classOrObject is KtClass && classOrObject.isSealed()
        val isInner = classOrObject is KtClass && classOrObject.isInner()
        val isCompanion = classOrObject is KtObjectDeclaration && classOrObject.isCompanion()

        // Determine if it's a data wrapper
        val isDataWrapper = isDataClass ||
            kind == ClassKind.ENUM_CLASS ||
            kind == ClassKind.ENUM_ENTRY ||
            isSimpleDto(classOrObject)

        // Extract constructors
        val constructors = extractConstructors(classOrObject, projectTypeIndex)
        val constructorTypes = constructors.flatMap { c ->
            c.parameterTypes.map { it.type }
        }.filter { it.isProjectType }.toSet()

        // Extract super types (only project types)
        val superTypes = extractSuperTypes(classOrObject, projectTypeIndex)

        // Extract methods
        val methods = classOrObject.declarations
            .filterIsInstance<KtNamedFunction>()
            .mapNotNull { extractFunctionInfo(it, fqn, packageName, sourceFile, projectTypeIndex) }

        // Extract properties
        val properties = classOrObject.declarations
            .filterIsInstance<KtProperty>()
            .mapNotNull { extractPropertyInfo(it, projectTypeIndex) }

        // Extract annotations (project types only)
        val annotations = classOrObject.annotationEntries
            .mapNotNull { resolveAnnotationType(it, projectTypeIndex) }
            .toSet()

        // Get nested class names
        val nestedClassNames = mutableSetOf<String>()

        val classInfo = ClassInfo(
            name = name,
            fqn = fqn,
            packageName = packageName,
            moduleName = sourceFile.moduleName,
            sourceFile = sourceFile.relativePath,
            lineNumber = getLineNumber(classOrObject),
            kind = kind,
            visibility = extractVisibility(classOrObject),
            isDataClass = isDataClass,
            isSealedClass = isSealed,
            isInner = isInner,
            isCompanion = isCompanion,
            isDataWrapper = isDataWrapper,
            enclosingClass = enclosingClass,
            superClass = superTypes.superClass,
            interfaces = superTypes.interfaces,
            typeParameters = classOrObject.typeParameters.mapNotNull { it.name },
            constructorParameterTypes = constructorTypes,
            allConstructorTypes = constructors,
            annotations = annotations,
            methods = methods,
            properties = properties,
            nestedClasses = nestedClassNames
        )

        result.add(classInfo)

        // Process nested classes
        classOrObject.declarations.filterIsInstance<KtClassOrObject>().forEach { nested ->
            nestedClassNames.add("$fqn.${nested.name}")
            extractClassInfo(nested, packageName, sourceFile, projectTypeIndex, result, fqn)
        }
    }

    /**
     * Checks if a class is a simple DTO (mostly properties, few methods).
     */
    private fun isSimpleDto(classOrObject: KtClassOrObject): Boolean {
        val methods = classOrObject.declarations.filterIsInstance<KtNamedFunction>()
        val properties = classOrObject.declarations.filterIsInstance<KtProperty>()

        // Consider it a DTO if it has mostly properties and few/no custom methods
        return properties.size >= 2 && methods.size <= 2
    }

    /**
     * Extracts constructor information.
     */
    private fun extractConstructors(
        classOrObject: KtClassOrObject,
        projectTypeIndex: Set<String>
    ): List<ConstructorInfo> {
        val result = mutableListOf<ConstructorInfo>()

        // Primary constructor
        if (classOrObject is KtClass) {
            classOrObject.primaryConstructor?.let { primary ->
                result.add(
                    ConstructorInfo(
                        isPrimary = true,
                        parameterTypes = extractParameterInfoList(primary.valueParameters, projectTypeIndex),
                        visibility = extractVisibility(primary)
                    )
                )
            } ?: run {
                // Implicit primary constructor with no parameters
                val params = classOrObject.primaryConstructorParameters
                if (params.isNotEmpty()) {
                    result.add(
                        ConstructorInfo(
                            isPrimary = true,
                            parameterTypes = extractParameterInfoList(params, projectTypeIndex),
                            visibility = Visibility.PUBLIC
                        )
                    )
                }
            }

            // Secondary constructors
            classOrObject.secondaryConstructors.forEach { secondary ->
                result.add(
                    ConstructorInfo(
                        isPrimary = false,
                        parameterTypes = extractParameterInfoList(secondary.valueParameters, projectTypeIndex),
                        visibility = extractVisibility(secondary)
                    )
                )
            }
        }

        return result
    }

    private fun extractParameterInfoList(
        parameters: List<KtParameter>,
        projectTypeIndex: Set<String>
    ): List<ParameterInfo> {
        return parameters.mapNotNull { param ->
            val typeRef = param.typeReference?.let { resolveTypeReference(it, projectTypeIndex) }
            if (typeRef != null) {
                ParameterInfo(
                    name = param.name ?: "",
                    type = typeRef,
                    isProperty = param.hasValOrVar(),
                    hasDefault = param.hasDefaultValue()
                )
            } else null
        }
    }

    data class SuperTypes(val superClass: String?, val interfaces: Set<String>)

    private fun extractSuperTypes(
        classOrObject: KtClassOrObject,
        projectTypeIndex: Set<String>
    ): SuperTypes {
        var superClass: String? = null
        val interfaces = mutableSetOf<String>()

        classOrObject.superTypeListEntries.forEach { entry ->
            val typeRef = entry.typeReference
            if (typeRef != null) {
                val resolved = resolveTypeReference(typeRef, projectTypeIndex)
                if (resolved != null && resolved.isProjectType) {
                    // We can't definitively know if it's a class or interface from PSI alone
                    // We'll add to interfaces for now; this can be refined in post-processing
                    interfaces.add(resolved.fqn)
                }
            }
        }

        return SuperTypes(superClass, interfaces)
    }

    /**
     * Extracts top-level functions from a parsed file.
     */
    fun extractTopLevelFunctions(
        ktFile: KtFile,
        sourceFile: ProjectScanner.SourceFile,
        projectTypeIndex: Set<String>
    ): List<FunctionInfo> {
        val packageName = ktFile.packageFqName.asString()

        return ktFile.declarations
            .filterIsInstance<KtNamedFunction>()
            .mapNotNull { extractFunctionInfo(it, null, packageName, sourceFile, projectTypeIndex) }
    }

    /**
     * Extracts function information.
     */
    private fun extractFunctionInfo(
        function: KtNamedFunction,
        containingClass: String?,
        packageName: String,
        sourceFile: ProjectScanner.SourceFile,
        projectTypeIndex: Set<String>
    ): FunctionInfo? {
        val name = function.name ?: return null

        // Build signature
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

        // Extract extension receiver type
        val extensionReceiver = function.receiverTypeReference?.let {
            resolveTypeReference(it, projectTypeIndex)
        }

        // Extract return type
        val returnType = function.typeReference?.let {
            resolveTypeReference(it, projectTypeIndex)
        }

        // Extract parameters
        val parameters = extractParameterInfoList(function.valueParameters, projectTypeIndex)

        // Extract annotations
        val annotations = function.annotationEntries
            .mapNotNull { resolveAnnotationType(it, projectTypeIndex) }
            .toSet()

        // Extract function calls (references to other functions)
        val callsTo = extractFunctionCalls(function, projectTypeIndex)

        // Compute incoming and outgoing types
        val incomingTypes = mutableSetOf<TypeReference>()
        extensionReceiver?.let { if (it.isProjectType) incomingTypes.add(it) }
        parameters.filter { it.type.isProjectType }.forEach { incomingTypes.add(it.type) }

        val outgoingTypes = mutableSetOf<TypeReference>()
        returnType?.let { if (it.isProjectType) outgoingTypes.add(it) }

        return FunctionInfo(
            name = name,
            signature = signature,
            containingClass = containingClass,
            packageName = packageName,
            moduleName = sourceFile.moduleName,
            sourceFile = sourceFile.relativePath,
            lineNumber = getLineNumber(function),
            visibility = extractVisibility(function),
            isSuspend = function.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.SUSPEND_KEYWORD),
            isInline = function.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.INLINE_KEYWORD),
            isExtension = function.receiverTypeReference != null,
            isOperator = function.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.OPERATOR_KEYWORD),
            isInfix = function.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.INFIX_KEYWORD),
            extensionReceiverType = extensionReceiver,
            returnType = returnType,
            parameters = parameters,
            typeParameters = function.typeParameters.mapNotNull { it.name },
            annotations = annotations,
            callsTo = callsTo,
            calledFrom = mutableSetOf(),  // Will be populated in post-processing
            incomingTypes = incomingTypes,
            outgoingTypes = outgoingTypes
        )
    }

    /**
     * Extracts property information.
     */
    private fun extractPropertyInfo(
        property: KtProperty,
        projectTypeIndex: Set<String>
    ): PropertyInfo? {
        val name = property.name ?: return null
        val typeRef = property.typeReference?.let { resolveTypeReference(it, projectTypeIndex) }
            ?: return null

        return PropertyInfo(
            name = name,
            type = typeRef,
            isMutable = property.isVar,
            visibility = extractVisibility(property),
            isComputed = property.getter?.hasBody() == true,
            lineNumber = getLineNumber(property)
        )
    }

    /**
     * Extracts function calls from a function body.
     * This is a heuristic approach since we don't have full semantic analysis.
     */
    private fun extractFunctionCalls(
        function: KtNamedFunction,
        projectTypeIndex: Set<String>
    ): Set<String> {
        val calls = mutableSetOf<String>()

        function.bodyExpression?.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                super.visitCallExpression(expression)

                // Get the callee expression
                val callee = expression.calleeExpression
                when (callee) {
                    is KtNameReferenceExpression -> {
                        // Simple function call: functionName()
                        calls.add(callee.getReferencedName())
                    }
                    is KtDotQualifiedExpression -> {
                        // Method call: receiver.methodName()
                        val selector = callee.selectorExpression
                        if (selector is KtNameReferenceExpression) {
                            calls.add(selector.getReferencedName())
                        }
                    }
                }
            }

            override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
                super.visitDotQualifiedExpression(expression)

                // Handle chained calls like a.b.c()
                val selector = expression.selectorExpression
                if (selector is KtCallExpression) {
                    val callee = selector.calleeExpression
                    if (callee is KtNameReferenceExpression) {
                        calls.add(callee.getReferencedName())
                    }
                }
            }
        })

        return calls
    }

    /**
     * Resolves a type reference to a TypeReference object.
     */
    private fun resolveTypeReference(
        typeRef: KtTypeReference,
        projectTypeIndex: Set<String>
    ): TypeReference? {
        val typeElement = typeRef.typeElement ?: return null

        return when (typeElement) {
            is KtNullableType -> {
                typeElement.innerType?.let { inner ->
                    resolveTypeElement(inner, projectTypeIndex)?.copy(isNullable = true)
                }
            }
            else -> resolveTypeElement(typeElement, projectTypeIndex)
        }
    }

    private fun resolveTypeElement(
        typeElement: KtTypeElement,
        projectTypeIndex: Set<String>
    ): TypeReference? {
        return when (typeElement) {
            is KtUserType -> {
                val simpleName = typeElement.referencedName ?: return null
                val fqn = buildFqnFromUserType(typeElement)
                val isProjectType = projectTypeIndex.contains(fqn) ||
                    projectTypeIndex.contains(simpleName) ||
                    ProjectScanner.isProjectPackage(fqn.substringBeforeLast(".", ""))

                val typeArgs = typeElement.typeArguments.mapNotNull { arg ->
                    arg.typeReference?.let { resolveTypeReference(it, projectTypeIndex) }
                }

                TypeReference(
                    fqn = fqn,
                    simpleName = simpleName,
                    isNullable = false,
                    typeArguments = typeArgs,
                    isProjectType = isProjectType
                )
            }
            is KtFunctionType -> {
                // Function types like (A) -> B - simplified handling
                TypeReference(
                    fqn = "kotlin.Function",
                    simpleName = "Function",
                    isProjectType = false
                )
            }
            else -> null
        }
    }

    private fun buildFqnFromUserType(userType: KtUserType): String {
        val qualifier = userType.qualifier
        val name = userType.referencedName ?: ""

        return if (qualifier != null) {
            "${buildFqnFromUserType(qualifier)}.$name"
        } else {
            name
        }
    }

    /**
     * Resolves an annotation to its FQN (if it's a project type).
     */
    private fun resolveAnnotationType(
        annotation: KtAnnotationEntry,
        projectTypeIndex: Set<String>
    ): String? {
        val typeRef = annotation.typeReference ?: return null
        val resolved = resolveTypeReference(typeRef, projectTypeIndex)
        return if (resolved?.isProjectType == true) resolved.fqn else null
    }

    private fun extractVisibility(declaration: KtDeclaration): Visibility {
        return when {
            declaration.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PRIVATE_KEYWORD) -> Visibility.PRIVATE
            declaration.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PROTECTED_KEYWORD) -> Visibility.PROTECTED
            declaration.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.INTERNAL_KEYWORD) -> Visibility.INTERNAL
            else -> Visibility.PUBLIC
        }
    }

    private fun getLineNumber(element: PsiElement): Int {
        val document = element.containingFile?.let {
            org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager
                .getInstance(element.project)
                .getDocument(it)
        }
        return document?.getLineNumber(element.startOffset)?.plus(1) ?: 0
    }
}
