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

// Published: still usable from Kotlin 2.3.
kotlin.compilerOptions {
    apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
    languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))

    implementation(project(":rpc-protocol"))

    // logging
    implementation(libs.logback.classic)

    // serialization
    implementation(libs.bundles.kotlinx.serialization)

    implementation(libs.bundles.kotlinx.datetime)

    // tests
    testImplementation(libs.bundles.test.junit5)
    testImplementation(libs.bundles.kotlinx.coroutines.jdk9)
    testImplementation("com.google.code.findbugs:jsr305:3.0.2")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}

kotlin.sourceSets["main"].kotlin.setSrcDirs(project.files("src"))
kotlin.sourceSets["test"].kotlin.setSrcDirs(project.files("test"))
sourceSets["main"].resources.setSrcDirs(project.files("resources", "build/generated-resources"))
sourceSets["test"].resources.setSrcDirs(project.files("testresources"))