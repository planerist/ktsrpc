plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))

    // logging
    implementation(libs.logback.classic)
    implementation(libs.kotlin.logging)

    // ktor
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // coroutines
    implementation(libs.bundles.kotlinx.coroutines.jdk9)

    // serialization
    implementation(libs.bundles.kotlinx.serialization)

    testImplementation(libs.bundles.ktor.test)
    testImplementation(libs.bundles.test.junit5)
    testImplementation(libs.bundles.ktor.client)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}

kotlin.sourceSets["main"].kotlin.setSrcDirs(project.files("src"))
kotlin.sourceSets["test"].kotlin.setSrcDirs(project.files("test"))
sourceSets["main"].resources.setSrcDirs(project.files("resources", "build/generated-resources"))
sourceSets["test"].resources.setSrcDirs(project.files("testresources"))