package net.planerist.ktsrpc.gen.generate

/**
 * Builds a TypeScript class declaration.
 *
 * Example output:
 * ```typescript
 * export class Rpc {
 *     private rpcProxy: RpcProxy
 *
 *     constructor(rpcProxy: RpcProxy) {
 *         this.rpcProxy = rpcProxy
 *     }
 *
 *     async greet(name: string): Promise<string> { ... }
 * }
 * ```
 */
class TsClass(
    val name: String,
    val properties: List<TsClassProperty> = emptyList(),
    val constructorBody: List<String> = emptyList(),
    val constructorParams: List<TsParam> = emptyList(),
    val methods: List<TsFunction> = emptyList(),
    val export: Boolean = true,
) {
    fun render(): String = buildString {
        if (export) append("export ")
        appendLine("class $name {")
        appendLine()

        // Properties
        properties.forEach { prop ->
            appendLine("    ${prop.render()}")
        }

        // Constructor
        if (constructorParams.isNotEmpty() || constructorBody.isNotEmpty()) {
            appendLine("    ")
            append("    constructor(${constructorParams.joinToString(", ") { it.render() }})")
            if (constructorBody.isNotEmpty()) {
                appendLine(" {")
                constructorBody.forEach { line ->
                    appendLine("        $line")
                }
                appendLine("    }")
            } else {
                appendLine()
            }
        }

        // Methods
        methods.forEach { method ->
            appendLine()
            appendLine(method.render())
        }

        appendLine()
        appendLine("}")
        appendLine()
    }

    class Builder(private val name: String) {
        private var export = true
        private val properties = mutableListOf<TsClassProperty>()
        private var constructorParams = mutableListOf<TsParam>()
        private var constructorBody = mutableListOf<String>()
        private val methods = mutableListOf<TsFunction>()

        fun export(value: Boolean) = apply { export = value }
        fun property(name: String, type: TsType, private: Boolean = false) = apply {
            properties.add(TsClassProperty(name, type, `private`))
        }
        fun constructorParam(name: String, type: TsType) = apply {
            constructorParams.add(TsParam(name, type))
        }
        fun constructorBody(vararg lines: String) = apply {
            constructorBody.addAll(lines)
        }
        fun method(method: TsFunction) = apply { methods.add(method) }
        fun build() = TsClass(name, properties, constructorBody, constructorParams, methods, export)
    }

    companion object {
        fun builder(name: String) = Builder(name)
    }
}

/** A class property: `private name: type` */
data class TsClassProperty(val name: String, val type: TsType, val private: Boolean = false) {
    fun render(): String = (if (`private`) "private " else "") + "$name : ${type.render()}"
}
