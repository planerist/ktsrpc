package net.planerist.serializers

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField

typealias OffsetDateTimeAsString = @Serializable(OffsetDateTimeAsStringSerializer::class) OffsetDateTime

val jsonDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .append(DateTimeFormatter.ISO_LOCAL_DATE)
        .appendLiteral('T')
        .appendValue(ChronoField.HOUR_OF_DAY, 2)
        .appendLiteral(':')
        .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
        .appendLiteral(':')
        .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
        .appendFraction(ChronoField.NANO_OF_SECOND, 3, 3, true)
        .appendOffset("+HH:MM", "Z")
        .toFormatter()

private fun parseOffsetDateTime(
    s: String,
    exceptionMaker: (String) -> RuntimeException,
): OffsetDateTime {
    return try {
        val parse: OffsetDateTime =
            OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        if (parse.get(ChronoField.OFFSET_SECONDS) == 0 && s.endsWith("-00:00")) {
            throw exceptionMaker(
                "Invalid value : '$s'. Negative zero offset is not allowed"
            )
        }
        parse
    } catch (e: DateTimeParseException) {
        throw exceptionMaker(
            "Invalid RFC3339 value : '" + s + "'. because of : '" + e.message + "'"
        )
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializer(forClass = OffsetDateTime::class)
object OffsetDateTimeAsStringSerializer : KSerializer<OffsetDateTime> {
    override fun serialize(encoder: Encoder, value: OffsetDateTime) {
        encoder.encodeString(jsonDateTimeFormatter.format(value))
    }

    override fun deserialize(decoder: Decoder): OffsetDateTime =
        parseOffsetDateTime(decoder.decodeString()) {
            RuntimeException(it)
        }
}
