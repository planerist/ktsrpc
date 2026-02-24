package net.planerist.ktsrpc.gen.tsGenerator

import net.planerist.ktsrpc.gen.generate.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.serializerOrNull
import java.beans.Introspector
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.reflect.jvm.javaType

private fun isJavaBeanProperty(kProperty: KProperty<*>, klass: KClass<*>): Boolean {
    val beanInfo = Introspector.getBeanInfo(klass.java)
    return beanInfo.propertyDescriptors.any { bean -> bean.name == kProperty.name }
}

/**
 * TypeScript definition generator.
 *
 * Generates the content of a TypeScript definition file (.d.ts) that covers a set of Kotlin and
 * Java classes.
 *
 * This is useful when data classes are serialized to JSON and handled in a JS or TypeScript web
 * frontend.
 *
 * Supports:
 * * Primitive types, with explicit int
 * * Kotlin and Java classes
 * * Data classes
 * * Enums
 * * Any type
 * * Generic classes, without type erasure
 * * Generic constraints
 * * Class inheritance
 * * Abstract classes
 * * Lists as JS arrays
 * * Maps as JS objects
 * * Null safety, even inside composite types
 * * Java beans
 * * Mapping types
 * * Parenthesis are placed only when they are needed to disambiguate
 *
 * @param rootClasses Initial classes to traverse.
 * @param mappings Allows to map some JVM types with JS/TS types.
 * @param ignoreSuperclasses Classes and interfaces specified here will not be emitted as supertypes.
 * @param intTypeName Defines the name integer numbers will be emitted as.
 */
class TypeScriptGenerator(
    rootClasses: Iterable<KClass<*>>,
    private val rpcClasses: Iterable<KClass<*>>,
    val mappings: Map<KClass<*>, MappingDescriptor> = mapOf(),
    ignoreSuperclasses: Set<KClass<*>> = setOf(),
    private val intTypeName: String = "number",
    private val voidType: VoidType = VoidType.NULL
) {
    private val classesToIncludeTypeField: Map<KClass<*>, KClass<*>?>
    private val classesToConvert: Map<KClass<*>, List<String>>
    private val sealedClassesToConvert: Set<KClass<*>>
    private val visitedClasses: MutableSet<KClass<*>> = mutableSetOf()
    private val generatedDefinitions = mutableListOf<String>()
    private val generatedRpcs = mutableListOf<TsFunction>()
    private val generatedConvertors = mutableListOf<TsStandaloneFunction>()
    private val ignoredSuperclasses =
        setOf(Any::class, java.io.Serializable::class, Comparable::class).plus(ignoreSuperclasses)

    init {
        classesToIncludeTypeField =
            rootClasses
                .filter { it.isSealed }
                .flatMap {
                    buildList {
                        add(it to null)
                        addAll(it.sealedSubclasses.map { subType -> subType to it })
                    }
                }
                .toMap()

        rootClasses.forEach { visitClass(it) }

        val classConvertors = mutableMapOf<KClass<*>, MutableList<String>>()

        // Step 1: detect all classes with convertable types
        visitedClasses.forEach { dataClass ->
            val propertiesToConvert = mutableListOf<String>()
            dataClass.declaredMemberProperties.forEach { prop ->
                val memberClass = getEffectiveClass(prop.returnType)
                if (memberClass != null && mappings.containsKey(memberClass)) {
                    propertiesToConvert.add(prop.name)
                }
            }
            classConvertors[dataClass] = propertiesToConvert
        }

        // Step 2: build transitive closure
        var hasChanges: Boolean
        do {
            hasChanges = false
            visitedClasses.forEach { dataClass ->
                val myPropertiesToConvert = classConvertors[dataClass]!!
                dataClass.declaredMemberProperties.forEach { prop ->
                    val memberClass: KClass<*>? = getEffectiveClass(prop.returnType)
                    val propertyTypeConvertors = classConvertors[memberClass]
                    if (propertyTypeConvertors != null && propertyTypeConvertors.isNotEmpty()) {
                        if (!myPropertiesToConvert.contains(prop.name)) {
                            myPropertiesToConvert.add(prop.name)
                            hasChanges = true
                        }
                    }
                }
            }
        } while (hasChanges)

        classesToConvert = classConvertors.filter { it.value.isNotEmpty() }.toMap()

        val sealedClassesToConvert = mutableSetOf<KClass<*>>()
        visitedClasses.forEach {
            if (it.isSealed) {
                if (it.sealedSubclasses.any { classesToConvert.containsKey(it) }) {
                    sealedClassesToConvert.add(it)
                }
            }
        }
        this.sealedClassesToConvert = sealedClassesToConvert

        classesToConvert.forEach { visitConvertor(it.key) }
        sealedClassesToConvert.forEach { visitSealedConvertor(it) }
        rpcClasses.forEach { visitRpcClass(it) }
    }

    private fun getEffectiveClass(type: KType): KClass<*>? {
        val kClass = type.classifier as? KClass<*>
        if (kClass != null && kClass.isSubclassOf(List::class)) {
            return getEffectiveClass(type.arguments[0].type!!)
        }
        return kClass
    }

    companion object {
        private val KotlinAnyOrNull = Any::class.createType(nullable = true)
    }

    // region Class visiting

    private fun visitClass(klass: KClass<*>) {
        if (klass !in visitedClasses) {
            visitedClasses.add(klass)
            if (klass.qualifiedName!!.startsWith("kotlin.")) return
            if (klass.qualifiedName!!.startsWith("kotlinx.coroutines.flow")) return

            val definition = generateDefinition(klass)
            if (!rpcClasses.contains(klass)) {
                generatedDefinitions.add(definition)
            }
        }
    }

    // endregion

    // region Converter generation

    private fun visitConvertor(klass: KClass<*>) {
        generatedConvertors.add(buildToJsonConvertor(klass))
        generatedConvertors.add(buildFromJsonConvertor(klass))
    }

    private fun visitSealedConvertor(klass: KClass<*>) {
        generatedConvertors.add(buildSealedToJsonConvertor(klass))
        generatedConvertors.add(buildSealedFromJsonConvertor(klass))
    }

    private fun buildToJsonConvertor(klass: KClass<*>) =
        buildConvertor(klass, "convert${klass.simpleName}ToJson", toJson = true)

    private fun buildFromJsonConvertor(klass: KClass<*>) =
        buildConvertor(klass, "convertJsonTo${klass.simpleName}", toJson = false)

    private fun buildConvertor(klass: KClass<*>, name: String, toJson: Boolean): TsStandaloneFunction {
        val typeName = formatKType(klass.starProjectedType)
        val generateCall = if (toJson) ::generateToJsonCall else ::generateFromJsonCall

        val overrides = classesToConvert[klass]!!.map { propName ->
            val returnType = klass.declaredMemberProperties.first { it.name == propName }.returnType
            val convertor = generateCall("value.${propName}", returnType)
            if (returnType.isMarkedNullable) {
                propName to "value.${propName} ? $convertor : null"
            } else {
                propName to convertor
            }
        }

        val body = TsBody.build {
            ifBlock("value", then = {
                objectSpread("value") {
                    overrides.forEach { (n, v) -> field(n, v) }
                }
            }, elseBlock = {
                statement("return null")
            })
        }

        val paramType = if (toJson) TsType.union(typeName, TsType.simple("null"))
            else TsType.union(TsType.ANY, TsType.simple("null"))
        val retType = if (toJson) TsType.union(TsType.ANY, TsType.simple("null"))
            else TsType.union(typeName, TsType.simple("null"))

        return TsStandaloneFunction(name, listOf(TsParam("value", paramType)), retType, body)
    }

    private fun buildSealedToJsonConvertor(klass: KClass<*>) =
        buildSealedConvertor(klass, "convert${klass.simpleName}ToJson", toJson = true)

    private fun buildSealedFromJsonConvertor(klass: KClass<*>) =
        buildSealedConvertor(klass, "convertJsonTo${klass.simpleName}", toJson = false)

    private fun buildSealedConvertor(klass: KClass<*>, name: String, toJson: Boolean): TsStandaloneFunction {
        val typeName = formatKType(klass.starProjectedType)
        val generateCall = if (toJson) ::generateToJsonCall else ::generateFromJsonCall

        val body = TsBody.build {
            ifBlock("value", then = {
                switchBlock("value.type") {
                    klass.sealedSubclasses.forEach {
                        if (classesToConvert.containsKey(it)) {
                            val tsName = getTsTypeName(it)
                            val callArg = if (toJson) "value as $tsName" else "value"
                            case("\"$tsName\"", generateCall(callArg, it.starProjectedType))
                        }
                    }
                    default("value")
                }
            }, elseBlock = {
                statement("return null")
            })
        }

        val paramType = if (toJson) TsType.union(typeName, TsType.simple("null"))
            else TsType.union(TsType.ANY, TsType.simple("null"))
        val retType = if (toJson) TsType.union(TsType.ANY, TsType.simple("null"))
            else TsType.union(typeName, TsType.simple("null"))

        return TsStandaloneFunction(name, listOf(TsParam("value", paramType)), retType, body)
    }

    // endregion

    // region RPC generation

    private fun visitRpcClass(klass: KClass<*>) {
        generatedRpcs.addAll(klass.declaredFunctions.map { buildRpcMethod(it) })
    }

    private fun buildRpcMethod(func: KFunction<*>): TsFunction {
        val isFlow = (func.returnType.classifier as KClass<*>).simpleName == "Flow"

        val params = func.parameters
            .filter { it.kind == KParameter.Kind.VALUE }
            .filter { it.name != "context" }

        val tsParams = params.map { param ->
            if (param.isOptional) {
                TsParam(param.name!!, formatKType(param.type.withNullability(true)), defaultValue = "null")
            } else {
                TsParam(param.name!!, formatKType(param.type))
            }
        }.toMutableList()

        if (isFlow) {
            tsParams.add(TsParam("options?", TsType.simple("{ signal?: AbortSignal }")))
        }

        val returnType = when {
            func.returnType.isSubtypeOf(Unit::class.starProjectedType) ->
                TsType.simple("Promise<void>")
            isFlow ->
                TsType.simple("AsyncIterable<${flowItemType(func)}>")
            else ->
                TsType.simple("Promise<${formatKType(func.returnType).render()}>")
        }

        val body = buildRpcBody(func, params, isFlow)

        return TsFunction(
            name = func.name,
            params = tsParams,
            returnType = returnType,
            body = body,
            async = true,
            generator = isFlow,
        )
    }

    private fun buildRpcBody(
        func: KFunction<*>,
        params: List<KParameter>,
        isFlow: Boolean
    ): TsBody {
        val paramAssignments = params.joinToString(",\n") { param ->
            param.name!! + ": " + generateToJsonCall(param.name!!, param.type)
        }

        if (isFlow) {
            val flowParamType: KType? = func.returnType.arguments[0].type

            return TsBody.build {
                statement("const iterable = subscribeToIterable(")
                statement("    sink => this.rpcProxy.subscribe(\"${func.name}\", {")
                if (paramAssignments.isNotBlank()) {
                    paramAssignments.lines().forEach { statement("        $it") }
                }
                statement("    }, sink),")
                statement("    options?.signal")
                statement(");")
                blank()

                if (flowParamType != null && isConverterRequired(flowParamType)) {
                    val convert = generateFromJsonCall("value", flowParamType)
                    val cast = formatKType(flowParamType).render()
                    forAwait("value", "iterable") {
                        yieldStatement("$convert as $cast;")
                    }
                } else {
                    yieldAll("iterable as AsyncIterable<${flowItemType(func)}>;")
                }
            }
        } else {
            return TsBody.build {
                statement("const result = await this.rpcProxy.call(\"${func.name}\", {")
                if (paramAssignments.isNotBlank()) {
                    paramAssignments.lines().forEach { statement("    $it") }
                }
                statement("})")

                val deserialize = generateFromJsonCall("result", func.returnType)
                val castType = if (func.returnType.isSubtypeOf(Unit::class.starProjectedType)) "void"
                else formatKType(func.returnType).render()
                returnStatement("$deserialize as $castType")
            }
        }
    }

    private fun flowItemType(func: KFunction<*>) =
        formatKType(func.returnType.arguments[0].type!!).render()

    // endregion

    // region Converter call helpers

    private fun isConverterRequired(paramType: KType): Boolean {
        val paramClass = paramType.classifier as KClass<*>
        return if (paramClass.isSubclassOf(List::class)) {
            val listParamClassifier = paramType.arguments[0].type!!.classifier as KClass<*>
            classesToConvert[listParamClassifier] != null ||
                    sealedClassesToConvert.contains(listParamClassifier)
        } else {
            classesToConvert[paramClass] != null || sealedClassesToConvert.contains(paramClass)
        }
    }

    private fun generateToJsonCall(paramName: String, paramType: KType): String {
        val paramClass = paramType.classifier as KClass<*>
        val mapping = mappings[paramClass]
        if (mapping != null) return mapping.tsToJson(paramName)

        if (paramClass.isSubclassOf(List::class)) {
            val listParamType = paramType.arguments[0].type!!
            val listParamClassifier = listParamType.classifier as KClass<*>
            if (classesToConvert[listParamClassifier] != null) {
                return "$paramName.map(v => ${generateToJsonCall("v", listParamType)})"
            }
        } else {
            if (sealedClassesToConvert.contains(paramClass) || classesToConvert[paramClass] != null) {
                return "convert${paramClass.simpleName!!}ToJson(${paramName})"
            }
        }
        return paramName
    }

    private fun generateFromJsonCall(paramName: String, paramType: KType): String {
        val paramClass = paramType.classifier as KClass<*>
        val mapping = mappings[paramClass]
        if (mapping != null) return mapping.jsonToTs(paramName)

        if (paramClass.isSubclassOf(List::class)) {
            val listParamType = paramType.arguments[0].type!!
            val listParamClassifier = listParamType.classifier as KClass<*>
            if (classesToConvert[listParamClassifier] != null) {
                val castType = formatKType(paramType).render()
                return "($paramName as $castType).map(v => ${generateFromJsonCall("v", listParamType)})"
            }
        } else {
            if (sealedClassesToConvert.contains(paramClass) || classesToConvert[paramClass] != null) {
                return "convertJsonTo${paramClass.simpleName!!}(${paramName})"
            }
        }
        return paramName
    }

    // endregion

    // region Type formatting

    private fun formatClassType(type: KClass<*>): String {
        visitClass(type)
        return getTsTypeName(type)
    }

    private fun formatKType(kType: KType): TsType {
        val classifier = kType.classifier

        // Check for explicit mapping first
        if (classifier is KClass<*>) {
            val mapping = mappings[classifier]
            if (mapping != null) {
                val base = TsType.simple(mapping.typeName)
                return if (kType.isMarkedNullable) base.nullable(voidType.jsTypeName) else base
            }
        }

        val baseType: TsType = when (classifier) {
            Boolean::class -> TsType.BOOLEAN
            String::class, Char::class -> TsType.STRING
            Int::class, UInt::class, Long::class, ULong::class, Short::class, Byte::class ->
                TsType.simple(intTypeName)
            Float::class, Double::class -> TsType.NUMBER
            Any::class -> TsType.ANY
            else -> {
                if (classifier is KClass<*>) {
                    when {
                        classifier.isSubclassOf(Iterable::class) || classifier.javaObjectType.isArray -> {
                            val itemType = when (kType.classifier) {
                                IntArray::class -> Int::class.createType(nullable = false)
                                ShortArray::class -> Short::class.createType(nullable = false)
                                ByteArray::class -> Byte::class.createType(nullable = false)
                                CharArray::class -> Char::class.createType(nullable = false)
                                LongArray::class -> Long::class.createType(nullable = false)
                                FloatArray::class -> Float::class.createType(nullable = false)
                                DoubleArray::class -> Double::class.createType(nullable = false)
                                else -> kType.arguments.single().type ?: KotlinAnyOrNull
                            }
                            TsType.readonlyArray(formatKType(itemType))
                        }
                        classifier.isSubclassOf(Map::class) -> {
                            val rawKeyType = kType.arguments[0].type ?: KotlinAnyOrNull
                            val keyType = formatKType(rawKeyType)
                            val valueType = formatKType(kType.arguments[1].type ?: KotlinAnyOrNull)
                            val isEnumKey = (rawKeyType.classifier as? KClass<*>)?.java?.isEnum == true
                            TsType.objectMap(keyType, valueType, isEnumKey)
                        }
                        else -> {
                            val baseName = formatClassType(classifier)
                            if (kType.arguments.isNotEmpty()) {
                                TsType.generic(baseName, *kType.arguments.map { arg ->
                                    formatKType(arg.type ?: KotlinAnyOrNull)
                                }.toTypedArray())
                            } else {
                                TsType.simple(baseName)
                            }
                        }
                    }
                } else if (classifier is KTypeParameter) {
                    TsType.simple(classifier.name)
                } else {
                    TsType.simple("UNKNOWN")
                }
            }
        }

        return if (kType.isMarkedNullable) baseType.nullable(voidType.jsTypeName) else baseType
    }

    // endregion

    // region Interface/Enum generation

    private fun generateEnum(klass: KClass<*>): String {
        val alias = TsTypeAlias.enumLike(
            getTsTypeName(klass),
            klass.java.enumConstants.map { it.toString() }
        )
        return alias.render()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun generateInterface(klass: KClass<*>): String {
        val supertypes = klass.supertypes.filterNot { it.classifier in ignoredSuperclasses }
        val subclassName = getTsTypeName(klass)

        val builder = TsInterface.builder(subclassName)

        // Type parameters
        klass.typeParameters.forEach { typeParameter ->
            val bounds = typeParameter.upperBounds.filter { it.classifier != Any::class }
            val bound = if (bounds.isNotEmpty()) {
                val boundStr = bounds.joinToString(" & ") { formatKType(it).render() }
                TsType.simple(boundStr)
            } else null
            builder.typeParam(typeParameter.name, bound)
        }

        // Supertypes
        supertypes.forEach { supertype -> builder.extends(formatKType(supertype)) }

        // Properties
        klass.declaredMemberProperties
            .filter { !isFunctionType(it.returnType.javaType) }
            .filter { it.visibility == KVisibility.PUBLIC || isJavaBeanProperty(it, klass) }
            .forEach { property ->
                builder.property(property.name, formatKType(property.returnType))
            }

        // Type discriminator for sealed class hierarchies
        var typeGuard: TsTypeGuard? = null
        if (classesToIncludeTypeField.containsKey(klass)) {
            if (klass.isSealed) {
                val subclasses = klass.sealedSubclasses.map {
                    serializerOrNull(it.starProjectedType)!!.descriptor.serialName
                }
                val typeUnion = subclasses.joinToString(" | ") { "\"$it\"" }
                builder.property("type", TsType.simple(typeUnion))
            } else {
                val serialName = serializerOrNull(klass.starProjectedType)!!.descriptor.serialName
                builder.property("type", TsType.simple("'${serialName}'"))

                val sealedBase = classesToIncludeTypeField[klass]!!
                typeGuard = TsTypeGuard(
                    name = subclassName,
                    paramType = getTsTypeName(sealedBase),
                    guardType = subclassName,
                    discriminator = "type",
                    discriminatorValue = serialName
                )
            }
        }

        val result = builder.build().render()
        return if (typeGuard != null) {
            result + "\n\n" + typeGuard.render()
        } else result
    }

    // endregion

    @OptIn(ExperimentalSerializationApi::class)
    private fun getTsTypeName(kClass: KClass<*>): String {
        val serialName =
            if (kClass.typeParameters.any()) kClass.simpleName!!
            else serializerOrNull(kClass.starProjectedType)?.descriptor?.serialName
        val simpleName =
            serialName?.let { if (it.contains(".")) null else it } ?: kClass.simpleName!!
        return simpleName
    }

    private fun isFunctionType(javaType: Type): Boolean {
        return javaType is KCallable<*> ||
                javaType.typeName.startsWith("kotlin.jvm.functions.") ||
                (javaType is ParameterizedType && isFunctionType(javaType.rawType))
    }

    private fun generateDefinition(klass: KClass<*>): String {
        return if (klass.java.isEnum) {
            generateEnum(klass)
        } else {
            generateInterface(klass)
        }
    }

    // Public API:
    val definitionsText: String
        get() = generatedDefinitions.joinToString("\n\n")

    val serializersText: String
        get() = generatedConvertors.joinToString("\n\n") { it.render() }

    val rpcsText: String
        get() {
            val rpcClass = TsClass.builder("Rpc")
                .property("rpcProxy", TsType.simple("RpcProxy"), private = true)
                .constructorParam("rpcProxy", TsType.simple("RpcProxy"))
                .constructorBody("this.rpcProxy = rpcProxy")
            generatedRpcs.forEach { rpcClass.method(it) }
            return rpcClass.build().render()
        }

    val individualDefinitions: Set<String>
        get() = generatedDefinitions.toSet()
}
