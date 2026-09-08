import org.apache.commons.lang3.SystemUtils
import java.time.LocalDate
import java.time.format.DateTimeFormatter
plugins {
    idea
    java
    id("gg.essential.loom") version "0.10.0.+"
    id("dev.architectury.architectury-pack200") version "0.1.3"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}
val baseGroup: String by project
val mcVersion: String by project
val mixinGroup = "$baseGroup.mixin"

val requestedTasks: String = gradle.startParameter.taskNames.joinToString(" ")
val betaBuild: Boolean = requestedTasks.contains("buildBeta", ignoreCase = true)
val releaseBuild: Boolean =
    betaBuild || requestedTasks.contains("buildLatest", ignoreCase = true)
val channelName: String = if (betaBuild) "Beta" else "Latest"
val majorKey: String = if (betaBuild) "betaMajor" else "latestMajor"
val minorKey: String = if (betaBuild) "betaMinor" else "latestMinor"
val propertiesFile = file("gradle.properties")

fun readCounter(key: String, fallback: Int): Int =
    (project.findProperty(key) as String?)?.trim()?.toIntOrNull() ?: fallback

fun writeCounter(key: String, value: Int) {
    val text = propertiesFile.readText()
    val pattern = Regex("(?m)^[ \\t]*" + Regex.escape(key) + "[ \\t]*=.*$")
    propertiesFile.writeText(
        if (pattern.containsMatchIn(text)) pattern.replace(text, "$key = $value")
        else text.trimEnd() + "\n$key = $value\n"
    )
}

val buildDate: String = LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"))
val buildMajor: Int = readCounter(majorKey, 1)
val buildMinor: Int = readCounter(minorKey, 0)

version = "$buildDate-$channelName.v$buildMajor.$buildMinor"

if (releaseBuild) {
    writeCounter(minorKey, buildMinor + 1)
    println("myau build: $version")
}
val modid: String by project
val jarName: String by project
val transformerFile = file("src/main/resources/accesstransformer.cfg")
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(8))
}
loom {
    log4jConfigs.from(file("log4j2.xml"))
    launchConfigs {
        "client" {
            property("mixin.debug", "true")
            arg("--tweakClass", "org.spongepowered.asm.launch.MixinTweaker")
        }
    }
    runConfigs {
        "client" {
            if (SystemUtils.IS_OS_MAC_OSX) {
                vmArgs.remove("-XstartOnFirstThread")
            }
        }
        remove(getByName("server"))
    }
    forge {
        pack200Provider.set(dev.architectury.pack200.java.Pack200Adapter())
        mixinConfig("mixins.$modid.json")
	    if (transformerFile.exists()) {
			println("Installing access transformer")
		    accessTransformer(transformerFile)
	    }
    }
    mixin {
        defaultRefmapName.set("mixins.$modid.refmap.json")
    }
}
sourceSets.main {
    output.setResourcesDir(sourceSets.main.flatMap { it.java.classesDirectory })
}
repositories {
    mavenCentral()
    maven("https://repo.spongepowered.org/maven/")
    maven("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1")
}
val shadowImpl: Configuration by configurations.creating {
    configurations.implementation.get().extendsFrom(this)
}
dependencies {
    minecraft("com.mojang:minecraft:1.8.9")
    mappings("de.oceanlabs.mcp:mcp_stable:22-1.8.9")
    forge("net.minecraftforge:forge:1.8.9-11.15.1.2318-1.8.9")
    shadowImpl("org.spongepowered:mixin:0.7.11-SNAPSHOT") {
        isTransitive = false
    }
    annotationProcessor("org.spongepowered:mixin:0.8.5-SNAPSHOT")
    shadowImpl("org.ow2.asm:asm:9.7")
    shadowImpl("org.ow2.asm:asm-tree:9.7")
    shadowImpl("org.ow2.asm:asm-commons:9.7")
    runtimeOnly("me.djtheredstoner:DevAuth-forge-legacy:1.2.1")
}
tasks.withType(JavaCompile::class) {
    options.encoding = "UTF-8"
}
tasks.withType(org.gradle.jvm.tasks.Jar::class) {
    archiveBaseName.set(jarName)
    manifest.attributes.run {
        this["FMLCorePluginContainsFMLMod"] = "true"
        this["ForceLoadAsMod"] = "true"
        this["TweakClass"] = "org.spongepowered.asm.launch.MixinTweaker"
        this["Premain-Class"] = "myau.inject.Agent"
        this["Agent-Class"] = "myau.inject.Agent"
        this["Can-Retransform-Classes"] = "true"
        this["Can-Redefine-Classes"] = "true"
        this["Main-Class"] = "myau.inject.Injector"
        this["MixinConfigs"] = "mixins.$modid.json"
	    if (transformerFile.exists())
			this["FMLAT"] = "${modid}_at.cfg"
    }
}
tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("mcversion", mcVersion)
    inputs.property("modid", modid)
    inputs.property("basePackage", baseGroup)
    filesMatching(listOf("mcmod.info", "mixins.$modid.json","version.json")) {
        expand(inputs.properties)
    }
    rename("accesstransformer.cfg", "META-INF/${modid}_at.cfg")
}
val remapJar by tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveClassifier.set("")
    from(tasks.shadowJar)
    input.set(tasks.shadowJar.get().archiveFile)
}
tasks.jar {
    archiveClassifier.set("without-deps")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates"))
    from(layout.buildDirectory.dir("classes/java/main"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
tasks.shadowJar {
    destinationDirectory.set(layout.buildDirectory.dir("intermediates"))
    archiveClassifier.set("non-obfuscated-with-deps")
    configurations = listOf(shadowImpl)
    doLast {
        configurations.forEach {
            println("Copying dependencies into mod: ${it.files}")
        }
    }
    relocate("org.objectweb.asm", "myau.shadow.asm")
}
tasks.assemble.get().dependsOn(tasks.remapJar)

val nativesDir = file("../myau-natives")
val nativesBuildDir = File(nativesDir, "build")
val nativeJavaHome: String = System.getenv("JAVA_HOME") ?: System.getProperty("java.home")

fun cmakeConfigure(extra: List<String>) {
    providers.exec {
        commandLine(
            listOf("cmake", "-S", nativesDir.absolutePath, "-B", nativesBuildDir.absolutePath) + extra
        )
        environment("JAVA_HOME", nativeJavaHome)
    }.standardOutput.asText.get().let { println(it) }
}

fun cmakeBuild() {
    providers.exec {
        commandLine("cmake", "--build", nativesBuildDir.absolutePath, "--config", "Release")
        environment("JAVA_HOME", nativeJavaHome)
    }.standardOutput.asText.get().let { println(it) }
}

fun latestJar(): File {
    val expected = File(layout.buildDirectory.get().asFile, "libs/$jarName-$version.jar")
    if (!expected.isFile) {
        throw GradleException("client jar not found: ${expected.name}")
    }
    return expected
}

fun buildNative(outputName: String) {
    val jar = latestJar()
    println("client jar: ${jar.name}")
    cmakeConfigure(listOf("-DCLIENT_JAR=${jar.invariantSeparatorsPath}"))
    cmakeBuild()
    val built = File(nativesBuildDir, "Release/myau_native.dll")
    val target = File(nativesBuildDir, "Release/$outputName")
    if (built.absolutePath != target.absolutePath) {
        built.copyTo(target, overwrite = true)
        built.delete()
    }
    println("output: $target")
}

tasks.register("buildDll") {
    group = "myau"
    description = "Builds myau_native.dll from the current jar without bumping the version."
    dependsOn(tasks.assemble)
    doLast { buildNative("myau_native.dll") }
}

tasks.register("buildLatest") {
    group = "myau"
    description = "Builds the Latest channel payload and bumps the version."
    dependsOn(tasks.assemble)
    doLast { buildNative("myau_native.dll") }
}

tasks.register("buildBeta") {
    group = "myau"
    description = "Builds the Beta channel payload and bumps the version."
    dependsOn(tasks.assemble)
    doLast { buildNative("myau_native_beta.dll") }
}
