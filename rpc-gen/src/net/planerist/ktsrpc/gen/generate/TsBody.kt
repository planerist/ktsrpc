package net.planerist.ktsrpc.gen.generate

/**
 * Builds a TypeScript function body with proper indentation.
 *
 * Supports statements, if/else blocks, switch/case, for-await loops,
 * return statements, and object literals.
 *
 * Example usage:
 * ```kotlin
 * TsBody.build {
 *     statement("const result = await this.rpcProxy.call(\"greet\", {")
 *     statement("    name: name")
 *     statement("})")
 *     returnStatement("result as string")
 * }
 * ```
 */
class TsBody private constructor(private val lines: List<BodyLine>) {

    fun render(baseIndent: String): String =
        lines.joinToString("\n") { it.render(baseIndent) }

    /** Renders as a list of indented lines */
    fun renderLines(baseIndent: String): List<String> =
        render(baseIndent).lines()

    sealed interface BodyLine {
        fun render(indent: String): String
    }

    /** A simple statement line */
    data class Statement(val code: String) : BodyLine {
        override fun render(indent: String) = "$indent$code"
    }

    /** An empty line */
    data object BlankLine : BodyLine {
        override fun render(indent: String) = ""
    }

    /** An if/else block */
    data class IfBlock(
        val condition: String,
        val thenBody: TsBody,
        val elseBody: TsBody? = null,
    ) : BodyLine {
        override fun render(indent: String) = buildString {
            appendLine("${indent}if($condition) {")
            appendLine(thenBody.render("$indent    "))
            if (elseBody != null) {
                appendLine("${indent}} else {")
                appendLine(elseBody.render("$indent    "))
            }
            append("${indent}}")
        }
    }

    /** A switch block */
    data class SwitchBlock(val expression: String, val cases: List<SwitchCase>) : BodyLine {
        override fun render(indent: String) = buildString {
            appendLine("${indent}switch ($expression) {")
            cases.forEach { case ->
                appendLine(case.render("$indent    "))
            }
            append("${indent}}")
        }
    }

    data class SwitchCase(val label: String, val body: String) {
        fun render(indent: String) = "${indent}case $label: return $body;"
    }

    data class SwitchDefault(val body: String) : BodyLine {
        override fun render(indent: String) = "${indent}default: return $body;"
    }

    /** A for-await loop */
    data class ForAwait(val variable: String, val iterable: String, val body: TsBody) : BodyLine {
        override fun render(indent: String) = buildString {
            appendLine("${indent}for await (const $variable of $iterable) {")
            appendLine(body.render("$indent    "))
            append("${indent}}")
        }
    }

    /** Object literal spread pattern: `return { ...value, field: expr }` */
    data class ObjectSpread(
        val source: String,
        val overrides: List<Pair<String, String>>,
        val keyword: String = "return",
    ) : BodyLine {
        override fun render(indent: String) = buildString {
            appendLine("${indent}${keyword} {")
            appendLine("$indent    ...$source,")
            overrides.forEach { (name, value) ->
                appendLine("$indent    $name: $value,")
            }
            append("$indent}")
        }
    }

    class Builder {
        private val lines = mutableListOf<BodyLine>()

        fun statement(code: String) = apply { lines.add(Statement(code)) }
        fun blank() = apply { lines.add(BlankLine) }
        fun returnStatement(expr: String) = apply { lines.add(Statement("return $expr")) }
        fun yieldStatement(expr: String) = apply { lines.add(Statement("yield $expr")) }
        fun yieldAll(expr: String) = apply { lines.add(Statement("yield* $expr")) }

        fun ifBlock(condition: String, then: Builder.() -> Unit, elseBlock: (Builder.() -> Unit)? = null) = apply {
            val thenBody = Builder().apply(then).build()
            val elseBody = elseBlock?.let { Builder().apply(it).build() }
            lines.add(IfBlock(condition, thenBody, elseBody))
        }

        fun switchBlock(expression: String, init: SwitchBuilder.() -> Unit) = apply {
            val builder = SwitchBuilder().apply(init)
            lines.add(SwitchBlock(expression, builder.cases))
            if (builder.defaultBody != null) {
                lines.add(SwitchDefault(builder.defaultBody!!))
            }
        }

        fun forAwait(variable: String, iterable: String, init: Builder.() -> Unit) = apply {
            lines.add(ForAwait(variable, iterable, Builder().apply(init).build()))
        }

        fun objectSpread(source: String, keyword: String = "return", init: ObjectSpreadBuilder.() -> Unit) = apply {
            val overrides = ObjectSpreadBuilder().apply(init).overrides
            lines.add(ObjectSpread(source, overrides, keyword))
        }

        fun build() = TsBody(lines)
    }

    class SwitchBuilder {
        val cases = mutableListOf<SwitchCase>()
        var defaultBody: String? = null

        fun case(label: String, body: String) { cases.add(SwitchCase(label, body)) }
        fun default(body: String) { defaultBody = body }
    }

    class ObjectSpreadBuilder {
        val overrides = mutableListOf<Pair<String, String>>()
        fun field(name: String, value: String) { overrides.add(name to value) }
    }

    companion object {
        fun build(init: Builder.() -> Unit): TsBody = Builder().apply(init).build()
    }
}
