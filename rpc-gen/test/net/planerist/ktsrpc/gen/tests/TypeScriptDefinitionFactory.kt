package net.planerist.ktsrpc.gen.tests

object TypeScriptDefinitionFactory {
    fun fromCode(tsCode: String): TypeScriptDefinition {
        val code = tsCode.trim()

        if (code.startsWith("interface")) {
            return ClassDefinition(code)
        } else if (code.startsWith("type")) {
            return EnumDefinition(code)
        } else if (code.startsWith("export interface")) {
            return ClassDefinition(code)
        } else if (code.startsWith("export type")) {
            return EnumDefinition(code)
        } else if (code.startsWith("export enum")) {
            return EnumDefinition(code)
        } else {
            throw RuntimeException("Unknown definition type: $code")
        }
    }
}
