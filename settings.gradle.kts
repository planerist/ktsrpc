pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "kts-rpc"

// Core library modules
include("rpc-protocol")
include("rpc-gen")

// Example project
include("examples:schema")
include("examples:ktor-server")
