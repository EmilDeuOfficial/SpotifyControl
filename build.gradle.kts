import java.util.Properties

plugins {
    id("fabric-loom") version "1.17-SNAPSHOT"
    id("maven-publish")
}

val buildMatrix = Properties().apply {
    file("versions.properties").inputStream().use { load(it) }
}

val minecraftVersion: String = (findProperty("mc") ?: project.property("default_minecraft_version")) as String
val matrixEntry: String = buildMatrix.getProperty(minecraftVersion)
    ?: throw GradleException("Unknown Minecraft version $minecraftVersion, add it to versions.properties")
val matrixParts = matrixEntry.split("|")
val fabricVersion = matrixParts[0]
val platformId = matrixParts[1]
val minecraftDependency = matrixParts[2]

version = "${project.property("mod_version")}+$minecraftVersion"
group = project.property("maven_group") as String
base.archivesName.set(project.property("archives_base_name") as String)

sourceSets.main {
    java.srcDir("src/platform-$platformId/java")
}

loom {
    mixin {
        // Loom 1.17 turned the mixin annotation processor off by default and instead rewrites the
        // mixin annotations in place during remapJar. The universal jar copies shared classes and
        // shared resources out of the 1.21.1 build only, so a single refmap file that travels with
        // those classes is the form that can be opened and checked after the fact.
        useLegacyMixinAp.set(true)
        // Must stay identical to the "refmap" entry in spotifycontrol.mixins.json. Loom names the
        // generated file from here and never reads that name back out of the mixin config, so the
        // two drifting apart is a silent crash at mixin apply time.
        defaultRefmapName.set("spotifycontrol.refmap.json")
    }
}

repositories {
    maven("https://maven.maxhenkel.de/repository/public") { name = "Max Henkel" }
    maven("https://maven.terraformersmc.com/releases") { name = "TerraformersMC" }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")

    compileOnly("de.maxhenkel.voicechat:voicechat-api:${project.property("voicechat_api_version")}")
    modCompileOnly("com.terraformersmc:modmenu:${project.property("modmenu_version")}")
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_dependency", minecraftDependency)
    filteringCharset = "UTF-8"
    filesMatching("fabric.mod.json") {
        expand(
            mapOf(
                "version" to project.version,
                "minecraft_dependency" to minecraftDependency
            )
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.register("printBuildTargets") {
    val targets = buildMatrix.stringPropertyNames().toList()
    doLast {
        targets.forEach { println(it) }
    }
}

tasks.register<Copy>("stageGenerationJar") {
    dependsOn(tasks.remapJar)
    from(tasks.remapJar)
    into(layout.buildDirectory.dir("generations"))
    rename { "SpotifyControl-$minecraftVersion.jar" }
}

val generationRepresentatives = mapOf(
    "a" to "1.21.1",
    "b" to "1.21.4",
    "c" to "1.21.5",
    "d" to "1.21.8",
    "e" to "1.21.10",
    "f" to "1.21.11"
)

val universalModJson = tasks.register("universalModJson") {
    val template = file("src/main/resources/fabric.mod.json")
    val output = layout.buildDirectory.file("universal/fabric.mod.json")
    val modVersion = project.property("mod_version") as String
    inputs.file(template)
    // Without the version as an input Gradle kept the file from the last build after a version bump.
    inputs.property("modVersion", modVersion)
    outputs.file(output)
    doLast {
        val rendered = template.readText()
            .replace("\${version}", modVersion)
            .replace("\${minecraft_dependency}", ">=1.21 <1.22")
        val target = output.get().asFile
        target.parentFile.mkdirs()
        target.writeText(rendered)
    }
}

tasks.register<Jar>("universalJar") {
    dependsOn(universalModJson)
    val modVersion = project.property("mod_version") as String
    val stagingDir = layout.buildDirectory.dir("generations")

    archiveFileName.set("SpotifyControl_${modVersion}_1.21.x.jar")
    destinationDirectory.set(layout.projectDirectory.dir("versions"))
    duplicatesStrategy = DuplicatesStrategy.FAIL

    from(universalModJson)

    val baseJar = stagingDir.get().file("SpotifyControl-${generationRepresentatives["a"]}.jar").asFile
    from(zipTree(baseJar)) {
        exclude("fabric.mod.json")
        exclude("META-INF/**")
        exclude("de/emilo/spotifycontrol/platform/impl/**")
    }

    generationRepresentatives.forEach { (generation, mcVersion) ->
        val source = stagingDir.get().file("SpotifyControl-$mcVersion.jar").asFile
        from(zipTree(source)) {
            include("de/emilo/spotifycontrol/platform/impl/$generation/**")
        }
    }

    doFirst {
        val missing = generationRepresentatives.values
            .map { stagingDir.get().file("SpotifyControl-$it.jar").asFile }
            .filterNot { it.exists() }
        if (missing.isNotEmpty()) {
            throw GradleException("Stage the generation jars first (./gradlew stageGenerationJar -Pmc=<version>), missing: ${missing.joinToString { it.name }}")
        }
    }
}

tasks.register("printGenerationTargets") {
    val targets = generationRepresentatives.values.toList()
    doLast {
        targets.forEach { println(it) }
    }
}
