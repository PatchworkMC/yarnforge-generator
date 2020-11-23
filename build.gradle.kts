
plugins {
    kotlin("jvm") version "1.4.10"
    application
}

group = "net.patchworkmc"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.github.ajalt.clikt:clikt:3.0.1")
    implementation("org.eclipse.jgit", "org.eclipse.jgit", "5.9.0.202009080501-r")
    implementation("com.jsoniter", "jsoniter", "0.9.19")
}

java.sourceCompatibility = JavaVersion.VERSION_1_8
java.targetCompatibility = JavaVersion.VERSION_1_8

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

val fatJar = tasks.create<Jar>("fatJar") {
    archiveBaseName.set("${project.name}-fat")
    manifest {
        attributes["Main-Class"] =  "net.patchworkmc.yarnforge.generator.MainKt"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    // todo: figure out how to do this without a security exception
    with(tasks.jar.get() as CopySpec)
}
tasks {
    "build" {
        dependsOn(fatJar)
    }
}
application {
    mainClassName = "net.patchworkmc.yarnforge.generator.MainKt"
}