package io.availe.builders

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import io.availe.helpers.*
import io.availe.models.*

internal fun processProperty(
    propertyDeclaration: KSPropertyDeclaration,
    modelVariants: Set<Variant>,
    modelNominalTyping: String?,
    resolver: Resolver,
    frameworkDeclarations: Set<KSClassDeclaration>,
    environment: SymbolProcessorEnvironment
): Property {
    if (propertyDeclaration.isMutable) {
        val propertyName = propertyDeclaration.simpleName.asString()
        val interfaceName = (propertyDeclaration.parent as? KSClassDeclaration)?.simpleName?.asString() ?: "Unknown"
        fail(
            environment,
            """
            KReplica Validation Error: Property '$propertyName' in interface '$interfaceName' is declared as 'var'.
            Source model interfaces for KReplica must use immutable properties ('val').
            Please change '$propertyName' from 'var' to 'val'.
            """.trimIndent()
        )
    }

    val fieldAnnotation =
        propertyDeclaration.annotations.firstOrNull { it.isAnnotation(REPLICATE_PROPERTY_ANNOTATION_NAME) }

    val propertyVariants = if (fieldAnnotation == null) {
        modelVariants
    } else {
        val includeArg = fieldAnnotation.arguments.find { it.name?.asString() == "include" }?.value as? List<*>
        val excludeArg = fieldAnnotation.arguments.find { it.name?.asString() == "exclude" }?.value as? List<*>

        val include = includeArg?.map { Variant.valueOf(it.toString().substringAfterLast('.')) }?.toSet() ?: emptySet()
        val exclude = excludeArg?.map { Variant.valueOf(it.toString().substringAfterLast('.')) }?.toSet() ?: emptySet()

        when {
            include.isNotEmpty() -> include
            exclude.isNotEmpty() -> modelVariants - exclude
            else -> modelVariants
        }
    }

    val propertyNominalTyping = fieldAnnotation?.arguments
        ?.find { it.name?.asString() == "nominalTyping" }
        ?.value?.toString()?.substringAfterLast('.')

    val finalNominalTyping = if (propertyNominalTyping != null && propertyNominalTyping != "INHERIT") {
        propertyNominalTyping
    } else {
        modelNominalTyping
    }

    val useSerializerAnnotation =
        propertyDeclaration.annotations.firstOrNull { it.isAnnotation(REPLICATE_WITH_SERIALIZER_ANNOTATION_NAME) }
    val forceContextualAnnotation =
        propertyDeclaration.annotations.firstOrNull { it.isAnnotation(REPLICATE_FORCE_CONTEXTUAL_ANNOTATION_NAME) }
    val customSerializer = useSerializerAnnotation?.arguments?.firstOrNull()?.value as? String
    val forceContextual = forceContextualAnnotation != null

    val ksType = propertyDeclaration.type.resolve()
    val typeInfo = KSTypeInfo.from(ksType, environment, resolver).toModelTypeInfo(customSerializer, forceContextual)
    val propertyAnnotations: List<AnnotationModel>? =
        propertyDeclaration.annotations.toAnnotationModels(frameworkDeclarations)

    val typeDecl = ksType.declaration as? KSClassDeclaration
    val foreignModelKey: String? = if (typeDecl != null) {
        val parentDecl = typeDecl.parentDeclaration as? KSClassDeclaration
        if (parentDecl != null && parentDecl.simpleName.asString().endsWith("Schema")) {
            val baseName = parentDecl.simpleName.asString().removeSuffix("Schema")
            val versionName = typeDecl.simpleName.asString()
            "$baseName.$versionName"
        } else if (typeDecl.simpleName.asString().endsWith("Schema")) {
            typeDecl.simpleName.asString().removeSuffix("Schema")
        } else {
            null
        }
    } else {
        null
    }

    return if (foreignModelKey != null) {
        ForeignProperty(
            name = propertyDeclaration.simpleName.asString(),
            typeInfo = typeInfo,
            foreignModelName = foreignModelKey,
            variants = propertyVariants,
            annotations = propertyAnnotations,
            nominalTyping = finalNominalTyping
        )
    } else {
        RegularProperty(
            name = propertyDeclaration.simpleName.asString(),
            typeInfo = typeInfo,
            variants = propertyVariants,
            annotations = propertyAnnotations,
            nominalTyping = finalNominalTyping
        )
    }
}