@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

package io.availe.gradle

import io.availe.KReplicaExtension
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

fun registerKReplicaCodegenTask(
    project: Project,
    extension: KReplicaExtension,
    kspTaskName: String,
    projectVersion: String
): TaskProvider<JavaExec> {
    val cleanKReplicaOutput = project.tasks.register("cleanKReplicaOutput", Delete::class.java) {
        delete(project.layout.buildDirectory.dir("generated-src/kotlin-poet"))
        delete(project.layout.buildDirectory.file("generated/ksp/main/resources/models.json"))
        delete(project.layout.buildDirectory.file("generated/ksp/metadata/commonMain/resources/models.json"))
    }

    project.tasks.matching { it.name == kspTaskName }.configureEach {
        dependsOn(cleanKReplicaOutput)
    }

    val codegenConfiguration = project.configurations.create("kreplicaCodegen") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
    project.dependencies.add(codegenConfiguration.name, "io.availe:codegen-runtime:$projectVersion")

    val runCodegen = project.tasks.register("runKReplicaCodegen", JavaExec::class.java) {
        group = "kreplica"
        description = "Runs the KReplica code generator"
        classpath = codegenConfiguration
        mainClass.set("io.availe.ApplicationKt")
        dependsOn(kspTaskName)
        inputs.file(extension.primaryModelJson).withPathSensitivity(PathSensitivity.ABSOLUTE)
            .withPropertyName("primaryModelJson")
        inputs.files(extension.contextModelJsons).withPathSensitivity(PathSensitivity.ABSOLUTE)
            .withPropertyName("contextModelJsons")

        onlyIf {
            extension.primaryModelJson.get().asFile.exists()
        }

        doFirst {
            project.delete {
                delete(project.layout.buildDirectory.dir("generated/ksp/main/kotlin"))
                delete(project.layout.buildDirectory.dir("generated/ksp/metadata/commonMain/kotlin"))
            }

            val execArgs = mutableListOf<String>()
            val primaryFile = extension.primaryModelJson.get().asFile
            if (primaryFile.exists()) {
                execArgs.add(primaryFile.absolutePath)
            } else {
                throw GradleException("KReplica: Primary input file does not exist: ${primaryFile.path}. The KSP task may have failed or produced no output.")
            }
            extension.contextModelJsons.files.forEach {
                if (it.exists()) execArgs.add(it.absolutePath)
            }
            args = execArgs
        }
    }

    project.extensions.findByType(KotlinMultiplatformExtension::class.java)?.let { kmpExt ->
        kmpExt.targets.configureEach {
            compilations.configureEach {
                if (compilationName == KotlinCompilation.MAIN_COMPILATION_NAME) {
                    compileTaskProvider.configure {
                        if (name != kspTaskName) {
                            dependsOn(runCodegen)
                        }
                    }
                }
            }
        }
    } ?: project.tasks.withType<KotlinCompile>().configureEach {
        if (name == "compileKotlin" && name != kspTaskName) {
            dependsOn(runCodegen)
        }
    }

    return runCodegen
}