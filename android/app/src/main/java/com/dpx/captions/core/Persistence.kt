package com.dpx.captions.core

import com.dpx.captions.model.Aspect
import com.dpx.captions.model.AnimationStyle
import com.dpx.captions.model.CaptionCard
import com.dpx.captions.model.CaptionFormatting
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
    coerceInputValues = true
}

/** AutoSubs `.autosubs-preset.json` read/write. The same files load on the desktop app. */
object Presets {
    fun parse(text: String): AnimationStyle {
        val root = AppJson.parseToJsonElement(text).jsonObject
        val settings = (root["macroSettings"] as? JsonObject) ?: root
        val style = AppJson.decodeFromJsonElement<AnimationStyle>(settings)
        // AutoSubs expresses weight only through the Style string, with no separate Bold flag.
        return if ("Bold" in settings) style
        else style.copy(bold = style.styleName.contains("bold", ignoreCase = true))
    }

    fun nameOf(text: String): String? = runCatching {
        AppJson.parseToJsonElement(text).jsonObject["name"]?.jsonPrimitive?.content
    }.getOrNull()

    fun serialize(style: AnimationStyle, name: String, description: String = ""): String {
        val obj = buildJsonObject {
            put("description", description)
            put("macroSettings", AppJson.encodeToJsonElement(style))
            put("name", JsonPrimitive(name))
            put("version", 1)
        }
        return AppJson.encodeToString(JsonObject.serializer(), obj)
    }
}

/**
 * On-disk project. The shared fields use the same names as the desktop `.dpxproj`; the extra
 * metadata is ignored by the desktop loader.
 */
@Serializable
data class ProjectFile(
    val version: Int = 1,
    val id: String,
    val name: String,
    @SerialName("video_path") val videoPath: String,
    @SerialName("model_size") val modelSize: String,
    val formatting: CaptionFormatting,
    val style: AnimationStyle,
    val aspect: Aspect = Aspect.AUTO,
    val cards: List<CaptionCard>,
    val width: Int = 0,
    val height: Int = 0,
    @SerialName("duration_s") val durationSeconds: Double = 0.0,
    val fps: Double = 30.0,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("modified_at") val modifiedAt: Long = 0,
) {
    fun toJson(): String = AppJson.encodeToString(serializer(), this)

    companion object {
        fun fromJson(text: String): ProjectFile = AppJson.decodeFromString(serializer(), text)
    }
}

/** What survives between launches: the last-used look and options. */
@Serializable
data class AppSettings(
    val style: AnimationStyle = AnimationStyle(),
    val formatting: CaptionFormatting = CaptionFormatting(),
    @SerialName("model_id") val modelId: String = "large-v3-turbo-q5_0",
    val aspect: Aspect = Aspect.AUTO,
    /** CPU threads for transcription; 0 picks a sensible default for the device. */
    val threads: Int = 0,
    /** 1 is greedy decoding (fastest); 5 is beam search (more accurate, slower). */
    @SerialName("beam_size") val beamSize: Int = 5,
    val guides: Boolean = false,
) {
    fun toJson(): String = AppJson.encodeToString(serializer(), this)

    companion object {
        fun fromJson(text: String): AppSettings = AppJson.decodeFromString(serializer(), text)
    }
}
