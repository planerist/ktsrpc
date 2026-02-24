plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
    implementation(libs.bundles.kotlinx.coroutines.jdk9)

    implementation(project(":rpc-protocol"))
    implementation(project(":rpc-gen"))

    // serialization
    implementation(libs.bundles.kotlinx.serialization)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}

kotlin.sourceSets["main"].kotlin.srcDirs("src")
kotlin.sourceSets["test"].kotlin.srcDirs("test")

sourceSets["main"].resources.srcDirs("resources")
sourceSets["test"].resources.srcDirs("testresources")

// TypeScript code generation task — customize the class list for your project
tasks.register<JavaExec>("generateTsRpc") {
    dependsOn("assemble")

    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(libs.versions.jdk.get().toInt())
    }

    group = "export"
    description = "Generate TypeScript RPC definitions from Kotlin interfaces"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("net.planerist.ktsrpc.gen.RpcExporter")

    args = listOf(
        "--output", "../../examples/frontend/src/api/rpc.ts",
        // List RPC interface classes to generate TypeScript for:
        "net.planerist.ktsrpc.example.GreeterServiceRpc",
        "net.planerist.ktsrpc.example.TodoServiceRpc",
        "net.planerist.ktsrpc.example.ChatServiceRpc",
    )
}
