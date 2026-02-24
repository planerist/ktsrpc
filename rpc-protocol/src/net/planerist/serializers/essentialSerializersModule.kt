package net.planerist.serializers

import kotlinx.serialization.modules.SerializersModule
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

val essentialSerializersModule: SerializersModule = SerializersModule {
    contextual(OffsetDateTime::class, OffsetDateTimeAsStringSerializer)
    contextual(LocalTime::class, LocalTimeSerializer)
    contextual(ZoneId::class, ZoneIdSerializer)
}