package io.availe.generators

import com.squareup.kotlinpoet.*
import io.availe.OUTPUT_DIRECTORY
import io.availe.SCHEMA_SUFFIX
import io.availe.SERIALIZABLE_QUALIFIED_NAME
import io.availe.builders.*
import io.availe.models.ForeignProperty
import io.availe.models.Model
import io.availe.models.RegularProperty
import io.availe.utils.NamingUtils
import io.availe.utils.fieldsFor
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("io.availe.generators")

fun generateDataClasses(primaryModels: List<Model>, allModels: List<Model>) {
    val allModelsByBaseName = allModels.groupBy { it.isVersionOf ?: it.name }
    val modelsByName = allModels.associateBy { it.name }
    val valueClassNamesByBase = allModelsByBaseName.mapValues { (base, versions) ->
        determineValueClassNames(base, versions)
    }
    val globalValueClasses: Set<String> = valueClassNamesByBase.values.flatMap { it.values }.toSet()
    val primaryModelsByBaseName = primaryModels.groupBy { it.isVersionOf ?: it.name }
    primaryModelsByBaseName.forEach { (baseName, versions) ->
        val mapForBase = valueClassNamesByBase[baseName]!!
        generateSchemaFile(baseName, versions, mapForBase, globalValueClasses, modelsByName)
    }
}

private fun generateSchemaFile(
    baseName: String,
    versions: List<Model>,
    valueClassNames: Map<Pair<String, String>, String>,
    existingValueClasses: Set<String>,
    modelsByName: Map<String, Model>
) {
    val isVersioned = versions.first().isVersionOf != null
    val representativeModel = versions.first()
    val schemaFileName =
        (if (isVersioned) baseName else representativeModel.name.substringAfterLast('.')) + SCHEMA_SUFFIX
    logger.info("Generating schema file: $schemaFileName in package ${representativeModel.packageName}")
    val fileBuilder = FileSpec.builder(representativeModel.packageName, schemaFileName)
        .addFileComment(FILE_HEADER_COMMENT)
        .addOptInMarkersForModels(versions)

    val isGloballySerializable =
        representativeModel.annotationConfigs.any { it.annotation.qualifiedName == SERIALIZABLE_QUALIFIED_NAME }

    if (isVersioned) {
        val topLevelClassBuilder = TypeSpec.interfaceBuilder(schemaFileName)
            .addModifiers(KModifier.SEALED)
            .apply {
                if (isGloballySerializable) {
                    addAnnotation(ClassName("kotlinx.serialization", "Serializable"))
                }
            }
            .addKdoc(TOP_LEVEL_CLASS_KDOC, baseName)

        val allVariants = versions.flatMap { it.variants }.toSet()
        allVariants.forEach { variant ->
            val interfaceName = "${variant.suffix}Variant"
            val variantInterfaceSpec = TypeSpec.interfaceBuilder(interfaceName)
                .addModifiers(KModifier.PUBLIC, KModifier.SEALED)
                .addSuperinterface(ClassName(representativeModel.packageName, schemaFileName))
                .build()
            topLevelClassBuilder.addType(variantInterfaceSpec)
        }

        versions.forEach { version ->
            val dtos =
                generateDataTransferObjects(version, valueClassNames, existingValueClasses, modelsByName)
            val versionClassName = version.name.substringAfterLast('.')
            val versionClass = TypeSpec.interfaceBuilder(versionClassName)
                .addModifiers(KModifier.SEALED)
                .addSuperinterface(ClassName(version.packageName, schemaFileName))
                .apply {
                    val isVersionSerializable =
                        version.annotationConfigs.any { it.annotation.qualifiedName == SERIALIZABLE_QUALIFIED_NAME }
                    if (isVersionSerializable) {
                        addAnnotation(ClassName("kotlinx.serialization", "Serializable"))
                    }
                }
                .addKdoc(generateVersionBoxKdoc(versionClassName, version.schemaVersion!!))
                .addTypes(dtos)
                .build()
            topLevelClassBuilder.addType(versionClass)
        }
        fileBuilder.addType(topLevelClassBuilder.build())
    } else {
        val model = versions.first()
        val dtos = generateDataTransferObjects(model, valueClassNames, existingValueClasses, modelsByName)
        val schemaInterfaceName = ClassName(representativeModel.packageName, schemaFileName)
        val schemaBuilder = TypeSpec.interfaceBuilder(schemaInterfaceName)
            .addModifiers(KModifier.SEALED)
            .addKdoc("A sealed hierarchy representing all variants of the %L data model.", baseName)

        if (isGloballySerializable) {
            schemaBuilder.addAnnotation(ClassName("kotlinx.serialization", "Serializable"))
        }

        dtos.forEach { dtoSpec ->
            schemaBuilder.addType(
                dtoSpec.toBuilder()
                    .addSuperinterface(schemaInterfaceName)
                    .build()
            )
        }
        fileBuilder.addType(schemaBuilder.build())
    }

    generateAndAddValueClasses(
        fileBuilder = fileBuilder,
        baseName = baseName,
        versions = versions,
        valueClassNames = valueClassNames,
        existingValueClasses = existingValueClasses
    )
    fileBuilder.build().writeTo(OUTPUT_DIRECTORY)
}

private fun generateDataTransferObjects(
    model: Model,
    valueClassNames: Map<Pair<String, String>, String>,
    existingValueClasses: Set<String>,
    modelsByName: Map<String, Model>
): List<TypeSpec> {
    return model.variants.mapNotNull { variant ->
        val fields = fieldsFor(model, variant)
        if (fields.isNotEmpty()) {
            buildDataTransferObjectClass(
                model = model,
                properties = fields,
                variant = variant,
                valueClassNames = valueClassNames,
                existingValueClasses = existingValueClasses,
                modelsByName = modelsByName,
                coreInterfaceSpec = null
            )
        } else null
    }
}


private fun determineValueClassNames(
    baseName: String,
    versions: List<Model>
): Map<Pair<String, String>, String> {
    if (versions.size == 1 && versions.first().isVersionOf == null) {
        val model = versions.first()
        return model.properties
            .filterIsInstance<RegularProperty>()
            .associate { property ->
                (model.name to property.name) to NamingUtils.generateValueClassName(model.name, property.name)
            }
    }
    val propertySignatures = mutableMapOf<String, String>()
    val conflictingProperties = mutableSetOf<String>()
    versions.forEach { version ->
        version.properties.filterIsInstance<RegularProperty>().forEach { property ->
            if (conflictingProperties.contains(property.name)) return@forEach
            val signature = property.typeInfo.qualifiedName
            if (propertySignatures.containsKey(property.name)) {
                if (propertySignatures[property.name] != signature) {
                    conflictingProperties.add(property.name)
                    propertySignatures.remove(property.name)
                }
            } else {
                propertySignatures[property.name] = signature
            }
        }
    }
    return versions.flatMap { version ->
        version.properties.filterIsInstance<RegularProperty>().map { property ->
            val valueClassName = if (conflictingProperties.contains(property.name)) {
                val versionClassName = version.name.substringAfterLast('.')
                NamingUtils.generateValueClassName("${baseName}${versionClassName}", property.name)
            } else {
                NamingUtils.generateValueClassName(baseName, property.name)
            }
            (version.name to property.name) to valueClassName
        }
    }.toMap()
}

private fun propertyNeedsValueClassWrapper(
    property: RegularProperty,
    modelNominalTyping: String?,
    existingValueClasses: Set<String>
): Boolean {
    val finalTyping = property.nominalTyping ?: modelNominalTyping
    if (finalTyping != "ENABLED") return false

    val skip = property.typeInfo.isEnum ||
            property.typeInfo.isValueClass ||
            property.typeInfo.isDataClass ||
            property is ForeignProperty ||
            existingValueClasses.contains(property.typeInfo.qualifiedName) ||
            property.typeInfo.qualifiedName.startsWith("kotlin.collections.")

    logger.debug(
        "Evaluating property='${property.name}' for value class wrapping. Result: ${!skip} " +
                "(isEnum=${property.typeInfo.isEnum}, isValueClass=${property.typeInfo.isValueClass}, " +
                "isDataClass=${property.typeInfo.isDataClass}, isForeign=${property is ForeignProperty})"
    )

    return !skip
}

private fun generateAndAddValueClasses(
    fileBuilder: FileSpec.Builder,
    baseName: String,
    versions: List<Model>,
    valueClassNames: Map<Pair<String, String>, String>,
    existingValueClasses: Set<String>
) {
    val allValueClassData =
        versions.flatMap { version ->
            val modelNominalTyping = version.nominalTyping
            version.properties
                .filterIsInstance<RegularProperty>()
                .filter { propertyNeedsValueClassWrapper(it, modelNominalTyping, existingValueClasses) }
                .mapNotNull { property ->
                    valueClassNames[version.name to property.name]?.let { className ->
                        Triple(property, version, className)
                    }
                }
        }

    val groupedData = allValueClassData.groupBy { it.third }

    logger.debug("Found ${groupedData.size} potential value classes to generate for $baseName.")
    if (groupedData.isEmpty()) return

    val specData = groupedData.map { (className, triples) ->
        val representativeProperty = triples.first().first
        val isSerializable = triples.any { (_, version, _) ->
            version.annotationConfigs.any { it.annotation.qualifiedName == SERIALIZABLE_QUALIFIED_NAME }
        }

        object {
            val spec = buildValueClass(className, representativeProperty, isSerializable)
            val propertyName = representativeProperty.name
        }
    }

    val isStandalone = versions.size == 1 && versions.first().isVersionOf == null
    if (isStandalone) {
        val valueClassSpecs = specData.map { it.spec }.sortedBy { it.name }
        fileBuilder.addTypesWithHeader(valueClassSpecs, STANDALONE_VALUE_CLASSES_KDOC)
    } else {
        val conflictingPropertyNames = valueClassNames.values
            .filter { it.startsWith(baseName + "V") }
            .map { it.removePrefix(baseName).drop(2).replaceFirstChar { c -> c.lowercaseChar() } }
            .toSet()

        val (conflictedData, sharedData) = specData.partition {
            conflictingPropertyNames.contains(it.propertyName)
        }

        if (sharedData.isNotEmpty()) {
            val sharedSpecs = sharedData.map { it.spec }.sortedBy { it.name }
            fileBuilder.addTypesWithHeader(sharedSpecs, SHARED_VALUE_CLASSES_KDOC)
        }
        if (conflictedData.isNotEmpty()) {
            val conflictedSpecs = conflictedData.map { it.spec }.sortedBy { it.name }
            fileBuilder.addTypesWithHeader(conflictedSpecs, CONFLICTED_VALUE_CLASSES_KDOC)
        }
    }
}

private fun FileSpec.Builder.addTypesWithHeader(
    specs: List<TypeSpec>,
    header: String
) {
    if (specs.isEmpty()) return
    val firstSpecBuilder = specs.first().toBuilder()
    firstSpecBuilder.kdoc.clear()
    firstSpecBuilder.addKdoc(header)
    addType(firstSpecBuilder.build())
    specs.drop(1).forEach { addType(it) }
}

private fun FileSpec.Builder.addOptInMarkersForModels(
    models: List<Model>
): FileSpec.Builder {
    val distinctMarkers = models.flatMap { it.optInMarkers ?: emptyList() }.distinct()
    if (distinctMarkers.isNotEmpty()) {
        val format = distinctMarkers.joinToString(", ") { "%T::class" }
        val args = distinctMarkers.map { fq ->
            val pkg = fq.substringBeforeLast('.')
            val cls = fq.substringAfterLast('.')
            ClassName(pkg, cls)
        }.toTypedArray()
        addAnnotation(
            AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
                .addMember(format, *args)
                .build()
        )
    }
    return this
}