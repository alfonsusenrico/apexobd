package com.apexobd.app.platform

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.apexobd.app.data.db.TelemetrySampleEntity
import com.apexobd.app.data.db.TripSessionEntity
import java.io.File

object TripExporter {

    fun encodePayload(values: Map<String, Double>): String {
        return values.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "\"${escapeJson(k)}\":$v"
        }
    }

    fun decodePayload(json: String): Map<String, Double> {
        val trimmed = json.trim().removePrefix("{").removeSuffix("}")
        if (trimmed.isBlank()) return emptyMap()
        val out = linkedMapOf<String, Double>()
        for (pair in trimmed.split(',')) {
            val idx = pair.indexOf(':')
            if (idx <= 0) continue
            val key = pair.substring(0, idx).trim().removeSurrounding("\"")
            val valNum = pair.substring(idx + 1).trim().toDoubleOrNull() ?: continue
            out[key] = valNum
        }
        return out
    }

    fun toCsv(session: TripSessionEntity, samples: List<TelemetrySampleEntity>): String {
        val sb = StringBuilder()
        val allKeys = linkedSetOf<String>()
        val parsedPayloads = samples.map { sample ->
            val map = decodePayload(sample.payloadJson)
            allKeys.addAll(map.keys)
            map
        }

        sb.append("session_id,timestamp_ms,latitude,longitude,gps_speed_kmh")
        for (k in allKeys) {
            sb.append(',').append(k)
        }
        sb.append('\n')

        samples.forEachIndexed { idx, s ->
            val map = parsedPayloads[idx]
            sb.append(session.id).append(',')
                .append(s.timestampMs).append(',')
                .append(s.latitude?.toString() ?: "").append(',')
                .append(s.longitude?.toString() ?: "").append(',')
                .append(s.gpsSpeedKmh?.toString() ?: "")
            for (k in allKeys) {
                sb.append(',').append(map[k]?.toString() ?: "")
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    fun toJson(session: TripSessionEntity, samples: List<TelemetrySampleEntity>): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"sessionId\": ${session.id},\n")
        sb.append("  \"startedAtMs\": ${session.startedAtMs},\n")
        sb.append("  \"endedAtMs\": ${session.endedAtMs ?: "null"},\n")
        sb.append("  \"distanceKm\": ${session.distanceKm},\n")
        sb.append("  \"maxSpeedKmh\": ${session.maxSpeedKmh},\n")
        sb.append("  \"peakRpm\": ${session.peakRpm},\n")
        sb.append("  \"best0100Sec\": ${session.best0100Sec ?: "null"},\n")
        sb.append("  \"samples\": [\n")
        samples.forEachIndexed { idx, s ->
            val comma = if (idx < samples.lastIndex) "," else ""
            sb.append("    {")
            sb.append("\"timestampMs\": ${s.timestampMs}, ")
            sb.append("\"latitude\": ${s.latitude ?: "null"}, ")
            sb.append("\"longitude\": ${s.longitude ?: "null"}, ")
            sb.append("\"gpsSpeedKmh\": ${s.gpsSpeedKmh ?: "null"}, ")
            sb.append("\"sensors\": ${s.payloadJson.ifBlank { "{}" }}")
            sb.append("}$comma\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    fun shareFile(context: Context, fileName: String, mimeType: String, content: String) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(content)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export Trip Log"))
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
