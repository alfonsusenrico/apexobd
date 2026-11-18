package com.apexobd.app.platform

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
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
        sb.append("  \"durationSec\": ${session.effectiveDurationSec()},\n")
        sb.append("  \"distanceKm\": ${session.distanceKm},\n")
        sb.append("  \"avgSpeedKmh\": ${session.avgSpeedKmh},\n")
        sb.append("  \"fuelUsedLiters\": ${session.fuelUsedLiters},\n")
        sb.append("  \"avgKpl\": ${session.avgKpl},\n")
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

    fun buildShareChooserIntent(
        uri: Uri,
        mimeType: String,
        title: String = "Export Trip Log"
    ): Intent {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return Intent.createChooser(sendIntent, title).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun exportCacheFile(context: Context, fileName: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        return File(dir, fileName)
    }

    fun shareFile(context: Context, fileName: String, mimeType: String, content: String) {
        val file = exportCacheFile(context, fileName)
        file.writeText(content)
        sharePreparedFile(context, file, mimeType, "Export Trip Log")
    }

    fun sharePreparedFile(
        context: Context,
        file: File,
        mimeType: String,
        title: String = "Export Trip Log"
    ) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val chooser = buildShareChooserIntent(uri, mimeType, title)
        val launch = {
            val result = runCatching { context.startActivity(chooser) }
            if (result.isFailure) {
                Toast.makeText(context.applicationContext, "Unable to open share sheet: ${result.exceptionOrNull()?.message ?: "unknown"}", Toast.LENGTH_LONG).show()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            launch()
        } else {
            Handler(Looper.getMainLooper()).post(launch)
        }
    }

    /** Saves a PNG file into public `Pictures/ApexOBD` via MediaStore so it appears immediately in Gallery/Files. */
    fun savePngToPictures(
        context: Context,
        sourceFile: File,
        displayName: String = "apex_route_${System.currentTimeMillis()}.png"
    ): Result<String> = runCatching {
        val cleanName = if (displayName.endsWith(".png", ignoreCase = true)) displayName else "$displayName.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, cleanName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ApexOBD")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore returned null URI")
            resolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { inp -> inp.copyTo(out) }
            } ?: error("Unable to open MediaStore output stream")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Pictures/ApexOBD/$cleanName"
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ApexOBD").apply { mkdirs() }
            val dest = File(dir, cleanName)
            sourceFile.copyTo(dest, overwrite = true)
            dest.absolutePath
        }
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
