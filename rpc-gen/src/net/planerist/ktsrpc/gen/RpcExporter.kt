package net.planerist.ktsrpc.gen

import kotlinx.serialization.serializerOrNull
import net.planerist.ktsrpc.gen.tsGenerator.MappingDescriptor
import net.planerist.ktsrpc.gen.tsGenerator.TypeScriptGenerator
import java.io.File
import java.time.OffsetDateTime
import kotlinx.datetime.LocalDate
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.*
import kotlin.system.exitProcess

/**
 * TypeScript RPC code generator.
 *
 * Usage:
 *   java -cp ... net.planerist.ktsrpc.gen.RpcExporter \
 *       --output path/to/rpc.ts \
 *       com.example.GreeterRpc \
 *       com.example.TodoRpc \
 *       com.example.ChatRpc
 *
 * Arguments:
 *   --output <path>   Output TypeScript file path (required)
 *   [classes...]       Fully qualified names of RPC interface classes
 */
object RpcExporter {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) {
            System.err.println("Usage: RpcExporter --output <path> <rpcClass1> [rpcClass2] ...")
            exitProcess(1)
        }

        // Parse args
        var outputPath: String? = null
        val classNames = mutableListOf<String>()

        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--output" -> {
                    i++
                    outputPath = args[i]
                }
                else -> classNames.add(args[i])
            }
            i++
        }

        if (outputPath == null) {
            System.err.println("Error: --output <path> is required")
            exitProcess(1)
        }

        if (classNames.isEmpty()) {
            System.err.println("Error: at least one RPC interface class name is required")
            exitProcess(1)
        }

        println("Generating TypeScript definitions...")

        // Load RPC classes by name
        val allRpcClasses: List<KClass<*>> = classNames.map { name ->
            try {
                Class.forName(name).kotlin
            } catch (e: ClassNotFoundException) {
                System.err.println("Error: RPC class not found: $name")
                exitProcess(1)
            }
        }

        val mappings: Map<KClass<out Any>, MappingDescriptor> = mapOf(
            OffsetDateTime::class to
                    MappingDescriptor("OffsetDateTime", { it }, { it }),
            LocalDate::class to
                    MappingDescriptor("LocalDate", { it }, { it }),
            kotlin.time.Instant::class to
                    MappingDescriptor("Instant", { it }, { it }),
        )

        val allDataClasses = allDataClasses(allRpcClasses, mappings)

        val typeScriptGenerator = TypeScriptGenerator(
            rootClasses = allDataClasses,
            rpcClasses = allRpcClasses,
            mappings = mappings,
        )

        val tsDef = typeScriptGenerator.definitionsText
        val tsSerializersDef = typeScriptGenerator.serializersText
        val tsRpc = typeScriptGenerator.rpcsText

        val file = File(outputPath)
        file.parentFile?.mkdirs()
        file.printWriter().use { out ->
            out.println("import {subscribeToIterable} from \"./runtime\";")
            out.println("import type {RpcProxy} from \"./runtime\";")
            out.println()
            out.println("export type OffsetDateTime = string;")
            out.println("export type LocalDate = string;")
            out.println("export type Instant = string;")
            out.println()
            out.print(tsDef)
            out.println()
            out.println()
            out.print(tsSerializersDef)
            out.println()
            out.println()
            out.println("// RPC")
            out.print(tsRpc)
        }
        println("RPC TS generation complete: ${file.absolutePath}")
    }
}

fun allDataClasses(rpcInterfaces: List<KClass<*>>, mappings: Map<KClass<out Any>, MappingDescriptor>): Set<KClass<*>> {
    return rpcInterfaces
        .flatMap { it.functions }
        .flatMap { kFunction ->
            kFunction.parameters
                .flatMap { processType(it.type) }
                .union(processType(kFunction.returnType))
        }
        .asSequence()
        .flatMap { processKClass(it) }
        .filter { !it.isSubclassOf(List::class) }
        .filter { !it.isValue }
        .filter { !it.isSubclassOf(String::class) }
        .filter { !rpcInterfaces.contains(it) }
        .filter { !it.qualifiedName!!.startsWith("kotlin") }
        .filter { !mappings.containsKey(it) }
        .toSet()
}

private fun processType(type: KType): List<KClass<*>> {
    val kClass = type.classifier as KClass<*>
    return buildList {
        add(kClass)
        addAll(
            type.arguments.flatMap {
                if (it.type != null && it.type!!.classifier is KClass<*>) {
                    processType(it.type!!)
                } else {
                    emptyList()
                }
            }
        )
        addAll(kClass.sealedSubclasses.flatMap { processKClass(it) })
    }
}

private fun processKClass(kClass: KClass<*>): List<KClass<*>> {
    return buildList {
        add(kClass)
        addAll(kClass.sealedSubclasses.flatMap { processKClass(it) })
        kClass.declaredMemberProperties.forEach {
            addAll(processType(it.returnType))
        }
    }
}
