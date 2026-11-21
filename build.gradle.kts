plugins {
    kotlin("jvm") version "2.4.21"
}

group = "net.nakaymi"
version = "0.1.0"

val paperVersion = "26.3.build.257-stable"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
    implementation(kotlin("stdlib"))
    compileOnly("org.yaml:snakeyaml:2.2")
    testImplementation("org.yaml:snakeyaml:2.2")
    testImplementation("io.papermc.paper:paper-api:26.3.build.200-stable") // must match MockBukkit manifest Paper-Version
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.3:4.123.0") // QA: in-JVM Player/event tests
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin { jvmToolchain(25) }

tasks.test { useJUnitPlatform() }

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
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.jar {
    archiveFileName.set("EtherealChronicle-${project.version}.jar")
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}



