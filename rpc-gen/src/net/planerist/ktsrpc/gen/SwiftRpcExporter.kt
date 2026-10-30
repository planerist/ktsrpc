package net.planerist.ktsrpc.gen

import net.planerist.ktsrpc.gen.swiftGenerator.SwiftGenerator
import java.io.File
import kotlin.reflect.KClass
import kotlin.system.exitProcess

/**
 * Swift RPC code generator: Codable types and typed `RpcClient` methods for the KtsRpc Swift package.
 *
 * Usage:
 *   java -cp ... net.planerist.ktsrpc.gen.SwiftRpcExporter \
 *       --output path/to/Rpc.swift \
 *       --context-annotation com.example.RpcContext \
 *       --map com.example.UserId=UserId \
 *       com.example.GreeterRpc \
 *       com.example.TodoRpc
 *
 * Arguments:
 *   --output <path>                Output Swift file path (required)
 *   --context-annotation <class>   Annotation of the server-side context parameter, left out of
 *                                  the client (a parameter named `context` is always left out)
 *   --map <class>=<SwiftType>      A Kotlin class with a hand-written Swift type; repeatable. The
 *                                  date types (OffsetDateTime, LocalDate, Instant) are mapped already
 *   --runtime-module <name>        Swift module to import the runtime from (default: KtsRpc)
 *   --no-runtime-import            The generated file is compiled into the runtime's own module
 *   [classes...]                   Fully qualified names of RPC interface classes
 */
object SwiftRpcExporter {
    @JvmStatic
    fun main(args: Array<String>) {
        var outputPath: String? = null
        var contextAnnotation: KClass<out Annotation>? = null
        var runtimeModule: String? = "KtsRpc"
        val mappings = SwiftGenerator.defaultMappings.toMutableMap()
        val classNames = mutableListOf<String>()

        var i = 0
        fun value(option: String): String = args.getOrNull(++i) ?: fail("$option needs a value")
        while (i < args.size) {
            when (val arg = args[i]) {
                "--output" -> outputPath = value(arg)
                "--context-annotation" -> {
                    val klass = load(value(arg))
                    if (!klass.java.isAnnotation) fail("${klass.qualifiedName} is not an annotation")
                    @Suppress("UNCHECKED_CAST")
                    contextAnnotation = klass as KClass<out Annotation>
                }

                "--map" -> {
                    val (kotlinName, swiftName) = value(arg).split("=", limit = 2)
                        .takeIf { it.size == 2 } ?: fail("--map needs <class>=<SwiftType>")
                    mappings[load(kotlinName)] = swiftName
                }

                "--runtime-module" -> runtimeModule = value(arg)
                "--no-runtime-import" -> runtimeModule = null
                else -> classNames.add(arg)
            }
            i++
        }

        if (outputPath == null) fail("--output <path> is required")
        if (classNames.isEmpty()) fail("at least one RPC interface class name is required")

        val generator = SwiftGenerator(
            rpcClasses = classNames.map { load(it) },
            mappings = mappings,
            contextAnnotation = contextAnnotation,
            runtimeModule = runtimeModule,
        )

        val file = File(outputPath)
        file.parentFile?.mkdirs()
        file.writeText(generator.text)
        println("RPC Swift generation complete: ${file.absolutePath}")
    }

    private fun load(name: String): KClass<*> =
        try {
            Class.forName(name).kotlin
        } catch (e: ClassNotFoundException) {
            fail("class not found: $name")
        }

    private fun fail(message: String): Nothing {
        System.err.println("Error: $message")
        System.err.println("Usage: SwiftRpcExporter --output <path> [--context-annotation <class>] [--map <class>=<SwiftType>]... <rpcClass1> [rpcClass2] ...")
        exitProcess(1)
    }
}
