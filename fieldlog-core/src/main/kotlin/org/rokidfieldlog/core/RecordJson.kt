package org.rokidfieldlog.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonSyntaxException
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.io.IOException

/** Validate the wire representation before Gson can discard duplicate names or coerce values. */
internal object RecordJson {
    private val gson = GsonBuilder().setStrictness(Strictness.STRICT).create()
    fun objectValue(text: String, maxBytes: Int): JsonObject {
        require(text.toByteArray(Charsets.UTF_8).size <= maxBytes) { "记录文件过大，原文件保留" }
        try {
            JsonReader(StringReader(text)).use { reader ->
                reader.strictness = Strictness.STRICT
                inspect(reader, 0)
                if (reader.peek() != JsonToken.END_DOCUMENT) throw JsonParseException("记录含多个JSON值，原文件保留")
            }
        } catch (error: IOException) {
            throw JsonSyntaxException("记录JSON格式异常，原文件保留", error)
        } catch (error: IllegalStateException) {
            throw JsonSyntaxException("记录JSON结构异常，原文件保留", error)
        }
        return gson.fromJson(text, JsonObject::class.java) ?: throw JsonParseException("缺少记录对象")
    }

    private fun inspect(reader: JsonReader, depth: Int) {
        if (depth > 64) throw JsonParseException("记录嵌套过深，原文件保留")
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                val names = mutableSetOf<String>()
                reader.beginObject()
                while (reader.hasNext()) {
                    if (!names.add(reader.nextName())) throw JsonParseException("记录含重复字段，原文件保留")
                    inspect(reader, depth + 1)
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                while (reader.hasNext()) inspect(reader, depth + 1)
                reader.endArray()
            }
            JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> reader.nextNull()
            else -> throw JsonParseException("记录JSON结构异常，原文件保留")
        }
    }

    fun integer(value: JsonElement?, maximum: Long = Long.MAX_VALUE) {
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber &&
            value.toString().matches(Regex("[0-9]+")) && value.toString().toLongOrNull()?.let { it <= maximum } == true) {
            "记录整数超出范围或类型异常，原文件保留"
        }
    }
}
