package net.planerist.ktsrpc.gen.tests

import net.planerist.ktsrpc.gen.tsGenerator.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.KType
import kotlin.reflect.full.createType

fun assertGeneratedCode(
    klass: KClass<*>,
    expectedOutput: Set<String>,
    mappings: Map<KClass<*>, MappingDescriptor> = mapOf(),
    ignoreSuperclasses: Set<KClass<*>> = setOf(),
    voidType: VoidType = VoidType.NULL
) {
    val generator = TypeScriptGenerator(
        listOf(klass), emptyList(), mappings,
        ignoreSuperclasses, intTypeName = "int", voidType = voidType
    )

    val expected = expectedOutput
        .map(TypeScriptDefinitionFactory::fromCode)
        .toSet()
    val actual = generator.individualDefinitions
        .map(TypeScriptDefinitionFactory::fromCode)
        .toSet()

    assertEquals(expected, actual)
}

class Empty
class ClassWithMember(val a: String)
class SimpleTypes(
    val aString: String,
    var anInt: Int,
    val aDouble: Double,
    private val privateMember: String
)

class ClassWithLists(
    val aList: List<String>,
    val anArrayList: ArrayList<String>
)

class ClassWithArray(
    val items: Array<String>
)

class Widget(
    val name: String,
    val value: Int
)

class ClassWithDependencies(
    val widget: Widget
)

class ClassWithMixedNullables(
    val count: Int,
    val time: Instant?
)

class ClassWithNullables(
    val widget: Widget?
)

class ClassWithComplexNullables(
    val maybeWidgets: List<String?>?,
    val maybeWidgetsArray: Array<String?>?
)

class ClassWithNullableList(
    val strings: List<String>?
)

open class GenericClass<A, out B, out C : List<Any>>(
    val a: A,
    val b: List<B?>,
    val c: C,
    private val privateMember: A
)

open class BaseClass(val a: Int)
class DerivedClass(val b: List<String>) : BaseClass(4)
class GenericDerivedClass<B>(a: Empty, b: List<B?>, c: ArrayList<String>) :
    GenericClass<Empty, B, ArrayList<String>>(a, b, c, a)

class ClassWithMethods(val propertyMethod: () -> Int) {
    fun regularMethod() = 4
}

abstract class AbstractClass(val concreteProperty: String) {
    abstract val abstractProperty: Int
    abstract fun abstractMethod()
}

enum class Direction {
    North,
    West,
    South,
    East
}

class ClassWithEnum(val direction: Direction)
data class DataClass(val prop: String)
class ClassWithAny(val required: Any, val optional: Any?)
class ClassWithMap(val values: Map<String, String>)
class ClassWithEnumMap(val values: Map<Direction, String>)

class GeneratorTests {
    @Test
    fun `handles empty class`() {
        assertGeneratedCode(
            Empty::class, setOf(
                """
export interface Empty {
}
"""
            )
        )
    }

    @Test
    fun `handles classes with a single member`() {
        assertGeneratedCode(
            ClassWithMember::class, setOf(
                """
export interface ClassWithMember {
    a: string;
}
"""
            )
        )
    }

    @Test
    fun `handles SimpleTypes`() {
        assertGeneratedCode(
            SimpleTypes::class, setOf(
                """
    export interface SimpleTypes {
        aString: string;
        anInt: int;
        aDouble: number;
    }
    """
            )
        )
    }

    @Test
    fun `handles ClassWithLists`() {
        assertGeneratedCode(
            ClassWithLists::class, setOf(
                """
    export interface ClassWithLists {
        aList: readonly string[];
        anArrayList: readonly string[];
    }
    """
            )
        )
    }

    @Test
    fun `handles ClassWithArray`() {
        assertGeneratedCode(
            ClassWithArray::class, setOf(
                """
    export interface ClassWithArray {
        items: readonly string[];
    }
    """
            )
        )
    }

    private val widget = """
    export interface Widget {
        name: string;
        value: int;
    }
    """

    @Test
    fun `handles ClassWithDependencies`() {
        assertGeneratedCode(
            ClassWithDependencies::class, setOf(
                """
    export interface ClassWithDependencies {
        widget: Widget;
    }
    """, widget
            )
        )
    }

    @Test
    fun `handles ClassWithNullables`() {
        assertGeneratedCode(
            ClassWithNullables::class, setOf(
                """
    export interface ClassWithNullables {
        widget: Widget | null;
    }
    """, widget
            )
        )
    }

    @Test
    fun `handles ClassWithMixedNullables using mapping`() {
        assertGeneratedCode(
            ClassWithMixedNullables::class, setOf(
                """
    export interface ClassWithMixedNullables {
        count: int;
        time: string | null;
    }
    """
            ), mappings = mapOf(Instant::class to MappingDescriptor("string", { it }, { it }))
        )
    }

    @Test
    fun `handles ClassWithMixedNullables using mapping and VoidTypes`() {
        assertGeneratedCode(
            ClassWithMixedNullables::class,
            setOf(
                """
    export interface ClassWithMixedNullables {
        count: int;
        time: string | undefined;
    }
    """
            ),
            mappings = mapOf(Instant::class to MappingDescriptor("string", { it }, { it })),
            voidType = VoidType.UNDEFINED
        )
    }

    @Test
    fun `handles ClassWithComplexNullables`() {
        assertGeneratedCode(
            ClassWithComplexNullables::class, setOf(
                """
    export interface ClassWithComplexNullables {
        maybeWidgets: readonly (string | null)[] | null;
        maybeWidgetsArray: readonly (string | null)[] | null;
    }
    """
            )
        )
    }

    @Test
    fun `handles ClassWithNullableList`() {
        assertGeneratedCode(
            ClassWithNullableList::class, setOf(
                """
    export interface ClassWithNullableList {
        strings: readonly string[] | null;
    }
    """
            )
        )
    }

    @Test
    fun `handles GenericClass`() {
        assertGeneratedCode(
            GenericClass::class, setOf(
                """
    export interface GenericClass<A, B, C extends readonly any[]> {
        a: A;
        b: readonly (B | null)[];
        c: C;
    }
    """
            )
        )
    }

    @Test
    fun `handles DerivedClass`() {
        assertGeneratedCode(
            DerivedClass::class, setOf(
                """
    export interface DerivedClass extends BaseClass {
        b: readonly string[];
    }
    """, """
    export interface BaseClass {
        a: int;
    }
    """
            )
        )
    }

    @Test
    fun `handles GenericDerivedClass`() {
        assertGeneratedCode(
            GenericDerivedClass::class, setOf(
                """
    export interface GenericClass<A, B, C extends readonly any[]> {
        a: A;
        b: readonly (B | null)[];
        c: C;
    }
    """, """
    export interface Empty {
    }
    """, """
    export interface GenericDerivedClass<B> extends GenericClass<Empty, B, readonly string[]> {
    }
    """
            )
        )
    }

    @Test
    fun `handles ClassWithMethods`() {
        assertGeneratedCode(
            ClassWithMethods::class, setOf(
                """
    export interface ClassWithMethods {
    }
    """
            )
        )
    }

    @Test
    fun `handles AbstractClass`() {
        assertGeneratedCode(
            AbstractClass::class, setOf(
                """
    export interface AbstractClass {
        concreteProperty: string;
        abstractProperty: int;
    }
    """
            )
        )
    }

    @Test
    fun `handles ClassWithEnum`() {
        assertGeneratedCode(
            ClassWithEnum::class, setOf(
                """
    export interface ClassWithEnum {
        direction: Direction;
    }
    """, """export type Direction =
    | "North"
    | "West"
    | "South"
    | "East""""
            )
        )
    }

    @Test
    fun `handles DataClass`() {
        assertGeneratedCode(
            DataClass::class, setOf(
                """
    export interface DataClass {
        prop: string;
    }
    """
            )
        )
    }

    @Test
    fun `handles ClassWithAny`() {
        // Note: in TypeScript any includes null and undefined.
        assertGeneratedCode(
            ClassWithAny::class, setOf(
                """
    export interface ClassWithAny {
        required: any;
        optional: any;
    }
    """
            )
        )
    }

    @Test
    fun `supports type mapping for classes`() {
        assertGeneratedCode(
            ClassWithDependencies::class, setOf(
                """
export interface ClassWithDependencies {
    widget: CustomWidget;
}
"""
            ), mappings = mapOf(Widget::class to MappingDescriptor("CustomWidget", { it }, { it }))
        )
    }

    @Test
    fun `supports type mapping for basic types`() {
        assertGeneratedCode(
            DataClass::class, setOf(
                """
    export interface DataClass {
        prop: CustomString;
    }
    """
            ), mappings = mapOf(String::class to MappingDescriptor("CustomString", { it }, { it }))
        )
    }



    @Test
    fun `handles JavaClass`() {
        assertGeneratedCode(
            JavaClass::class, setOf(
                """
    export interface JavaClass {
        name: string;
        results: readonly int[];
        multidimensional: readonly readonly string[][];
        finished: boolean;
    }
    """
            )
        )
    }

    @Test
    fun `handles JavaClassWithNullables`() {
        assertGeneratedCode(
            JavaClassWithNullables::class, setOf(
                """
    export interface JavaClassWithNullables {
        name: string;
        results: readonly int[];
        nextResults: readonly int[] | null;
    }
    """
            )
        )
    }

    @Test
    fun `handles JavaClassWithNonnullAsDefault`() {
        assertGeneratedCode(
            JavaClassWithNonnullAsDefault::class, setOf(
                """
    export interface JavaClassWithNonnullAsDefault {
        name: string;
        results: readonly int[];
        nextResults: readonly int[] | null;
    }
    """
            )
        )
    }

    @Test
    fun `handles ClassWithComplexNullables when serializing as undefined`() {
        assertGeneratedCode(
            ClassWithComplexNullables::class, setOf(
                """
    export interface ClassWithComplexNullables {
        maybeWidgets: readonly (string | undefined)[] | undefined;
        maybeWidgetsArray: readonly (string | undefined)[] | undefined;
    }
    """
            ), voidType = VoidType.UNDEFINED
        )
    }

    @Test
    fun `transforms ClassWithMap`() {
        assertGeneratedCode(
            ClassWithMap::class, setOf(
                """
    export interface ClassWithMap {
        values: { readonly [key: string]: string };
    }
    """
            )
        )
    }

    @Test
    fun `transforms ClassWithEnumMap`() {
        assertGeneratedCode(
            ClassWithEnumMap::class, setOf(
                """export type Direction =
    | "North"
    | "West"
    | "South"
    | "East"""", """
    export interface ClassWithEnumMap {
        values: { [readonly key in Direction]: string };
    }
    """
            )
        )
    }
}