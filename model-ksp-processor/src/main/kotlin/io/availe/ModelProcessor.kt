package io.availe

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import io.availe.builders.buildModel
import io.availe.builders.generateStubs
import io.availe.helpers.MODEL_ANNOTATION_NAME
import io.availe.helpers.getFrameworkDeclarations
import io.availe.helpers.isNonHiddenModelAnnotation
import io.availe.models.Model
import kotlinx.serialization.json.Json
import java.io.OutputStreamWriter

class ModelProcessor(private val env: SymbolProcessorEnvironment) : SymbolProcessor {
    private var finalized = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (finalized) {
            return emptyList()
        }

        val modelSymbols = resolver
            .getSymbolsWithAnnotation(MODEL_ANNOTATION_NAME)
            .filterIsInstance<KSClassDeclaration>()
            .filter(::isNonHiddenModelAnnotation)
            .toList()

        if (modelSymbols.isEmpty()) {
            return emptyList()
        }

        val hasErrorTypes = modelSymbols.any {
            it.getAllProperties().any { prop -> prop.type.resolve().isError }
        }

        if (hasErrorTypes) {
            env.logger.info("--- KREPLICA-KSP: Found unresolved types. Generating stubs (Round 1) ---")
            generateStubs(modelSymbols, env.codeGenerator, env)
            return modelSymbols
        }

        env.logger.info("--- KREPLICA-KSP: All types resolved. Generating final models (Round 2) ---")

        val frameworkDecls = getFrameworkDeclarations(resolver)
        val models = modelSymbols.map { decl ->
            buildModel(decl, resolver, frameworkDecls, env)
        }
        writeModelsToFile(models, modelSymbols)
        finalized = true
        env.logger.info("--- KREPLICA-KSP: ModelProcessor FINISHED ---")
        return emptyList()
    }

    private fun writeModelsToFile(models: List<Model>, sourceSymbols: List<KSClassDeclaration>) {
        val jsonText = Json { prettyPrint = true }.encodeToString(models)
        val sourceFiles = sourceSymbols.mapNotNull { it.containingFile }.toTypedArray()
        val dependencies = Dependencies(true, *sourceFiles)
        val file = env.codeGenerator.createNewFile(dependencies, "", "models", "json")
        env.logger.info("--- KREPLICA-KSP: Writing ${models.size} models to models.json ---")
        OutputStreamWriter(file, "UTF-8").use { it.write(jsonText) }
    }
}