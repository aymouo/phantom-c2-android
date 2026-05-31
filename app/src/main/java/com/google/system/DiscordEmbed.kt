package com.google.system

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal data class EmbedField(
    val name: String,
    val value: String,
    val inline: Boolean = false
)

internal data class DiscordEmbed(
    val title: String = "",
    val description: String = "",
    val color: Int = 0x2ECC71,
    val fields: List<EmbedField> = emptyList(),
    val footer: String = "",
    val timestamp: Long? = null
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        if (title.isNotBlank()) obj.put("title", title)
        if (description.isNotBlank()) obj.put("description", description)
        obj.put("color", color)

        if (fields.isNotEmpty()) {
            val arr = JSONArray()
            for (f in fields) {
                arr.put(JSONObject().apply {
                    put("name", f.name)
                    put("value", f.value)
                    put("inline", f.inline)
                })
            }
            obj.put("fields", arr)
        }

        if (footer.isNotBlank()) {
            obj.put("footer", JSONObject().put("text", footer))
        }

        if (timestamp != null) {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val iso = sdf.format(Date(timestamp))
            obj.put("timestamp", iso)
        }

        return obj
    }
}
