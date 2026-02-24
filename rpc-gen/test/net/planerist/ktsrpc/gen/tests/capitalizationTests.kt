package net.planerist.ktsrpc.gen.tests

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CapitalizationTests {

    @Test
    fun `camelCaseToSnakeCase handles common cases`() {
        val tests: List<Pair<String, String>> = listOf(
            "camelCase" to "camel_case",
            "camelCase" to "camel_case",
            "CamelCase" to "camel_case",
            "CamelCamelCase" to "camel_camel_case",
            "Camel2Camel2Case" to "camel2_camel2_case",
            "getHTTPResponseCode" to "get_http_response_code",
            "get2HTTPResponseCode" to "get2_http_response_code",
            "HTTPResponseCode" to "http_response_code",
            "HTTPResponseCodeXYZ" to "http_response_code_xyz"
        )

        tests.forEach { (camel, snake) ->
            assertEquals(snake, camelCaseToSnakeCase(camel), "$camel -> $snake")
        }
    }

    @Test
    fun `snakeCaseToCamelCase handles common cases`() {
        val tests: List<Pair<String, String>> = listOf(
            "camel_case" to "camelCase",
            "camel_camel_case" to "camelCamelCase",
            "camel2_camel2_case" to "camel2Camel2Case",
            "get_http_response_code" to "getHttpResponseCode",
            "get2_http_response_code" to "get2HttpResponseCode",
            "http_response_code" to "httpResponseCode",
            "http_response_code_xyz" to "httpResponseCodeXyz"
        )

        tests.forEach { (snake, camel) ->
            assertEquals(camel, snakeCaseToCamelCase(snake), "$snake -> $camel")
        }
    }
}