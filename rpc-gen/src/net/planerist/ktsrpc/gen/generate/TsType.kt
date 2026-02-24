package net.planerist.ktsrpc.gen.generate

/**
 * Represents a TypeScript type expression.
 * Handles rendering with proper parenthesization for complex types.
 */
sealed interface TsType {
    /** Render without outer parentheses: `string | null` */
    fun render(): String

    /** Render with parentheses if composite: `(string | null)` */
    fun renderParenthesized(): String = render()

    /** Create a nullable version of this type */
    fun nullable(voidType: String = "null"): TsType {
        if (this is Simple && name == "any") return this
        return Union(listOf(this, Simple(voidType)))
    }

    /** Simple named type: `string`, `number`, `MyClass`, etc. */
    data class Simple(val name: String) : TsType {
        override fun render() = name
    }

    /** Union type: `string | number | null` */
    data class Union(val members: List<TsType>) : TsType {
        override fun render() = members.joinToString(" | ") { it.render() }
        override fun renderParenthesized() =
            if (members.size > 1) "(${render()})" else render()
    }

    /** Array type: `readonly string[]` */
    data class Array(val itemType: TsType, val readonly: Boolean = true) : TsType {
        override fun render() =
            (if (readonly) "readonly " else "") + itemType.renderParenthesized() + "[]"
    }

    /** Generic type: `GenericClass<A, B>` */
    data class Generic(val base: String, val typeArgs: List<TsType>) : TsType {
        override fun render() = "$base<${typeArgs.joinToString(", ") { it.render() }}>"
    }

    /** Object/map type: `{ readonly [key: string]: number }` or `{ [readonly key in Direction]: string }` */
    data class ObjectMap(val keyType: TsType, val valueType: TsType, val enumKey: Boolean = false) : TsType {
        override fun render() = if (enumKey) {
            "{ [readonly key in ${keyType.render()}]: ${valueType.render()} }"
        } else {
            "{ readonly [key: ${keyType.render()}]: ${valueType.render()} }"
        }
    }

    companion object {
        val STRING = Simple("string")
        val NUMBER = Simple("number")
        val BOOLEAN = Simple("boolean")
        val ANY = Simple("any")
        val VOID = Simple("void")

        fun simple(name: String) = Simple(name)
        fun union(vararg members: TsType) = Union(members.toList())
        fun readonlyArray(itemType: TsType) = Array(itemType, readonly = true)
        fun generic(base: String, vararg args: TsType) = Generic(base, args.toList())
        fun objectMap(keyType: TsType, valueType: TsType, enumKey: Boolean = false) =
            ObjectMap(keyType, valueType, enumKey)
    }
}
