package net.planerist.ktsrpc.gen.generate

private fun String.toJSString(): String {
    return "\"${
        this
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
            .replace("\"", "\\\"")
    }\""
}

/**
 * Builds a TypeScript type alias for enum-like union types.
 *
 * Example output:
 * ```typescript
 * export type Direction =
 *     | "North"
 *     | "South"
 * ```
 */
class TsTypeAlias(
    val name: String,
    val members: List<String>,
    val export: Boolean = true,
) {
    fun render(): String = buildString {
        if (export) append("export ")
        append("type $name =\n")
        append(members.joinToString("\n") { "    | ${it.toJSString()}" })
    }

    companion object {
        fun enumLike(name: String, values: List<String>) = TsTypeAlias(name, values)
    }
}

/**
 * Builds a TypeScript type guard function.
 *
 * Example output:
 * ```typescript
 * export const isFoo = (v: Base): v is Foo => {
 *     return v.type === 'Foo'
 * }
 * ```
 */
class TsTypeGuard(
    val name: String,
    val paramType: String,
    val guardType: String,
    val discriminator: String,
    val discriminatorValue: String,
) {
    fun render(): String =
        "export const is$name = (v: $paramType): v is $name => {\n" +
                "    return v.type === '$discriminatorValue'\n" +
                "}"
}
