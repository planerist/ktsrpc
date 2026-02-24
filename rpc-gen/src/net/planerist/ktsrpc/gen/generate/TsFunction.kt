package net.planerist.ktsrpc.gen.generate

/**
 * Builds a TypeScript function declaration (class method).
 *
 * Example output:
 * ```typescript
 *     async greet(name: string): Promise<string> {
 *         const result = await this.rpcProxy.call("greet", { name })
 *         return result as string
 *     }
 * ```
 */
class TsFunction(
    val name: String,
    val params: List<TsParam> = emptyList(),
    val returnType: TsType? = null,
    val body: TsBody,
    val async: Boolean = false,
    val generator: Boolean = false,
    val indent: String = "    ",
) {
    fun render(): String = buildString {
        append(indent)
        if (async) append("async ")
        if (generator) append("*")
        append(name)
        append("(${params.joinToString(", ") { it.render() }})")
        if (returnType != null) append(" : ${returnType.render()}")
        appendLine(" {")
        appendLine(body.render(indent + indent))
        append(indent + "}")
    }
}

/**
 * Builds a standalone (non-method) TypeScript function.
 *
 * Example output:
 * ```typescript
 * function convertToJson(value: MyType | null): any | null {
 *     ...
 * }
 * ```
 */
class TsStandaloneFunction(
    val name: String,
    val params: List<TsParam> = emptyList(),
    val returnType: TsType? = null,
    val body: TsBody,
) {
    fun render(): String = buildString {
        append("function $name")
        append("(${params.joinToString(", ") { it.render() }})")
        if (returnType != null) append(": ${returnType.render()}")
        appendLine(" {")
        appendLine(body.render("    "))
        append("}")
    }
}

/** A function parameter: `name: string` or `name: string | null = null` */
data class TsParam(val name: String, val type: TsType, val defaultValue: String? = null) {
    fun render(): String = buildString {
        append("$name: ${type.render()}")
        if (defaultValue != null) append(" = $defaultValue")
    }
}
