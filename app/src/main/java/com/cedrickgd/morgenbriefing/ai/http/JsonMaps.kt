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

/*
 * Lenient readers for response element trees: a missing key, JSON null or a value of another
 * type is simply null, so unknown or changed fields never break a stream.
 */

/** The object under [key], or null. */
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** The array under [key], or null. */
internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

/** The objects of the array under [key] (other elements skipped); empty when there is none. */
internal fun JsonObject.objects(key: String): List<JsonObject> = array(key).orEmpty().mapNotNull { it as? JsonObject }

/** The primitive under [key] as text (also a number or boolean), or null for JSON null / no primitive. */
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

internal fun JsonObject.long(key: String): Long? = str(key)?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }

internal fun JsonObject.double(key: String): Double? = str(key)?.toDoubleOrNull()

internal fun JsonObject.bool(key: String): Boolean? = str(key)?.toBooleanStrictOrNull()
