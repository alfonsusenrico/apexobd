package com.apexobd.app.platform

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
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

/**
 * Manages zero-API-key MapLibre offline region downloads against OpenFreeMap vector tiles
 * with bounding-box tile/size estimation before downloading.
 */
class OfflineMapManager(context: Context) {

    private val appContext = context.applicationContext
    private var offlineManager: OfflineManager? = null

    private val _regions = MutableStateFlow<List<OfflineRegionSummary>>(emptyList())
    val regions: StateFlow<List<OfflineRegionSummary>> = _regions.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    fun ensureInitialized(): OfflineManager? {
        return offlineManager ?: runCatching {
            MapLibre.getInstance(appContext)
            OfflineManager.getInstance(appContext).also { offlineManager = it }
        }.getOrNull()
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
                                minZoom = def?.minZoom ?: 9.0,
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

    fun downloadRadiusRegion(
        name: String,
        centerLat: Double,
        centerLon: Double,
        radiusKm: Double,
        minZoom: Double = 9.0,
        maxZoom: Double = 14.0
    ) {
        val mgr = ensureInitialized() ?: run {
            _statusMessage.value = "MapLibre unavailable on this device"
            return
        }
        val boundsTuple = radiusToBounds(centerLat, centerLon, radiusKm)
        val estimate = estimateRegionSize(
            north = boundsTuple.north,
            east = boundsTuple.east,
            south = boundsTuple.south,
            west = boundsTuple.west,
            minZoom = minZoom,
            maxZoom = maxZoom
        )
        if (estimate.exceedsFairUseLimit) {
            _statusMessage.value = "Selected region too large (${estimate.tileCount} tiles / ~${"%.1f".format(estimate.estimatedMb)} MB). Reduce radius or max zoom under 3,000 tiles."
            return
        }

        val bounds = LatLngBounds.Builder()
            .include(LatLng(boundsTuple.north, boundsTuple.east))
            .include(LatLng(boundsTuple.south, boundsTuple.west))
            .build()
        val def = OfflineTilePyramidRegionDefinition(
            OPENFREEMAP_DARK_STYLE,
            bounds,
            minZoom,
            maxZoom,
            appContext.resources.displayMetrics.density
        )
        val metadata = name.ifBlank { "Offline Pack" }.toByteArray(Charsets.UTF_8)
        _statusMessage.value = "Downloading '$name' (~${"%.1f".format(estimate.estimatedMb)} MB)..."

        mgr.createOfflineRegion(def, metadata, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        if (status.isComplete) {
                            offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            _statusMessage.value = "Downloaded '$name' (${"%.1f".format(status.completedResourceSize / (1024.0 * 1024.0))} MB)"
                            refreshRegions()
                        } else if (status.requiredResourceCount > 0) {
                            val pct = (status.completedResourceCount * 100 / status.requiredResourceCount).toInt()
                            _statusMessage.value = "Downloading '$name': $pct%"
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

    private fun parseRegionName(metadata: ByteArray?): String {
        val raw = metadata?.toString(Charsets.UTF_8)?.trim().orEmpty()
        return raw.ifBlank { "Offline Region" }
    }

    data class BoundsBox(val north: Double, val east: Double, val south: Double, val west: Double)

    companion object {
        const val OPENFREEMAP_DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"
        private const val FAIR_USE_TILE_LIMIT = 3000L
        private const val AVG_VECTOR_TILE_KB = 16.5

        fun radiusToBounds(lat: Double, lon: Double, radiusKm: Double): BoundsBox {
            val dLat = radiusKm / 111.32
            val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.15)
            val dLon = radiusKm / (111.32 * cosLat)
            return BoundsBox(
                north = (lat + dLat).coerceAtMost(85.0),
                east = (lon + dLon).coerceAtMost(180.0),
                south = (lat - dLat).coerceAtLeast(-85.0),
                west = (lon - dLon).coerceAtLeast(-180.0)
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

        private fun latToTileY(latDeg: Double, n: Double): Long {
            val rad = Math.toRadians(latDeg.coerceIn(-85.05, 85.05))
            return floor((1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * n).toLong()
        }
    }
}
