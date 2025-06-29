package io.availe.builders

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec
import io.availe.helpers.determineVersioningInfo
import java.io.OutputStreamWriter

private data class StubInfo(
    val packageName: String,
    val schemaName: String,
    val sourceFiles: Set<KSClassDeclaration>,
    val versionNames: Set<String>
)

fun generateStubs(
    symbols: List<KSClassDeclaration>,
    codeGenerator: CodeGenerator,
    environment: SymbolProcessorEnvironment
) {
    val stubsBySchema = mutableMapOf<String, StubInfo>()

    symbols.forEach { decl ->
        val versioningInfo = determineVersioningInfo(decl, environment)
        val (schemaName, modelName) = if (versioningInfo != null) {
            "${versioningInfo.baseModelName}Schema" to decl.simpleName.asString()
        } else {
            "${decl.simpleName.asString()}Schema" to null
        }

        val existingStub = stubsBySchema.getOrPut(schemaName) {
            StubInfo(decl.packageName.asString(), schemaName, emptySet(), emptySet())
        }

        val updatedVersionNames = modelName?.let { existingStub.versionNames + it } ?: existingStub.versionNames
        val updatedSourceFiles = existingStub.sourceFiles + decl

        stubsBySchema[schemaName] =
            existingStub.copy(versionNames = updatedVersionNames, sourceFiles = updatedSourceFiles)
    }

    stubsBySchema.values.forEach { stub ->
        val fileBuilder = FileSpec.builder(stub.packageName, stub.schemaName)
        val schemaInterfaceBuilder = TypeSpec.interfaceBuilder(stub.schemaName)
            .addModifiers(KModifier.PUBLIC, KModifier.SEALED)

        stub.versionNames.sorted().forEach { versionName ->
            val versionInterface = TypeSpec.interfaceBuilder(versionName)
                .addModifiers(KModifier.PUBLIC, KModifier.SEALED)
                .build()
            schemaInterfaceBuilder.addType(versionInterface)
        }

        fileBuilder.addType(schemaInterfaceBuilder.build())

        val dependencies = Dependencies(false, *stub.sourceFiles.mapNotNull { it.containingFile }.toTypedArray())
        val file = codeGenerator.createNewFile(dependencies, stub.packageName, stub.schemaName)

        OutputStreamWriter(file, "UTF-8").use {
            fileBuilder.build().writeTo(it)
        }
    }
}