import java.security.MessageDigest

plugins {
    kotlin("jvm") version "2.4.21"
    id("com.gradleup.shadow") version "9.2.2"
}

group = "net.nakaymi"
version = "0.1.2"

val paperVersion = "26.3.build.257-stable"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.xenondevs.xyz/releases")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
    compileOnly("io.netty:netty-codec:4.2.16.Final")
    implementation(kotlin("stdlib"))
    compileOnly("org.yaml:snakeyaml:2.2")
    implementation("xyz.xenondevs.invui:invui:2.5.1")
    testImplementation("org.yaml:snakeyaml:2.2")
    testImplementation("io.papermc.paper:paper-api:26.3.build.200-stable") // must match MockBukkit manifest Paper-Version
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.3:4.123.0") // QA: in-JVM Player/event tests
    testImplementation("io.netty:netty-codec:4.2.16.Final")
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin { jvmToolchain(25) }

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
}

val generateContentIndex = tasks.register("generateContentIndex") {
    val content = layout.projectDirectory.dir("src/main/resources/content")
    val output = layout.buildDirectory.file("generated/content-index.txt")
    inputs.dir(content)
    outputs.file(output)
    doLast {
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText(content.asFile.walkTopDown().filter { it.isFile && it.extension == "yml" }
                .map { "content/" + it.relativeTo(content.asFile).invariantSeparatorsPath }.sorted().joinToString("\n", postfix = "\n"))
        }
    }
}

tasks.processResources {
    dependsOn(generateContentIndex)
    from(layout.buildDirectory.file("generated/content-index.txt"))
    from(layout.buildDirectory.file("generated/resourcepack/etherealchronicle-resourcepack.zip")) { into("resourcepack") }
    from(layout.buildDirectory.file("generated/resourcepack/etherealchronicle-resourcepack.sha1")) { into("resourcepack") }
    filesMatching("plugin.yml") { expand("version" to project.version) }
    filesMatching("paper-plugin.yml") { expand("version" to project.version) }
}

val packageResourcePack = tasks.register<Zip>("packageResourcePack") {
    from(layout.projectDirectory.dir("resourcepack")) {
        include("pack.mcmeta", "pack.png", "assets/**")
    }
    archiveFileName.set("etherealchronicle-resourcepack.zip")
    destinationDirectory.set(layout.buildDirectory.dir("generated/resourcepack"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val resourcePackSha1 = tasks.register("resourcePackSha1") {
    dependsOn(packageResourcePack)
    val archive = packageResourcePack.flatMap { it.archiveFile }
    val checksum = layout.buildDirectory.file("generated/resourcepack/etherealchronicle-resourcepack.sha1")
    inputs.file(archive)
    outputs.file(checksum)
    doLast {
        val hash = MessageDigest.getInstance("SHA-1")
            .digest(archive.get().asFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        checksum.get().asFile.apply {
            parentFile.mkdirs()
            writeText("$hash\n")
        }
        logger.lifecycle("Resource pack SHA-1: $hash")
    }
}

tasks.processResources { dependsOn(resourcePackSha1) }
tasks.test { dependsOn(resourcePackSha1) }

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveFileName.set("EtherealChronicle-${project.version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    relocate("xyz.xenondevs.invui", "net.nakaymi.etherealchronicle.libs.invui")
    mergeServiceFiles()
}

tasks.assemble { dependsOn(tasks.shadowJar) }
