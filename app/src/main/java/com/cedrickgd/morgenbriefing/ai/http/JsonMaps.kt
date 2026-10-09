package com.cedrickgd.morgenbriefing.ai.http

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Plain Kotlin values as a JSON element tree, for request bodies built from maps such as
 * [com.cedrickgd.morgenbriefing.ai.LivePublish.parametersSchema]: maps (keys as strings, order
 * kept), lists, arrays, strings, numbers, booleans, null and JSON elements as they are.
 * @throws IllegalArgumentException for any other type.
 */
internal fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associateTo(LinkedHashMap()) { (key, value) -> key.toString() to value.toJsonElement() })
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    is Array<*> -> JsonArray(map { it.toJsonElement() })
    else -> throw IllegalArgumentException("Not a JSON value: ${this::class.java.name}")
}
