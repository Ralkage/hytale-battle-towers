plugins {
    `java`
    `maven-publish`
    `idea`
    id("de.crazydev22.hytale") version "0.2.2"
}

import java.util.jar.JarFile

group = "com.ralkage"
version = "0.1.2"
val javaVersion = 25

repositories {
    mavenCentral()
    maven("https://maven.hytale-modding.info/releases") {
        name = "HytaleModdingReleases"
    }
}

dependencies {
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.jspecify)
}

hytale {
    fun detectLocalHytaleServerVersion(): String? {
        return try {
            val serverJar = fileTree(layout.buildDirectory.dir("hytale")) {
                include("**/Server/HytaleServer.jar")
            }.files.maxByOrNull { it.lastModified() } ?: return null

            JarFile(serverJar).use { jar ->
                jar.manifest?.mainAttributes?.getValue("Implementation-Version")
            }
        } catch (_: Exception) {
            null
        }
    }

    val serverVersionProperty = (findProperty("server_version") as String?)?.trim()
    val resolvedServerVersion = when {
        serverVersionProperty.isNullOrBlank() || serverVersionProperty == "*" ->
            detectLocalHytaleServerVersion() ?: serverVersionProperty ?: "*"

        else -> serverVersionProperty
    }

    manifest {
        group = findProperty("plugin_group") as String? ?: "BattleTowers"
        main = "com.ralkage.battletowers.BattleTower"
        description = findProperty("plugin_description") as String? ?: ""
        version = project.version.toString()
        // NOTE: '*' is treated as unspecified by the game and triggers "outdated mod" warnings.
        // Resolve to the local Hytale build version when available, unless explicitly overridden.
        serverVersion = resolvedServerVersion
        includesAssetPack = true
        author {
            name = findProperty("plugin_author") as String? ?: ""
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }

    withSourcesJar()
}

tasks.named<ProcessResources>("processResources") {
    val serverVersionProperty = (findProperty("server_version") as String?)?.trim()
    val resolvedServerVersion = when {
        serverVersionProperty.isNullOrBlank() || serverVersionProperty == "*" ->
            try {
                val serverJar = fileTree(layout.buildDirectory.dir("hytale")) {
                    include("**/Server/HytaleServer.jar")
                }.files.maxByOrNull { it.lastModified() }

                if (serverJar != null) {
                    JarFile(serverJar).use { jar ->
                        jar.manifest?.mainAttributes?.getValue("Implementation-Version")
                    }
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            } ?: serverVersionProperty ?: "*"

        else -> serverVersionProperty
    }

    val replaceProperties = mapOf(
        "plugin_group" to findProperty("plugin_group"),
        "plugin_maven_group" to project.group,
        "plugin_name" to project.name,
        "plugin_version" to project.version,
        "server_version" to resolvedServerVersion,

        "plugin_description" to findProperty("plugin_description"),
        "plugin_website" to findProperty("plugin_website"),

        "plugin_main_entrypoint" to findProperty("plugin_main_entrypoint"),
        "plugin_author" to findProperty("plugin_author")
    )

    filesMatching("manifest.json") {
        expand(replaceProperties)
    }

    inputs.properties(replaceProperties)
}

tasks.withType<Jar> {
    manifest {
        attributes["Specification-Title"] = rootProject.name
        attributes["Specification-Version"] = version
        attributes["Implementation-Title"] = project.name
        attributes["Implementation-Version"] =
            providers.environmentVariable("COMMIT_SHA_SHORT")
                .map { "${version}-${it}" }
                .getOrElse(version.toString())
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

idea {
    module {
        isDownloadSources = true
        isDownloadJavadoc = true
    }
}
