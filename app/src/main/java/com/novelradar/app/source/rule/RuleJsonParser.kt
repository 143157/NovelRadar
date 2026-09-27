package com.novelradar.app.source.rule

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import okio.Buffer

sealed interface RuleParseResult {
    data class Success(val rules: List<RuleDto>) : RuleParseResult
    data class Failure(val message: String) : RuleParseResult
}

class RuleJsonParser {
    private val adapter = Moshi.Builder()
        // Moshi normally coerces numbers to strings and numeric strings to integers.
        // Import must reject those schema mistakes instead of silently changing them.
        .add(String::class.java, StrictStringAdapter.nullSafe())
        .add(Int::class.java, StrictIntAdapter.nullSafe())
        .add(Int::class.javaObjectType, StrictIntAdapter.nullSafe())
        .addLast(KotlinJsonAdapterFactory())
        .build()
        .adapter(RuleDto::class.java)
        .nonNull()

    /** Accepts strict JSON only. Failure messages never contain imported data or Moshi details. */
    fun parse(json: String): RuleParseResult = try {
        JsonReader.of(Buffer().writeUtf8(json)).use { reader ->
            val rules = when (reader.peek()) {
                JsonReader.Token.BEGIN_OBJECT -> listOf(requireNotNull(adapter.fromJson(reader)))
                JsonReader.Token.BEGIN_ARRAY -> buildList {
                    reader.beginArray()
                    while (reader.hasNext()) add(requireNotNull(adapter.fromJson(reader)))
                    reader.endArray()
                }
                else -> return RuleParseResult.Failure("Expected a rule object or an array of rule objects.")
            }
            if (reader.peek() != JsonReader.Token.END_DOCUMENT) {
                RuleParseResult.Failure("Invalid rule JSON.")
            } else {
                RuleParseResult.Success(rules)
            }
        }
    } catch (_: JsonDataException) {
        RuleParseResult.Failure("Invalid rule JSON.")
    } catch (_: IOException) {
        RuleParseResult.Failure("Invalid rule JSON.")
    }
}

private object StrictStringAdapter : JsonAdapter<String>() {
    override fun fromJson(reader: JsonReader): String {
        if (reader.peek() != JsonReader.Token.STRING) throw JsonDataException("Expected a string")
        return reader.nextString()
    }

    override fun toJson(writer: JsonWriter, value: String?) {
        writer.value(value)
    }
}

private object StrictIntAdapter : JsonAdapter<Int>() {
    override fun fromJson(reader: JsonReader): Int {
        if (reader.peek() != JsonReader.Token.NUMBER) throw JsonDataException("Expected an integer")
        return reader.nextString().toIntOrNull() ?: throw JsonDataException("Expected an integer")
    }

    override fun toJson(writer: JsonWriter, value: Int?) {
        writer.value(value)
    }
}
