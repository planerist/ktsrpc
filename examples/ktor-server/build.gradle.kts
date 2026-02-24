plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("net.planerist.ktsrpc.example.server.ApplicationKt")
}

dependencies {
    implementation(kotlin("stdlib"))

    implementation(project(":rpc-protocol"))
    implementation(project(":examples:schema"))

    // ktor
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)

    // logging
    implementation(libs.logback.classic)
    implementation(libs.kotlin.logging)

    // coroutines
    implementation(libs.bundles.kotlinx.coroutines.jdk9)

    // serialization
    implementation(libs.bundles.kotlinx.serialization)
}

kotlin.sourceSets["main"].kotlin.setSrcDirs(project.files("src"))
sourceSets["main"].resources.setSrcDirs(project.files("resources"))
