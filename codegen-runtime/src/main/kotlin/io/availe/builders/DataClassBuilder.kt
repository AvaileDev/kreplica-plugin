package io.availe.builders

import com.squareup.kotlinpoet.*
import io.availe.*
import io.availe.models.Model
import io.availe.models.Property
import io.availe.models.RegularProperty
import io.availe.models.Variant

internal fun Model.isSerializable(variant: Variant): Boolean {
    return annotationConfigs.any { config ->
        variant in config.variants && config.annotation.qualifiedName == SERIALIZABLE_QUALIFIED_NAME
    }
}

private fun getPatchableClassName(model: Model, variant: Variant): ClassName {
    return if (model.isSerializable(variant)) {
        ClassName(MODELS_PACKAGE_NAME, SERIALIZABLE_PATCHABLE_CLASS_NAME)
    } else {
        ClassName(MODELS_PACKAGE_NAME, PATCHABLE_CLASS_NAME)
    }
}

fun buildDataTransferObjectClass(
    model: Model,
    properties: List<Property>,
    variant: Variant,
    valueClassNames: Map<Pair<String, String>, String>,
    existingValueClasses: Set<String>,
    modelsByName: Map<String, Model>,
    coreInterfaceSpec: TypeSpec?
): TypeSpec {
    val generatedClassName = variant.suffix

    val typeSpecBuilder = TypeSpec.classBuilder(generatedClassName).addModifiers(KModifier.DATA)

    model.annotationConfigs.filter { variant in it.variants }.forEach { config ->
        typeSpecBuilder.addAnnotation(buildAnnotationSpec(config.annotation))
    }

    if (model.isVersionOf != null) {
        val schemaName = model.isVersionOf!! + "Schema"
        val versionClassName = model.name.substringAfterLast('.')

        val versionInterface = ClassName(model.packageName, schemaName, versionClassName)
        typeSpecBuilder.addSuperinterface(versionInterface)

        val variantKindInterface = ClassName(model.packageName, schemaName, "${variant.suffix}Variant")
        typeSpecBuilder.addSuperinterface(variantKindInterface)
    }

    val constructorBuilder = FunSpec.constructorBuilder()
    val isSerializable = model.isSerializable(variant)
    properties.forEach { property ->
        val typeName =
            resolveTypeNameForProperty(
                property,
                variant,
                model,
                valueClassNames,
                existingValueClasses,
                modelsByName,
                isContainerSerializable = isSerializable
            )

        val paramBuilder = ParameterSpec.builder(property.name, typeName)

        var annotationsToApply = property.annotations

        val isWrappedInValueClass = (property.nominalTyping == "ENABLED" &&
                !propertyShouldSkipWrapping(property, existingValueClasses) &&
                property is RegularProperty)

        val shouldFilterContextual = (variant == Variant.PATCH) || isWrappedInValueClass

        if (shouldFilterContextual) {
            annotationsToApply =
                annotationsToApply?.filterNot { it.qualifiedName == "kotlinx.serialization.Contextual" }
        }

        annotationsToApply = annotationsToApply?.filterNot { it.qualifiedName == OPT_IN_QUALIFIED_NAME }

        annotationsToApply?.forEach { annotationModel ->
            paramBuilder.addAnnotation(buildAnnotationSpec(annotationModel))
        }

        if (property.name == SCHEMA_VERSION_PROPERTY_NAME && variant != Variant.PATCH) {
            val isWrapped = (property.nominalTyping == "ENABLED" &&
                    !propertyShouldSkipWrapping(property, existingValueClasses) &&
                    property is RegularProperty)

            if (isWrapped) {
                paramBuilder.defaultValue("%T(%L)", typeName, model.schemaVersion)
            } else {
                paramBuilder.defaultValue("%L", model.schemaVersion)
            }
        }

        if (variant == Variant.PATCH) {
            val patchableClassName = getPatchableClassName(model, variant)
            paramBuilder.defaultValue(
                "%T.%L",
                patchableClassName,
                UNCHANGED_OBJECT_NAME
            )
        }
        constructorBuilder.addParameter(paramBuilder.build())

        val propertySpecBuilder = PropertySpec.builder(property.name, typeName).initializer(property.name)

        typeSpecBuilder.addProperty(propertySpecBuilder.build())
    }
    typeSpecBuilder.primaryConstructor(constructorBuilder.build())

    return typeSpecBuilder.build()
}