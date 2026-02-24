package net.planerist.serializers

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.ZoneId

fun parseZoneId(timeZoneName: String?): ZoneId = try {
    ZoneId.of(timeZoneName ?: "UTC")
} catch (e: Exception) {
    ZoneId.of("UTC")
}

@OptIn(ExperimentalSerializationApi::class)
@Serializer(forClass = ZoneId::class)
object ZoneIdSerializer : KSerializer<ZoneId> {
    override fun serialize(encoder: Encoder, value: ZoneId) {
        encoder.encodeString(value.id)
    }

    override fun deserialize(decoder: Decoder): ZoneId {
        return parseZoneId(decoder.decodeString())
    }
}