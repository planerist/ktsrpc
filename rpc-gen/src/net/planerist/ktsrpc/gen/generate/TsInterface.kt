package net.planerist.ktsrpc.gen.generate

/**
 * Builds a TypeScript interface declaration.
 *
 * Example output:
 * ```typescript
 * export interface DerivedClass<T> extends BaseClass {
 *     name: string;
 *     items: readonly T[];
 * }
 * ```
 */
class TsInterface(
    val name: String,
    val typeParams: List<TsTypeParam> = emptyList(),
    val extends: List<TsType> = emptyList(),
    val properties: List<TsProperty> = emptyList(),
    val export: Boolean = true,
) {
    fun render(): String = buildString {
        if (export) append("export ")
        append("interface $name")

        if (typeParams.isNotEmpty()) {
            append("<${typeParams.joinToString(", ") { it.render() }}>")
        }

        if (extends.isNotEmpty()) {
            append(" extends ${extends.joinToString(", ") { it.render() }}")
        }

        appendLine(" {")
        properties.forEach { prop ->
            appendLine("    ${prop.render()}")
        }
        append("}")
    }

    class Builder(private val name: String) {
        private var export = true
        private val typeParams = mutableListOf<TsTypeParam>()
        private val extends = mutableListOf<TsType>()
        private val properties = mutableListOf<TsProperty>()

        fun export(value: Boolean) = apply { export = value }
        fun typeParam(name: String, bound: TsType? = null) = apply {
            typeParams.add(TsTypeParam(name, bound))
        }
        fun extends(type: TsType) = apply { extends.add(type) }
        fun property(name: String, type: TsType) = apply {
            properties.add(TsProperty(name, type))
        }
        fun build() = TsInterface(name, typeParams, extends, properties, export)
    }

    companion object {
        fun builder(name: String) = Builder(name)
    }
}

/** A type parameter with optional bound: `T extends SomeType` */
data class TsTypeParam(val name: String, val bound: TsType? = null) {
    fun render(): String = if (bound != null) "$name extends ${bound.render()}" else name
}

/** A property declaration: `name: type;` */
data class TsProperty(val name: String, val type: TsType) {
    fun render(): String = "$name: ${type.render()};"
}
