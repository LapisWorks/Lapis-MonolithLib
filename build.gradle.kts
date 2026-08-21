plugins {
    kotlin("jvm") version "2.4.0-Beta1"
    id("com.gradleup.shadow") version "9.0.0"
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

group = "mc506lw"

val monolithVersion: String by project
val rebarVersion: String by project
val minecraftVersion: String by project
val javaVersion: String by project

version = monolithVersion

repositories {
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots/")
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }
    maven("https://repo.xenondevs.xyz/releases") {
        name = "InvUI"
    }
    maven("https://repo.metamechanists.org/releases") {
        name = "MetaMechanists Repository"
    }
    maven("https://jitpack.io") {
        name = "JitPack"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${minecraftVersion}.build.+")
    compileOnly("io.github.pylonmc:rebar:$rebarVersion")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
}

kotlin {
    jvmToolchain(javaVersion.toInt())
}

tasks.shadowJar {
    exclude("kotlin/**")
    exclude("org/jetbrains/**")
    exclude("META-INF/kotlin/**")
    exclude("META-INF/services/kotlin.*")
}

tasks.build {
    dependsOn("shadowJar")
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}
