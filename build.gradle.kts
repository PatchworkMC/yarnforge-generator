
plugins {
    kotlin("jvm") version "1.4.10"
}

group = "org.example"
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

java.sourceCompatibility = org.gradle.api.JavaVersion.VERSION_1_8
java.targetCompatibility = org.gradle.api.JavaVersion.VERSION_1_8

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "1.8"
    }
}