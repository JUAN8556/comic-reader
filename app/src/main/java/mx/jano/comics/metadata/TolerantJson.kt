package mx.jano.comics.metadata

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Lectura tolerante de lo que escribe la migración (un script que puede cambiar): campos desconocidos fuera,
 * números entre comillas o sin ellas, una cadena donde se esperaba una lista (se envuelve), y si aun así un campo no
 * encaja, se descarta ESE campo y no la línea entera. Una línea que no es JSON o no tiene `ruta` se salta.
 */
internal object TolerantJson {

    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

    class Parsed<T>(val items: List<T>, val skipped: Int)

    fun <T> parseLines(text: String, serializer: KSerializer<T>): Parsed<T> {
        val out = ArrayList<T>()
        var skipped = 0
        text.lineSequence().forEach { raw ->
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty()) return@forEach
            val item = decodeLine(line, serializer)
            if (item == null) skipped++ else out += item
        }
        return Parsed(out, skipped)
    }

    fun <T> decodeLine(line: String, serializer: KSerializer<T>): T? {
        val element = try {
            json.parseToJsonElement(line)
        } catch (_: Exception) {
            return null
        }
        val obj = element as? JsonObject ?: return null
        return decodeObject(obj, serializer)
    }

    fun <T> decodeObject(obj: JsonObject, serializer: KSerializer<T>): T? {
        try {
            return json.decodeFromJsonElement(serializer, obj)
        } catch (_: Exception) {
            // Sigue abajo: campo a campo.
        }
        val ruta = obj["ruta"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: return null
        val base = mapOf("ruta" to JsonPrimitive(ruta))
        if (!fits(base, serializer)) return null
        val kept = LinkedHashMap<String, JsonElement>(base)
        for ((key, value) in obj) {
            if (key == "ruta" || value is JsonNull) continue
            val candidates = buildList {
                add(value)
                if (value is JsonPrimitive) add(JsonArray(listOf(value)))           // «guion»: "X" → ["X"]
                if (value is JsonArray && value.size == 1) add(value[0])             // [1999] → 1999
                if (value is JsonPrimitive && !value.isString) add(JsonPrimitive(value.content))
            }
            val accepted = candidates.firstOrNull { fits(kept + (key to it), serializer) } ?: continue
            kept[key] = accepted
        }
        return try {
            json.decodeFromJsonElement(serializer, JsonObject(kept))
        } catch (_: Exception) {
            null
        }
    }

    private fun <T> fits(fields: Map<String, JsonElement>, serializer: KSerializer<T>): Boolean = try {
        json.decodeFromJsonElement(serializer, JsonObject(fields))
        true
    } catch (_: Exception) {
        false
    }

    /** Primer entero de «12», «054», «12.5», «12-13», «#7»; null si no hay. */
    fun firstInt(text: String?): Int? {
        if (text == null) return null
        val m = Regex("""\d{1,6}""").find(text) ?: return null
        return m.value.toIntOrNull()
    }
}
