package com.apexobd.app.platform

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

data class OfflineRegionSummary(
    val id: Long,
    val name: String,
    val minZoom: Double,
    val maxZoom: Double,
    val completedResources: Long,
    val requiredResources: Long,
    val completedBytes: Long,
    val isComplete: Boolean,
    val isDownloading: Boolean = false
)

data class OfflineMapEstimate(
    val tileCount: Long,
    val estimatedMb: Double,
    val exceedsFairUseLimit: Boolean
)

data class PmtilesPackSummary(
    val name: String,
    val filePath: String,
    val sizeBytes: Long
)

/**
 * Manages both Google-Maps-style MapLibre bounding-box offline downloads (OpenFreeMap z5-z14)
 * and whole-island single-file `.pmtiles` archives stored locally in `filesDir/pmtiles/`.
 */
class OfflineMapManager(context: Context) {

    private val appContext = context.applicationContext
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private var offlineManager: OfflineManager? = null

    private val pmtilesDir: File
        get() = File(appContext.filesDir, "pmtiles").apply { mkdirs() }

    private val _regions = MutableStateFlow<List<OfflineRegionSummary>>(emptyList())
    val regions: StateFlow<List<OfflineRegionSummary>> = _regions.asStateFlow()

    private val _pmtilesPacks = MutableStateFlow<List<PmtilesPackSummary>>(emptyList())
    val pmtilesPacks: StateFlow<List<PmtilesPackSummary>> = _pmtilesPacks.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    fun ensureInitialized(): OfflineManager? {
        return offlineManager ?: runCatching {
            MapLibre.getInstance(appContext)
            OfflineManager.getInstance(appContext).also { offlineManager = it }
        }.getOrNull()
    }

    fun refreshAll() {
        refreshRegions()
        refreshPmtilesPacks()
    }

    fun refreshPmtilesPacks() {
        val list = pmtilesDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".pmtiles", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { PmtilesPackSummary(it.nameWithoutExtension, it.absolutePath, it.length()) }
            .orEmpty()
        _pmtilesPacks.value = list
    }

    fun refreshRegions() {
        val mgr = ensureInitialized() ?: return
        mgr.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                if (offlineRegions == null) {
                    _regions.value = emptyList()
                    return
                }
                val out = mutableListOf<OfflineRegionSummary>()
                var remaining = offlineRegions.size
                if (remaining == 0) {
                    _regions.value = emptyList()
                    return
                }
                for (reg in offlineRegions) {
                    val def = reg.definition as? OfflineTilePyramidRegionDefinition
                    val name = parseRegionName(reg.metadata)
                    reg.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            out += OfflineRegionSummary(
                                id = reg.id,
                                name = name,
                                minZoom = def?.minZoom ?: 5.0,
                                maxZoom = def?.maxZoom ?: 14.0,
                                completedResources = status?.completedResourceCount ?: 0L,
                                requiredResources = status?.requiredResourceCount ?: 0L,
                                completedBytes = status?.completedResourceSize ?: 0L,
                                isComplete = status?.isComplete ?: false,
                                isDownloading = status?.downloadState == OfflineRegion.STATE_ACTIVE
                            )
                            remaining--
                            if (remaining <= 0) {
                                _regions.value = out.sortedByDescending { it.id }
                            }
                        }

                        override fun onError(error: String?) {
                            remaining--
                            if (remaining <= 0) {
                                _regions.value = out.sortedByDescending { it.id }
                            }
                        }
                    })
                }
            }

            override fun onError(error: String) {
                _statusMessage.value = "Offline list error: $error"
            }
        })
    }

    fun downloadBoundsRegion(
        name: String,
        north: Double,
        east: Double,
        south: Double,
        west: Double,
        minZoom: Double = 5.0,
        maxZoom: Double = 14.0
    ) {
        val mgr = ensureInitialized() ?: run {
            _statusMessage.value = "MapLibre unavailable on this device"
            return
        }
        val estimate = estimateRegionSize(north, east, south, west, minZoom, maxZoom)
        if (estimate.exceedsFairUseLimit) {
            _statusMessage.value = "Frame too large (${estimate.tileCount} tiles / ~%.1f MB). Zoom in slightly until under 3,000 tiles.".format(estimate.estimatedMb)
            return
        }

        val bounds = LatLngBounds.Builder()
            .include(LatLng(north, east))
            .include(LatLng(south, west))
            .build()
        val def = OfflineTilePyramidRegionDefinition(
            OPENFREEMAP_DARK_STYLE,
            bounds,
            minZoom,
            maxZoom,
            appContext.resources.displayMetrics.density
        )
        val regionTitle = name.trim().ifBlank { "Offline Area" }
        val metadata = regionTitle.toByteArray(Charsets.UTF_8)
        val estMbStr = "%.1f".format(estimate.estimatedMb)
        _statusMessage.value = "Downloading '$regionTitle' (${estimate.tileCount} tiles, ~$estMbStr MB)..."

        mgr.createOfflineRegion(def, metadata, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        if (status.isComplete) {
                            offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            val doneMbStr = "%.1f".format(status.completedResourceSize / (1024.0 * 1024.0))
                            _statusMessage.value = "Ready offline: '$regionTitle' ($doneMbStr MB)"
                            refreshRegions()
                        } else if (status.requiredResourceCount > 0) {
                            val pct = (status.completedResourceCount * 100 / status.requiredResourceCount).toInt()
                            _statusMessage.value = "Downloading '$regionTitle': $pct%"
                        }
                    }

                    override fun onError(error: OfflineRegionError) {
                        _statusMessage.value = "Download error: ${error.message}"
                    }

                    override fun mapboxTileCountLimitExceeded(limit: Long) {
                        _statusMessage.value = "Tile limit reached ($limit)"
                    }
                })
                refreshRegions()
            }

            override fun onError(error: String) {
                _statusMessage.value = "Create region error: $error"
            }
        })
    }

    fun importPmtilesFromUri(uri: Uri, onImported: (String) -> Unit) {
        ioScope.launch {
            try {
                val displayName = resolveDisplayName(uri)
                    .substringBeforeLast('.')
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .ifBlank { "imported_map" }
                val dest = File(pmtilesDir, "$displayName.pmtiles")
                _statusMessage.value = "Importing $displayName.pmtiles..."

                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 65536)
                    }
                } ?: run {
                    _statusMessage.value = "Cannot open selected file"
                    return@launch
                }

                if (!isValidPmtilesHeader(dest)) {
                    dest.delete()
                    _statusMessage.value = "Selected file is not a valid PMTiles v3 archive"
                    return@launch
                }

                refreshPmtilesPacks()
                val importedMbStr = "%.1f".format(dest.length() / (1024.0 * 1024.0))
                _statusMessage.value = "Imported $displayName.pmtiles ($importedMbStr MB)"
                onImported(dest.absolutePath)
            } catch (t: Throwable) {
                _statusMessage.value = "Import failed: ${t.message}"
            }
        }
    }

    fun downloadPmtilesFromUrl(name: String, rawUrl: String, onDownloaded: (String) -> Unit) {
        val urlStr = rawUrl.trim()
        if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
            _statusMessage.value = "Enter a direct https:// link to a .pmtiles file"
            return
        }
        ioScope.launch {
            val safeName = name.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "region_pack" }
            val tempFile = File(pmtilesDir, "$safeName.part")
            val destFile = File(pmtilesDir, "$safeName.pmtiles")
            try {
                _statusMessage.value = "Connecting to download $safeName.pmtiles..."
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                }
                conn.connect()
                if (conn.responseCode !in 200..299) {
                    _statusMessage.value = "HTTP ${conn.responseCode}: ${conn.responseMessage}"
                    return@launch
                }
                val total = conn.contentLengthLong.coerceAtLeast(-1L)
                var copied = 0L
                var lastReportMs = 0L

                conn.inputStream.use { inp ->
                    tempFile.outputStream().use { out ->
                        val buf = ByteArray(65536)
                        while (true) {
                            val read = inp.read(buf)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                            copied += read
                            val now = System.currentTimeMillis()
                            if (now - lastReportMs > 450L) {
                                lastReportMs = now
                                val mbStr = "%.1f".format(copied / (1024.0 * 1024.0))
                                _statusMessage.value = if (total > 0) {
                                    "Downloading $safeName.pmtiles: ${(copied * 100 / total).toInt()}% ($mbStr MB)"
                                } else {
                                    "Downloading $safeName.pmtiles: $mbStr MB"
                                }
                            }
                        }
                    }
                }
                if (!isValidPmtilesHeader(tempFile)) {
                    tempFile.delete()
                    _statusMessage.value = "Downloaded file is not a valid PMTiles archive"
                    return@launch
                }
                if (destFile.exists()) destFile.delete()
                tempFile.renameTo(destFile)
                refreshPmtilesPacks()
                val readyMbStr = "%.1f".format(destFile.length() / (1024.0 * 1024.0))
                _statusMessage.value = "Ready offline: $safeName.pmtiles ($readyMbStr MB)"
                onDownloaded(destFile.absolutePath)
            } catch (t: Throwable) {
                tempFile.delete()
                _statusMessage.value = "Download error: ${t.message}"
            }
        }
    }

    fun deletePmtilesPack(path: String) {
        runCatching { File(path).delete() }
        refreshPmtilesPacks()
        _statusMessage.value = "Removed .pmtiles pack"
    }

    fun deleteRegion(regionId: Long) {
        val mgr = ensureInitialized() ?: return
        mgr.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val target = offlineRegions?.firstOrNull { it.id == regionId } ?: return
                target.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() {
                        _statusMessage.value = "Deleted offline region"
                        refreshRegions()
                    }

                    override fun onError(error: String) {
                        _statusMessage.value = "Delete failed: $error"
                    }
                })
            }

            override fun onError(error: String) = Unit
        })
    }

    private fun resolveDisplayName(uri: Uri): String {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx) ?: "offline.pmtiles"
            }
        }
        return uri.lastPathSegment ?: "offline.pmtiles"
    }

    private fun isValidPmtilesHeader(file: File): Boolean {
        if (!file.exists() || file.length() < 127L) return false
        return runCatching {
            file.inputStream().use { inp ->
                val hdr = ByteArray(8)
                val n = inp.read(hdr)
                n >= 8 && String(hdr, 0, 7, Charsets.US_ASCII) == "PMTiles"
            }
        }.getOrDefault(false)
    }

    private fun parseRegionName(metadata: ByteArray?): String {
        val raw = metadata?.toString(Charsets.UTF_8)?.trim().orEmpty()
        return raw.ifBlank { "Offline Region" }
    }

    data class BoundsBox(val north: Double, val east: Double, val south: Double, val west: Double)

    companion object {
        const val OPENFREEMAP_DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"
        const val FAIR_USE_TILE_LIMIT = 3000L
        private const val AVG_VECTOR_TILE_KB = 16.5

        fun insetBounds(north: Double, east: Double, south: Double, west: Double, marginRatio: Double = 0.08): BoundsBox {
            val dLat = (north - south) * marginRatio
            val dLon = (east - west) * marginRatio
            return BoundsBox(
                north = (north - dLat).coerceIn(-85.0, 85.0),
                east = (east - dLon).coerceIn(-180.0, 180.0),
                south = (south + dLat).coerceIn(-85.0, 85.0),
                west = (west + dLon).coerceIn(-180.0, 180.0)
            )
        }

        fun estimateRegionSize(
            north: Double,
            east: Double,
            south: Double,
            west: Double,
            minZoom: Double,
            maxZoom: Double
        ): OfflineMapEstimate {
            var totalTiles = 0L
            for (z in minZoom.toInt()..maxZoom.toInt()) {
                val n = 2.0.pow(z)
                val xMin = floor((west + 180.0) / 360.0 * n).toLong()
                val xMax = floor((east + 180.0) / 360.0 * n).toLong()
                val yMin = latToTileY(north, n)
                val yMax = latToTileY(south, n)
                val tilesX = (xMax - xMin + 1).coerceAtLeast(1L)
                val tilesY = (yMax - yMin + 1).coerceAtLeast(1L)
                totalTiles += tilesX * tilesY
            }
            val estMb = (totalTiles * AVG_VECTOR_TILE_KB) / 1024.0
            return OfflineMapEstimate(
                tileCount = totalTiles,
                estimatedMb = estMb,
                exceedsFairUseLimit = totalTiles > FAIR_USE_TILE_LIMIT
            )
        }

        /**
         * Builds an in-memory dark OLED MapLibre style supporting Protomaps (`roads`, `water`),
         * OpenMapTiles (`transportation`, `water`), and BBBike Shortbread (`streets`, `ocean`, `water_polygons`).
         */
        fun buildDarkPmtilesStyleJson(pmtilesFilePath: String): String {
            val escaped = pmtilesFilePath.replace("\\", "\\\\").replace("\"", "\\\"")
            return """
            {
              "version": 8,
              "name": "ApexOBD Dark PMTiles",
              "sources": {
                "offline_vec": {
                  "type": "vector",
                  "url": "pmtiles://file://$escaped"
                }
              },
              "layers": [
                {
                  "id": "bg",
                  "type": "background",
                  "paint": { "background-color": "#07090D" }
                },
                {
                  "id": "water-pm",
                  "type": "fill",
                  "source": "offline_vec",
                  "source-layer": "water",
                  "paint": { "fill-color": "#050E1B" }
                },
                {
                  "id": "water-sb",
                  "type": "fill",
                  "source": "offline_vec",
                  "source-layer": "water_polygons",
                  "paint": { "fill-color": "#050E1B" }
                },
                {
                  "id": "ocean-sb",
                  "type": "fill",
                  "source": "offline_vec",
                  "source-layer": "ocean",
                  "paint": { "fill-color": "#050E1B" }
                },
                {
                  "id": "bldg-pm",
                  "type": "fill",
                  "source": "offline_vec",
                  "source-layer": "buildings",
                  "minzoom": 13,
                  "paint": { "fill-color": "#0F1622", "fill-opacity": 0.7 }
                },
                {
                  "id": "bldg-omt",
                  "type": "fill",
                  "source": "offline_vec",
                  "source-layer": "building",
                  "minzoom": 13,
                  "paint": { "fill-color": "#0F1622", "fill-opacity": 0.7 }
                },
                {
                  "id": "roads-pm-minor",
                  "type": "line",
                  "source": "offline_vec",
                  "source-layer": "roads",
                  "paint": {
                    "line-color": "#1B2A3E",
                    "line-width": ["interpolate", ["linear"], ["zoom"], 8, 0.7, 15, 3.0]
                  }
                },
                {
                  "id": "roads-pm-major",
                  "type": "line",
                  "source": "offline_vec",
                  "source-layer": "roads",
                  "filter": ["in", ["get", "pmap:kind"], ["literal", ["highway", "major_road"]]],
                  "paint": {
                    "line-color": "#284364",
                    "line-width": ["interpolate", ["linear"], ["zoom"], 6, 1.0, 14, 4.5]
                  }
                },
                {
                  "id": "roads-omt",
                  "type": "line",
                  "source": "offline_vec",
                  "source-layer": "transportation",
                  "paint": {
                    "line-color": "#1E3048",
                    "line-width": ["interpolate", ["linear"], ["zoom"], 7, 0.8, 15, 3.4]
                  }
                },
                {
                  "id": "roads-sb",
                  "type": "line",
                  "source": "offline_vec",
                  "source-layer": "streets",
                  "paint": {
                    "line-color": "#1E3048",
                    "line-width": ["interpolate", ["linear"], ["zoom"], 7, 0.8, 15, 3.4]
                  }
                },
                {
                  "id": "boundaries-pm",
                  "type": "line",
                  "source": "offline_vec",
                  "source-layer": "boundaries",
                  "paint": { "line-color": "#233246", "line-width": 1.0 }
                }
              ]
            }
            """.trimIndent()
        }

        private fun latToTileY(latDeg: Double, n: Double): Long {
            val rad = Math.toRadians(latDeg.coerceIn(-85.05, 85.05))
            return floor((1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * n).toLong()
        }
    }
}
