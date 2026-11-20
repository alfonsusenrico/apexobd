package com.apexobd.app.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apexobd.app.data.db.RefuelLogEntity
import com.apexobd.app.data.db.TelemetrySampleEntity
import com.apexobd.app.data.db.TripSessionEntity
import com.apexobd.app.data.db.VehicleProfileEntity
import com.apexobd.app.data.prefs.HudCanvasBackdrop
import com.apexobd.app.data.prefs.HudCardStyle
import com.apexobd.app.data.prefs.HudGridScale
import com.apexobd.app.data.prefs.HudThemePreset
import com.apexobd.app.data.prefs.MapCameraMode
import com.apexobd.app.data.prefs.MapCursorStyle
import com.apexobd.app.data.prefs.MapProvider
import com.apexobd.app.data.prefs.MapStylePreset
import com.apexobd.app.data.prefs.ShareCardAspectRatio
import com.apexobd.app.data.prefs.ShareCardBackgroundMode
import com.apexobd.app.data.prefs.ShareCardMetric
import com.apexobd.app.data.prefs.TripCardConfig
import com.apexobd.app.data.prefs.UserSettings
import com.apexobd.app.platform.BluetoothClassicObdTransport
import com.apexobd.app.platform.BluetoothLeObdTransport
import com.apexobd.app.platform.BtDeviceCandidate
import com.apexobd.app.platform.GpsSnapshot
import com.apexobd.app.platform.OfflineMapManager
import com.apexobd.app.platform.OfflineRegionSummary
import com.apexobd.app.platform.PmtilesPackSummary
import com.apexobd.app.platform.TripCardRenderer
import com.apexobd.app.platform.UsbSerialCandidate
import com.apexobd.core.transport.ObdConnectionType
import com.apexobd.app.ui.components.DualMapCanvas
import com.apexobd.app.ui.components.OfflineFramePickerMap
import com.apexobd.app.ui.theme.TorqueEvoColors
import com.apexobd.core.model.AirflowUnit
import com.apexobd.core.model.FuelCoastingMode
import com.apexobd.core.model.FuelEconomyUnit
import com.apexobd.core.model.FuelType
import com.apexobd.core.model.PowerUnit
import com.apexobd.core.model.PressureUnit
import com.apexobd.core.model.SensorQuantity
import com.apexobd.core.model.SpeedUnit
import com.apexobd.core.model.TempUnit
import com.apexobd.core.model.TorqueUnit
import com.apexobd.core.model.TransmissionType
import com.apexobd.core.model.UnitConverter
import com.apexobd.core.model.UnitPreferences
import com.apexobd.core.model.VehicleBodyType
import com.apexobd.core.model.VolumeUnit
import com.apexobd.core.protocol.DiagnosticReport
import com.apexobd.core.protocol.GaugeSmoothingMode
import com.apexobd.core.protocol.ToyotaMeterCapabilities
import com.apexobd.core.scheduler.ConnectionState
import com.apexobd.core.protocol.ObdPidRegistry
import com.apexobd.core.protocol.TripRunCalculator
import com.apexobd.core.scheduler.PollingProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ApexFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    enabled: Boolean = true,
    compact: Boolean = false,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    FilterChip(
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        trailingIcon = trailingIcon,
        label = {
            Text(
                text = label,
                fontSize = if (compact) 11.sp else 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = TorqueEvoColors.DimBlue,
            selectedLabelColor = TorqueEvoColors.ElectricBlue,
            containerColor = TorqueEvoColors.CardSurface,
            labelColor = TorqueEvoColors.TextSecondary,
            disabledContainerColor = Color(0xFF0B0E14),
            disabledLabelColor = Color(0xFF4B5563)
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = TorqueEvoColors.SubtleBorder,
            selectedBorderColor = TorqueEvoColors.ElectricBlue,
            disabledBorderColor = Color(0x16FFFFFF),
            borderWidth = 1.dp,
            selectedBorderWidth = 1.dp
        )
    )
}

/** Configures Android `Dialog` window bounds edge-to-edge so `WindowInsets` apply cleanly above navigation/gesture bars. */
@Composable
private fun ConfigureEdgeToEdgeDialogWindow() {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        window.setGravity(android.view.Gravity.FILL)
        if (android.os.Build.VERSION.SDK_INT < 35) {
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }
}

/**
 * Non-swipe-dismissing sheet wrapper immune to downward scroll drags and accidental scrim taps.
 * Users close only via explicit header close controls or intentional system Back.
 */
@Composable
private fun ApexLockedSheet(
    onBackRequest: () -> Unit,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onBackRequest,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        ConfigureEdgeToEdgeDialogWindow()
        val safeInsets = WindowInsets.safeDrawing.union(
            WindowInsets.mandatorySystemGestures.only(WindowInsetsSides.Bottom)
        )
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .windowInsetsPadding(safeInsets)
                .imePadding()
                .padding(bottom = 6.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 12.dp, bottomEnd = 12.dp)
            val baseModifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.94f)
                .padding(horizontal = 6.dp)
                .clip(shape)
                .background(TorqueEvoColors.ElevatedDark)
                .border(1.dp, TorqueEvoColors.SubtleBorder, shape)
                .padding(top = 14.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
            Column(
                modifier = if (scrollable) baseModifier.verticalScroll(rememberScrollState()) else baseModifier,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        }
    }
}

@Composable
private fun ApexSheetHeader(
    title: String,
    onClose: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = TorqueEvoColors.TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            actions()
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = TorqueEvoColors.TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareCardMetricPickerSection(
    selectedMetrics: List<ShareCardMetric>,
    onToggleMetric: (ShareCardMetric) -> Unit,
    onResetDefault: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Share Card Metrics (${selectedMetrics.size}/${ShareCardMetric.MAX_SELECTED})",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TorqueEvoColors.TextSecondary
            )
            Text(
                "Reset Default",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = TorqueEvoColors.ElectricBlue,
                modifier = Modifier.clickable { onResetDefault() }
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ShareCardMetric.entries.forEach { metric ->
                val isSelected = metric in selectedMetrics
                val canInteract = isSelected || selectedMetrics.size < ShareCardMetric.MAX_SELECTED
                ApexFilterChip(
                    selected = isSelected,
                    enabled = canInteract,
                    onClick = { onToggleMetric(metric) },
                    label = metric.shortLabel,
                    compact = true
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VehicleProfileSheet(
    vehicles: List<VehicleProfileEntity>,
    activeVehicle: VehicleProfileEntity?,
    unitPrefs: UnitPreferences,
    startAddingNew: Boolean = false,
    onSelectVehicle: (Long) -> Unit,
    onPickPhoto: (Uri, (String?) -> Unit) -> Unit,
    onSaveVehicle: (
        existing: VehicleProfileEntity?,
        name: String,
        engineCc: Int,
        carType: VehicleBodyType,
        transmission: TransmissionType,
        modelYear: Int?,
        photoUri: String?,
        weightKg: Double,
        fuelType: FuelType,
        tankCapDisplay: Double,
        remDisplay: Double,
        odoDisplay: Double,
        redline: Int,
        mafTrimPct: Double
    ) -> Unit,
    onDeleteVehicle: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var editingExisting by remember(activeVehicle?.id, startAddingNew) {
        mutableStateOf(if (startAddingNew) null else activeVehicle)
    }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var name by remember(editingExisting?.id, startAddingNew) { mutableStateOf(editingExisting?.name ?: "New Car") }
    var engineCcStr by remember(editingExisting?.id, startAddingNew) {
        val initCc = editingExisting?.let { if (it.engineCc > 0) it.engineCc else (it.displacementLiters * 1000.0).toInt() } ?: 1500
        mutableStateOf("$initCc")
    }
    var carType by remember(editingExisting?.id, startAddingNew) {
        mutableStateOf(editingExisting?.let { runCatching { VehicleBodyType.valueOf(it.carType) }.getOrNull() } ?: VehicleBodyType.MPV)
    }
    var transmission by remember(editingExisting?.id, startAddingNew) {
        mutableStateOf(editingExisting?.let { runCatching { TransmissionType.valueOf(it.transmission) }.getOrNull() } ?: TransmissionType.MANUAL)
    }
    var yearStr by remember(editingExisting?.id, startAddingNew) { mutableStateOf(editingExisting?.modelYear?.toString() ?: "") }
    var photoPath by remember(editingExisting?.id, startAddingNew) { mutableStateOf(editingExisting?.photoUri) }
    var weight by remember(editingExisting?.id, startAddingNew) { mutableStateOf("${(editingExisting?.weightKg ?: 1250.0).toInt()}") }
    var fuelType by remember(editingExisting?.id, startAddingNew) {
        mutableStateOf(
            editingExisting?.let { runCatching { FuelType.valueOf(it.fuelType) }.getOrNull() } ?: FuelType.GASOLINE
        )
    }
    var redline by remember(editingExisting?.id, startAddingNew) { mutableStateOf("${editingExisting?.redlineRpm ?: 6800}") }
    var mafTrimStr by remember(editingExisting?.id, startAddingNew) { mutableStateOf("%.1f".format(editingExisting?.mafTrimPct ?: 100.0)) }

    val volUnit = unitPrefs.volumeSymbol(fuelType)
    val distUnit = unitPrefs.distanceSymbol()

    val capDispInit = UnitConverter.convertVolume(editingExisting?.tankCapacityLiters ?: 45.0, unitPrefs, fuelType).first
    val remDispInit = UnitConverter.convertVolume(editingExisting?.estimatedRemainingFuelLiters ?: 45.0, unitPrefs, fuelType).first
    val odoDispInit = UnitConverter.convertDistanceKm(editingExisting?.allTimeOdometerKm ?: 0.0, unitPrefs).first

    var tankCap by remember(editingExisting?.id, startAddingNew, fuelType, unitPrefs.volume) { mutableStateOf("%.1f".format(capDispInit)) }
    var remFuel by remember(editingExisting?.id, startAddingNew, fuelType, unitPrefs.volume) { mutableStateOf("%.1f".format(remDispInit)) }
    var odometer by remember(editingExisting?.id, startAddingNew, unitPrefs.speed) { mutableStateOf("%.1f".format(odoDispInit)) }

    val initSnapshot = remember(editingExisting?.id, startAddingNew) {
        listOf(
            editingExisting?.name ?: "New Car",
            (editingExisting?.let { if (it.engineCc > 0) it.engineCc else (it.displacementLiters * 1000.0).toInt() } ?: 1500).toString(),
            editingExisting?.carType ?: VehicleBodyType.MPV.name,
            editingExisting?.transmission ?: TransmissionType.MANUAL.name,
            editingExisting?.modelYear?.toString() ?: "",
            editingExisting?.photoUri ?: "",
            (editingExisting?.weightKg ?: 1250.0).toInt().toString(),
            editingExisting?.fuelType ?: FuelType.GASOLINE.name,
            (editingExisting?.redlineRpm ?: 6800).toString(),
            "%.1f".format(editingExisting?.mafTrimPct ?: 100.0),
            "%.1f".format(capDispInit),
            "%.1f".format(remDispInit),
            "%.1f".format(odoDispInit)
        )
    }
    val hasUnsavedChanges = listOf(
        name, engineCcStr, carType.name, transmission.name, yearStr, photoPath ?: "",
        weight, fuelType.name, redline, mafTrimStr, tankCap, remFuel, odometer
    ) != initSnapshot

    val requestClose = {
        if (hasUnsavedChanges) confirmDiscard = true else onDismiss()
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            onPickPhoto(uri) { copiedPath ->
                if (!copiedPath.isNullOrBlank()) photoPath = copiedPath
            }
        }
    }
    val photoBmp = remember(photoPath) {
        photoPath?.takeIf { File(it).exists() }?.let { path ->
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
    }

    if (confirmDelete && editingExisting != null) {
        val target = editingExisting!!
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = TorqueEvoColors.ElevatedDark,
            title = { Text("Delete Vehicle Profile", color = TorqueEvoColors.TextPrimary) },
            text = { Text("Delete \"${target.name}\" along with its saved refuel checkpoints and run logs?", color = TorqueEvoColors.TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        onDeleteVehicle(target.id)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TorqueEvoColors.CriticalRed, contentColor = Color.White)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            containerColor = TorqueEvoColors.ElevatedDark,
            title = { Text("Discard Unsaved Vehicle?", color = TorqueEvoColors.TextPrimary) },
            text = { Text("You have unsaved car profile edits. Close without saving?", color = TorqueEvoColors.TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDiscard = false
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TorqueEvoColors.CriticalRed, contentColor = Color.White)
                ) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep Editing") }
            }
        )
    }

    ApexLockedSheet(onBackRequest = requestClose) {
        ApexSheetHeader(
            title = if (editingExisting == null) "Add Vehicle Profile" else "Vehicle Profile",
            onClose = requestClose,
            actions = {
                if (editingExisting != null && vehicles.size > 1) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Delete", color = TorqueEvoColors.CriticalRed)
                    }
                }
                TextButton(onClick = {
                    editingExisting = null
                    name = "New Car"
                    engineCcStr = "1500"
                    carType = VehicleBodyType.MPV
                    transmission = TransmissionType.MANUAL
                    yearStr = ""
                    photoPath = null
                    weight = "1250"
                    fuelType = FuelType.GASOLINE
                    tankCap = "45.0"
                    remFuel = "45.0"
                    odometer = "0.0"
                    redline = "6800"
                    mafTrimStr = "100.0"
                }) {
                    Text("+ New Car", color = TorqueEvoColors.ElectricBlue)
                }
            }
        )

            if (vehicles.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    vehicles.sortedBy { it.id }.forEach { v ->
                        ApexFilterChip(
                            selected = v.id == activeVehicle?.id && editingExisting?.id == v.id,
                            onClick = {
                                onSelectVehicle(v.id)
                                editingExisting = v
                            },
                            label = v.name
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(TorqueEvoColors.CardSurface)
                    .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 76.dp, height = 54.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0A0F18))
                        .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (photoBmp != null) {
                        Image(
                            bitmap = photoBmp,
                            contentDescription = "Vehicle Photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text("No Photo", fontSize = 10.sp, color = TorqueEvoColors.TextSecondary)
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Car Photo", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TorqueEvoColors.TextPrimary)
                    Text("Route card background", fontSize = 10.sp, color = TorqueEvoColors.TextSecondary)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { photoPicker.launch("image/*") }) {
                        Text(if (photoBmp != null) "Change" else "Pick", fontSize = 11.sp)
                    }
                    if (photoPath != null) {
                        Text(
                            "Clear",
                            fontSize = 10.sp,
                            color = TorqueEvoColors.CriticalRed,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .clickable { photoPath = null }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Vehicle Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text("Body Type", fontSize = 11.sp, color = TorqueEvoColors.TextSecondary, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VehicleBodyType.entries.forEach { bt ->
                    ApexFilterChip(
                        selected = carType == bt,
                        onClick = { carType = bt },
                        label = bt.label,
                        compact = true
                    )
                }
            }

            Text("Transmission", fontSize = 11.sp, color = TorqueEvoColors.TextSecondary, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TransmissionType.entries.forEach { tr ->
                    ApexFilterChip(
                        selected = transmission == tr,
                        onClick = { transmission = tr },
                        label = tr.shortCode,
                        compact = true
                    )
                }
            }

            Text("Fuel Type", fontSize = 11.sp, color = TorqueEvoColors.TextSecondary, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FuelType.entries.forEach { ft ->
                    ApexFilterChip(
                        selected = fuelType == ft,
                        onClick = { fuelType = ft },
                        label = ft.label
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = engineCcStr,
                    onValueChange = { engineCcStr = it },
                    label = { Text("Engine (CC)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1.1f)
                )
                OutlinedTextField(
                    value = yearStr,
                    onValueChange = { yearStr = it },
                    label = { Text("Year") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(0.9f)
                )
                OutlinedTextField(
                    value = weight,
                    onValueChange = { weight = it },
                    label = { Text("Weight (kg)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = redline,
                    onValueChange = { redline = it },
                    label = { Text("Redline RPM") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = odometer,
                    onValueChange = { odometer = it },
                    label = { Text("Odometer ($distUnit)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = tankCap,
                    onValueChange = { tankCap = it },
                    label = { Text(if (fuelType == FuelType.ELECTRIC) "Battery ($volUnit)" else "Tank Cap ($volUnit)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = remFuel,
                    onValueChange = { remFuel = it },
                    label = { Text(if (fuelType == FuelType.ELECTRIC) "Est Charge ($volUnit)" else "Est Fuel ($volUnit)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            if (fuelType != FuelType.ELECTRIC) {
                OutlinedTextField(
                    value = mafTrimStr,
                    onValueChange = { mafTrimStr = it },
                    label = { Text("Fuel Calibration Trim (%) · default 100.0") },
                    supportingText = {
                        Text(
                            "Raise >100% if real pump refuels burn more than OBD estimates (auto-learns on Full Tank fills).",
                            fontSize = 10.sp
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Button(
                onClick = {
                    val parsedCc = engineCcStr.trim().toIntOrNull()
                        ?: (engineCcStr.trim().toDoubleOrNull()?.let { if (it < 20.0) (it * 1000.0).toInt() else it.toInt() })
                        ?: 1300
                    onSaveVehicle(
                        editingExisting,
                        name,
                        parsedCc,
                        carType,
                        transmission,
                        yearStr.trim().toIntOrNull(),
                        photoPath,
                        weight.toDoubleOrNull() ?: 1175.0,
                        fuelType,
                        tankCap.toDoubleOrNull() ?: 45.0,
                        remFuel.toDoubleOrNull() ?: 45.0,
                        odometer.toDoubleOrNull() ?: 0.0,
                        redline.toIntOrNull() ?: 7000,
                        mafTrimStr.toDoubleOrNull() ?: 100.0
                    )
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = TorqueEvoColors.ElectricBlue, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Vehicle Profile", fontWeight = FontWeight.Bold)
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RefuelLogSheet(
    vehicle: VehicleProfileEntity?,
    logs: List<RefuelLogEntity>,
    defaultCurrency: String,
    unitPrefs: UnitPreferences,
    onAddRefuel: (volume: Double, price: Double, currency: String, ron: Int?, fuelName: String?, odo: Double, isFullTank: Boolean, autoCalibrate: Boolean) -> Unit,
    onDeleteRefuel: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val fuelType = vehicle?.let { runCatching { FuelType.valueOf(it.fuelType) }.getOrNull() } ?: FuelType.GASOLINE
    val volUnit = unitPrefs.volumeSymbol(fuelType)
    val distUnit = unitPrefs.distanceSymbol()
    val initOdo = UnitConverter.convertDistanceKm(vehicle?.allTimeOdometerKm ?: 0.0, unitPrefs).first

    var volume by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf(defaultCurrency) }
    var ron by remember { mutableStateOf(if (fuelType == FuelType.GASOLINE) "92" else "") }
    var fuelName by remember { mutableStateOf("") }
    var odometer by remember { mutableStateOf("%.1f".format(initOdo)) }
    var isFullTank by remember { mutableStateOf(true) }
    var autoCalibrate by remember { mutableStateOf(true) }

    ApexLockedSheet(onBackRequest = onDismiss) {
        ApexSheetHeader(title = "Refuel Checkpoint Log", onClose = onDismiss)
            Text(
                "Tracks full-to-full checkpoint KPL and optionally refines your vehicle's Fuel Calibration Trim (%) toward real pump totals.",
                fontSize = 11.sp,
                color = TorqueEvoColors.TextSecondary
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = volume,
                    onValueChange = { volume = it },
                    label = { Text(if (fuelType == FuelType.ELECTRIC) "Charge ($volUnit)" else "Volume ($volUnit)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it },
                    label = { Text("Price ($currency)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Text("Currency", fontSize = 11.sp, color = TorqueEvoColors.TextSecondary, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("IDR", "USD", "EUR", "MYR", "SGD", "GBP").forEach { c ->
                    ApexFilterChip(selected = currency == c, onClick = { currency = c }, label = c, compact = true)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (fuelType == FuelType.GASOLINE) {
                    OutlinedTextField(
                        value = ron,
                        onValueChange = { ron = it },
                        label = { Text("RON") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(0.8f)
                    )
                }
                OutlinedTextField(
                    value = odometer,
                    onValueChange = { odometer = it },
                    label = { Text("Odometer ($distUnit)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1.2f)
                )
            }

            OutlinedTextField(
                value = fuelName,
                onValueChange = { fuelName = it },
                label = { Text("Fuel Name (Optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Full Tank Fill-Up", color = TorqueEvoColors.TextPrimary, fontSize = 12.5.sp)
                    Text("Resets estimated tank fuel to 100% capacity", fontSize = 10.sp, color = TorqueEvoColors.TextSecondary)
                }
                Switch(checked = isFullTank, onCheckedChange = { isFullTank = it })
            }
            if (isFullTank && fuelType != FuelType.ELECTRIC) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-Calibrate Fuel Trim (%)", color = TorqueEvoColors.TextPrimary, fontSize = 12.5.sp)
                        val curTrim = vehicle?.mafTrimPct ?: 100.0
                        val trackedL = vehicle?.fuelUsedSinceFullRefuelLiters ?: 0.0
                        Text(
                            "Current %.1f%% · OBD burned since fill: %.1f %s".format(curTrim, trackedL, volUnit),
                            fontSize = 10.sp,
                            color = TorqueEvoColors.TextSecondary
                        )
                    }
                    Switch(checked = autoCalibrate, onCheckedChange = { autoCalibrate = it })
                }
            }

            Button(
                onClick = {
                    val v = volume.toDoubleOrNull() ?: 0.0
                    if (v > 0.0) {
                        onAddRefuel(
                            v,
                            price.toDoubleOrNull() ?: 0.0,
                            currency,
                            ron.toIntOrNull(),
                            fuelName,
                            odometer.toDoubleOrNull() ?: initOdo,
                            isFullTank,
                            autoCalibrate
                        )
                        volume = ""
                        price = ""
                        fuelName = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = TorqueEvoColors.NominalGreen, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("+ Record Refuel Checkpoint", fontWeight = FontWeight.Bold)
            }

            if (logs.isEmpty()) {
                Text("No refuel entries yet.", color = TorqueEvoColors.TextSecondary, fontSize = 12.sp)
            } else {
                logs.forEach { entry ->
                    val (dispVol, vSym) = UnitConverter.convertVolume(entry.volumeLiters, unitPrefs, fuelType)
                    val (dispOdo, dSym) = UnitConverter.convertDistanceKm(entry.odometerKm, unitPrefs)
                    val cycleStr = entry.cycleAvgKpl?.let { kpl ->
                        val (econVal, econSym) = UnitConverter.convertFuelEconomy(kpl, unitPrefs, fuelType)
                        "%.1f %s".format(econVal, econSym)
                    } ?: "Start Checkpoint"
                    val distStr = entry.cycleDistanceKm?.let { dk ->
                        val (dd, ds) = UnitConverter.convertDistanceKm(dk, unitPrefs)
                        "+%.1f %s cycle".format(dd, ds)
                    } ?: "Baseline"

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(TorqueEvoColors.CardSurface)
                            .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val title = buildString {
                                append(entry.fuelName ?: fuelType.label)
                                entry.ronValue?.let { append(" · RON $it") }
                            }
                            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TorqueEvoColors.TextPrimary)
                            Text(
                                "Odo %.1f %s · %.1f %s · %s %s".format(
                                    dispOdo,
                                    dSym,
                                    dispVol,
                                    vSym,
                                    entry.currencyCode,
                                    NumberFormat.getInstance().format(entry.pricePaid.toLong())
                                ),
                                fontSize = 11.sp,
                                color = TorqueEvoColors.TextSecondary
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(cycleStr, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TorqueEvoColors.NominalGreen)
                            Text(distStr, fontSize = 10.sp, color = TorqueEvoColors.TextSecondary)
                            Text(
                                "Delete",
                                fontSize = 10.sp,
                                color = TorqueEvoColors.CriticalRed,
                                modifier = Modifier.clickable { onDeleteRefuel(entry.id) }
                            )
                        }
                    }
                }
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaultCodesSheet(
    report: DiagnosticReport?,
    vin: String?,
    isScanning: Boolean,
    diagnosticError: String?,
    onScan: () -> Unit,
    onClearDtcs: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (report == null && !isScanning) onScan()
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = TorqueEvoColors.ElevatedDark,
            title = { Text("Confirm Mode 04 Clear DTCs", color = TorqueEvoColors.TextPrimary) },
            text = { Text("Clears ECU trouble codes, turns off CEL/MIL, and resets readiness monitors. Keep Ignition ON and Engine OFF.", color = TorqueEvoColors.TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClear = false
                        onClearDtcs()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TorqueEvoColors.CriticalRed)
                ) { Text("Clear Faults") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }

    ApexLockedSheet(onBackRequest = onDismiss) {
        ApexSheetHeader(
            title = "Fault Codes & Readiness (03/07/0A)",
            onClose = onDismiss,
            actions = {
                OutlinedButton(onClick = onScan, enabled = !isScanning) {
                    Text(if (isScanning) "Scanning..." else "Rescan", fontSize = 12.sp)
                }
            }
        )
            Text("VIN (Mode 09): ${vin ?: "Not reported"}", fontSize = 12.sp, color = TorqueEvoColors.TextSecondary)

            val allCodes = (report?.confirmed.orEmpty() + report?.pending.orEmpty() + report?.permanent.orEmpty())
            when {
                isScanning -> {
                    Text("Scanning ECU trouble codes...", color = TorqueEvoColors.ElectricBlue, fontSize = 13.sp)
                }
                diagnosticError != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x1AF59E0B))
                            .border(1.dp, TorqueEvoColors.WarnAmber.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Adapter Offline / No ECU Response", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TorqueEvoColors.WarnAmber)
                        Text(diagnosticError, fontSize = 12.sp, color = TorqueEvoColors.TextPrimary)
                    }
                }
                report == null -> {
                    Text("Tap Rescan to query ECU trouble codes (Modes 03 / 07 / 0A).", color = TorqueEvoColors.TextSecondary, fontSize = 12.sp)
                }
                allCodes.isEmpty() -> {
                    Text(
                        "0 Trouble Codes Detected · MIL: ${if (report.readiness?.milOn == true) "ON" else "OFF"}",
                        color = TorqueEvoColors.NominalGreen,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
                else -> {
                    allCodes.forEach { dtc ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(TorqueEvoColors.CardSurface)
                                .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Text("${dtc.code} (${dtc.category.name})", fontWeight = FontWeight.Bold, color = TorqueEvoColors.WarnAmber)
                            Text(dtc.description, fontSize = 12.sp, color = TorqueEvoColors.TextPrimary)
                        }
                    }
                }
            }

            Button(
                onClick = { confirmClear = true },
                enabled = report != null && !isScanning && diagnosticError == null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF7F1D1D),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF231517),
                    disabledContentColor = Color(0xFF6B7280)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Clear Fault Codes (Mode 04)")
            }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MapDisplayControlsSection(
    mapStylePreset: MapStylePreset,
    mapCameraMode: MapCameraMode,
    mapCursorStyle: MapCursorStyle,
    highContrastRoads: Boolean,
    onSelectMapStylePreset: (MapStylePreset) -> Unit,
    onSelectMapCameraMode: (MapCameraMode) -> Unit,
    onSelectMapCursorStyle: (MapCursorStyle) -> Unit,
    onToggleHighContrastRoads: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Map Style", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MapStylePreset.entries.forEach { preset ->
                val selected = mapStylePreset == preset
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Color(preset.accentHex).copy(alpha = 0.2f) else TorqueEvoColors.CardSurface)
                        .border(
                            1.dp,
                            if (selected) Color(preset.accentHex) else TorqueEvoColors.SubtleBorder,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onSelectMapStylePreset(preset) }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(11.dp)
                            .clip(CircleShape)
                            .background(Color(preset.bgHex))
                            .border(1.5.dp, Color(preset.accentHex), CircleShape)
                    )
                    Text(preset.label, fontSize = 11.5.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = TorqueEvoColors.TextPrimary)
                }
            }
        }

        Text("Camera Perspective", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MapCameraMode.entries.forEach { camMode ->
                ApexFilterChip(
                    selected = mapCameraMode == camMode,
                    onClick = { onSelectMapCameraMode(camMode) },
                    label = camMode.label,
                    compact = true
                )
            }
        }

        Text("Map Cursor", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MapCursorStyle.entries.forEach { cursorStyle ->
                ApexFilterChip(
                    selected = mapCursorStyle == cursorStyle,
                    onClick = { onSelectMapCursorStyle(cursorStyle) },
                    label = cursorStyle.label,
                    compact = true
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("High-Contrast Dark Roads", color = TorqueEvoColors.TextPrimary, fontSize = 12.5.sp)
            Switch(checked = highContrastRoads, onCheckedChange = { onToggleHighContrastRoads() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RunLogsAndMapsSheet(
    sessions: List<TripSessionEntity>,
    vehicleName: String,
    settings: UserSettings,
    onSelectMapStylePreset: (MapStylePreset) -> Unit,
    onSelectMapCameraMode: (MapCameraMode) -> Unit,
    onSelectMapCursorStyle: (MapCursorStyle) -> Unit,
    onToggleHighContrastRoads: () -> Unit,
    unitPrefs: UnitPreferences,
    fuelType: FuelType,
    onExport: (TripSessionEntity, Boolean) -> Unit,
    onShareCard: (TripSessionEntity) -> Unit,
    isPreparingCard: Boolean = false,
    onOpenOfflineMaps: () -> Unit,
    onDismiss: () -> Unit
) {
    val fmt = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }
    var showMapOptions by remember { mutableStateOf(false) }

    ApexLockedSheet(onBackRequest = onDismiss) {
        ApexSheetHeader(title = "Runs & Maps · $vehicleName", onClose = onDismiss)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ApexFilterChip(selected = !showMapOptions, onClick = { showMapOptions = false }, label = "Saved Runs (${sessions.size})", compact = true)
            ApexFilterChip(
                selected = showMapOptions,
                onClick = { showMapOptions = !showMapOptions },
                label = "Map Style & Cursor",
                compact = true
            )
            ApexFilterChip(selected = false, onClick = onOpenOfflineMaps, label = "Offline Maps (Free)", compact = true)
        }

        if (showMapOptions) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(TorqueEvoColors.CardSurface)
                    .border(1.dp, TorqueEvoColors.ElectricBlue.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                MapDisplayControlsSection(
                    mapStylePreset = settings.mapStylePreset,
                    mapCameraMode = settings.mapCameraMode,
                    mapCursorStyle = settings.mapCursorStyle,
                    highContrastRoads = settings.highContrastMapRoads,
                    onSelectMapStylePreset = onSelectMapStylePreset,
                    onSelectMapCameraMode = onSelectMapCameraMode,
                    onSelectMapCursorStyle = onSelectMapCursorStyle,
                    onToggleHighContrastRoads = onToggleHighContrastRoads
                )
            }
        }

        if (sessions.isEmpty()) {
            Text("No saved runs yet for $vehicleName. Start a run from the HUD Mini-Map or Run Logger.", color = TorqueEvoColors.TextSecondary, fontSize = 12.sp)
        } else {
            sessions.forEach { s ->
                val (dVal, dSym) = UnitConverter.convertDistanceKm(s.distanceKm, unitPrefs)
                val (kVal, kSym) = UnitConverter.convertFuelEconomy(s.avgKpl, unitPrefs, fuelType)
                val (fVal, fSym) = UnitConverter.convertVolume(s.fuelUsedLiters, unitPrefs, fuelType)
                val (spdVal, spdSym) = UnitConverter.convert(s.effectiveAvgSpeedKmh(), SensorQuantity.SPEED, unitPrefs, fuelType)
                val (maxSpdVal, _) = UnitConverter.convert(s.maxSpeedKmh, SensorQuantity.SPEED, unitPrefs, fuelType)
                val durSec = s.effectiveDurationSec()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(TorqueEvoColors.CardSurface)
                        .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(fmt.format(Date(s.startedAtMs)), fontWeight = FontWeight.Bold, color = TorqueEvoColors.TextPrimary, fontSize = 13.5.sp)
                        Text(
                            "%.1f %s · %s · %.1f %s".format(dVal, dSym, TripRunCalculator.formatDurationClock(durSec), kVal, kSym),
                            color = TorqueEvoColors.ElectricBlue,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        RunCellStat("AVG / TOP", "%.0f / %.0f %s".format(spdVal, maxSpdVal, spdSym), Modifier.weight(1.15f))
                        RunCellStat("FUEL USED", "%.2f %s".format(fVal, fSym), Modifier.weight(0.95f))
                        RunCellStat("PEAK RPM", "%.0f".format(s.peakRpm), Modifier.weight(0.85f))
                        RunCellStat("SAMPLES", "${s.sampleCount}", Modifier.weight(0.75f))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = { onExport(s, false) }, modifier = Modifier.weight(0.8f)) { Text("CSV", fontSize = 11.sp) }
                        OutlinedButton(onClick = { onExport(s, true) }, modifier = Modifier.weight(0.8f)) { Text("JSON", fontSize = 11.sp) }
                        Button(
                            onClick = { onShareCard(s) },
                            enabled = !isPreparingCard,
                            colors = ButtonDefaults.buttonColors(containerColor = TorqueEvoColors.ElectricBlue, contentColor = Color.Black),
                            modifier = Modifier.weight(1.5f)
                        ) {
                            if (isPreparingCard) {
                                CircularProgressIndicator(
                                    color = Color.Black,
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Create Card", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RunCellStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = TorqueEvoColors.TextSecondary)
        Text(value, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.TextPrimary, maxLines = 1)
    }
}

@Composable
fun OfflineMapDownloaderDialog(
    activePmtilesPath: String?,
    gps: GpsSnapshot,
    offlineRegions: List<OfflineRegionSummary>,
    pmtilesPacks: List<PmtilesPackSummary>,
    offlineStatusMsg: String?,
    onDownloadBounds: (name: String, bounds: OfflineMapManager.BoundsBox, maxZoom: Double) -> Unit,
    onDeleteRegion: (Long) -> Unit,
    onImportPmtilesUri: (Uri) -> Unit,
    onDownloadPmtilesUrl: (name: String, url: String) -> Unit,
    onSelectPmtilesPack: (String?) -> Unit,
    onDeletePmtilesPack: (String) -> Unit,
    onRefreshRegions: () -> Unit,
    onBackToRuns: () -> Unit,
    onDismiss: () -> Unit
) {
    val fixedMaxZoom = 14.0
    val initialCenter = remember { gps.latitude to gps.longitude }
    var subTab by remember { mutableStateOf(0) } // 0 = Area Frame Picker, 1 = Whole-Island .pmtiles
    var regionName by remember { mutableStateOf("Local Area") }
    var frameBounds by remember {
        mutableStateOf(
            OfflineMapManager.BoundsBox(
                north = initialCenter.first + 0.16,
                east = initialCenter.second + 0.16,
                south = initialCenter.first - 0.16,
                west = initialCenter.second - 0.16
            )
        )
    }
    var pmtilesName by remember { mutableStateOf("Java_Island") }
    var pmtilesUrl by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { onRefreshRegions() }

    val pmtilesPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onImportPmtilesUri(uri)
    }

    val estimate = remember(frameBounds) {
        OfflineMapManager.estimateRegionSize(
            north = frameBounds.north,
            east = frameBounds.east,
            south = frameBounds.south,
            west = frameBounds.west,
            minZoom = 5.0,
            maxZoom = fixedMaxZoom
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(10.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(TorqueEvoColors.ElevatedDark)
                .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Offline Vector Maps", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TorqueEvoColors.TextPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Saved Runs",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TorqueEvoColors.ElectricBlue,
                        modifier = Modifier.clickable { onBackToRuns() }
                    )
                    Text(
                        "Close",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TorqueEvoColors.TextSecondary,
                        modifier = Modifier.clickable { onDismiss() }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ApexFilterChip(selected = subTab == 0, onClick = { subTab = 0 }, label = "Area Frame (Map)", compact = true)
                ApexFilterChip(selected = subTab == 1, onClick = { subTab = 1 }, label = "Whole-Island (.pmtiles)", compact = true)
            }

            if (subTab == 0) {
                val estColor = if (estimate.exceedsFairUseLimit) TorqueEvoColors.CriticalRed else TorqueEvoColors.NominalGreen
                val estLabel = "Frame Size: %,d tiles (~%.1f MB) · %s".format(
                    estimate.tileCount,
                    estimate.estimatedMb,
                    if (estimate.exceedsFairUseLimit) "Zoom in slightly (limit 3,000 tiles)" else "Ready offline (z5–z14)"
                )

                val controlsPanel: @Composable (Modifier) -> Unit = { panelMod ->
                    Column(
                        modifier = panelMod.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            estLabel,
                            fontSize = 12.sp,
                            color = estColor,
                            fontWeight = FontWeight.SemiBold
                        )
                        OutlinedTextField(
                            value = regionName,
                            onValueChange = { regionName = it },
                            label = { Text("Frame Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        offlineStatusMsg?.let { Text(it, fontSize = 11.sp, color = TorqueEvoColors.SkyBlue) }
                        Button(
                            onClick = { onDownloadBounds(regionName.ifBlank { "Local Area" }, frameBounds, fixedMaxZoom) },
                            enabled = !estimate.exceedsFairUseLimit && regionName.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TorqueEvoColors.ElectricBlue,
                                contentColor = Color.Black,
                                disabledContainerColor = Color(0xFF1A222D),
                                disabledContentColor = Color(0xFF6B7280)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Download Selected Frame", fontWeight = FontWeight.Bold)
                        }

                        if (offlineRegions.isNotEmpty()) {
                            Text("Downloaded Frames (${offlineRegions.size})", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.TextSecondary)
                        }
                        offlineRegions.forEach { reg ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(TorqueEvoColors.CardSurface)
                                    .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(8.dp))
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(reg.name, fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.TextPrimary)
                                    val mbStr = "%.1f".format(reg.completedBytes / (1024.0 * 1024.0))
                                    val statusTxt = if (reg.isComplete) "Ready Offline" else "${reg.completedResources}/${reg.requiredResources}"
                                    Text(
                                        "z${reg.minZoom.toInt()}–z${reg.maxZoom.toInt()} · $mbStr MB · $statusTxt",
                                        fontSize = 11.sp,
                                        color = TorqueEvoColors.TextSecondary
                                    )
                                }
                                Text(
                                    "Delete",
                                    color = TorqueEvoColors.CriticalRed,
                                    fontSize = 11.sp,
                                    modifier = Modifier.clickable { onDeleteRegion(reg.id) }
                                )
                            }
                        }
                    }
                }

                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val isLandDialog = maxWidth > maxHeight
                    if (isLandDialog) {
                        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(
                                modifier = Modifier
                                    .weight(1.15f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(1.dp, TorqueEvoColors.ElectricBlue.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                            ) {
                                OfflineFramePickerMap(
                                    initialLat = initialCenter.first,
                                    initialLon = initialCenter.second,
                                    activePmtilesPath = activePmtilesPath,
                                    onFrameBoundsChanged = { frameBounds = it },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            controlsPanel(Modifier.weight(1f).fillMaxHeight())
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "Pinch, pan, or tap +/− to resize the offline frame (like Google Maps).",
                                fontSize = 11.sp,
                                color = TorqueEvoColors.TextSecondary
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(290.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(1.dp, TorqueEvoColors.ElectricBlue.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                            ) {
                                OfflineFramePickerMap(
                                    initialLat = initialCenter.first,
                                    initialLon = initialCenter.second,
                                    activePmtilesPath = activePmtilesPath,
                                    onFrameBoundsChanged = { frameBounds = it },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            controlsPanel(Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Whole-Island .pmtiles Pack (Java / Full Regions)", fontWeight = FontWeight.Bold, color = TorqueEvoColors.TextPrimary)
                    Text(
                        "For entire Java Island (~200–350 MB in one file), extract .pmtiles from extract.bbbike.org or Protomaps and import locally or fetch via URL.",
                        fontSize = 11.sp,
                        color = TorqueEvoColors.TextSecondary
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { pmtilesPickerLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Import .pmtiles File", fontSize = 11.sp) }

                        if (activePmtilesPath != null) {
                            OutlinedButton(
                                onClick = { onSelectPmtilesPack(null) },
                                modifier = Modifier.weight(1f)
                            ) { Text("Use Frame Cache", fontSize = 11.sp) }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = pmtilesName,
                            onValueChange = { pmtilesName = it },
                            label = { Text("Pack Name") },
                            singleLine = true,
                            modifier = Modifier.weight(0.85f)
                        )
                        OutlinedTextField(
                            value = pmtilesUrl,
                            onValueChange = { pmtilesUrl = it },
                            label = { Text("Direct .pmtiles URL") },
                            singleLine = true,
                            modifier = Modifier.weight(1.35f)
                        )
                    }
                    OutlinedButton(
                        onClick = { if (pmtilesUrl.isNotBlank()) onDownloadPmtilesUrl(pmtilesName, pmtilesUrl) },
                        enabled = pmtilesUrl.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Download .pmtiles URL") }

                    offlineStatusMsg?.let { Text(it, fontSize = 11.sp, color = TorqueEvoColors.SkyBlue) }

                    pmtilesPacks.forEach { pack ->
                        val isActive = activePmtilesPath == pack.filePath
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isActive) Color(0x2200A8FF) else TorqueEvoColors.CardSurface)
                                .border(1.dp, if (isActive) TorqueEvoColors.ElectricBlue else TorqueEvoColors.SubtleBorder, RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${pack.name}.pmtiles", fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.TextPrimary)
                                Text("%.1f MB · ${if (isActive) "Active Offline Source" else "Tap Use to activate"}".format(pack.sizeBytes / (1024.0 * 1024.0)), fontSize = 11.sp, color = TorqueEvoColors.TextSecondary)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = if (isActive) "Active" else "Use",
                                    color = if (isActive) TorqueEvoColors.NominalGreen else TorqueEvoColors.ElectricBlue,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { onSelectPmtilesPack(if (isActive) null else pack.filePath) }
                                )
                                Text(
                                    "Delete",
                                    color = TorqueEvoColors.CriticalRed,
                                    fontSize = 11.sp,
                                    modifier = Modifier.clickable {
                                        if (isActive) onSelectPmtilesPack(null)
                                        onDeletePmtilesPack(pack.filePath)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EcuPidsSheet(
    supportedPids: Set<String>,
    liveValues: Map<String, Double>,
    explorerStatus: String?,
    isDeepScanning: Boolean = false,
    hasEcuDump: Boolean = false,
    onDiscoverExplorer: () -> Unit,
    onStopExplorer: () -> Unit,
    onAddCustomPid: (name: String, hex: String, formula: String, unit: String, headerHex: String?) -> Unit,
    onImportCsv: (String) -> Unit,
    onRunDeepDump: () -> Unit,
    onShareDeepDump: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var hex by remember { mutableStateOf("") }
    var headerHex by remember { mutableStateOf("") }
    var formula by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    var csvText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { onDiscoverExplorer() }

    val closeExplorer = {
        onStopExplorer()
        onDismiss()
    }
    ApexLockedSheet(onBackRequest = closeExplorer) {
        ApexSheetHeader(
            title = "ECU Supported PIDs (${supportedPids.size})",
            onClose = closeExplorer,
            actions = {
                if (hasEcuDump && !isDeepScanning) {
                    OutlinedButton(onClick = onShareDeepDump) {
                        Text("Share Dump", fontSize = 11.sp)
                    }
                }
                OutlinedButton(onClick = onRunDeepDump, enabled = !isDeepScanning) {
                    if (isDeepScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = TorqueEvoColors.ElectricBlue
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Scanning...", fontSize = 11.sp)
                    } else {
                        Text("Deep Scan", fontSize = 11.sp)
                    }
                }
            }
        )
            if (isDeepScanning) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = TorqueEvoColors.ElectricBlue,
                    trackColor = TorqueEvoColors.SubtleBorder
                )
            }
            explorerStatus?.let {
                Text(it, fontSize = 11.sp, color = if (isDeepScanning) TorqueEvoColors.ElectricBlue else TorqueEvoColors.SkyBlue)
            }

            ObdPidRegistry.standardPids
                .filter {
                    supportedPids.isEmpty() || it.pidHex in supportedPids ||
                        (it.key == "fuel_level" && ToyotaMeterCapabilities.TOKEN_FUEL_LEVEL in supportedPids)
                }
                .forEach { pid ->
                    val viaMeter = pid.key == "fuel_level" &&
                        ToyotaMeterCapabilities.TOKEN_FUEL_LEVEL in supportedPids &&
                        pid.pidHex !in supportedPids
                    val tag = if (viaMeter) "7C0 2129" else "01${pid.pidHex}"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(TorqueEvoColors.CardSurface)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("$tag · ${pid.name}", fontSize = 12.sp, color = TorqueEvoColors.TextPrimary)
                        val v = liveValues[pid.key]
                        Text(
                            if (v != null) "%.1f %s".format(v, pid.unit) else "—",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TorqueEvoColors.ElectricBlue
                        )
                    }
                }

            val meterSpecs = listOf(
                Triple(ToyotaMeterCapabilities.TOKEN_ODOMETER, "ecu_odometer", "7C0 21E1 · Cluster Odometer" to "km"),
                Triple(ToyotaMeterCapabilities.TOKEN_CLUSTER_SPEED, "cluster_speed", "7C0 2121 · Cluster Speed" to "km/h"),
                Triple(ToyotaMeterCapabilities.TOKEN_CLUSTER_RPM, "cluster_rpm", "7C0 2122 · Cluster RPM" to "RPM"),
                Triple(ToyotaMeterCapabilities.TOKEN_CLUSTER_COOLANT, "cluster_coolant", "7C0 2123 · Cluster Coolant" to "°C")
            ).filter { (token, _, _) -> token in supportedPids }

            if (meterSpecs.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Combination Meter (7C0) Live PIDs", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TorqueEvoColors.SkyBlue)
                meterSpecs.forEach { (_, key, labelUnit) ->
                    val (label, unitStr) = labelUnit
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(TorqueEvoColors.CardSurface)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(label, fontSize = 12.sp, color = TorqueEvoColors.TextPrimary)
                        val v = liveValues[key]
                        val formatted = when {
                            v == null -> "—"
                            key == "ecu_odometer" || key == "cluster_rpm" -> "%.0f %s".format(v, unitStr)
                            else -> "%.1f %s".format(v, unitStr)
                        }
                        Text(
                            formatted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TorqueEvoColors.NominalGreen
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text("Add Custom / UDS Mode 21–22 PID", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TorqueEvoColors.TextPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1.1f))
                OutlinedTextField(value = hex, onValueChange = { hex = it }, label = { Text("Mode/PID e.g. 2129") }, singleLine = true, modifier = Modifier.weight(0.95f))
                OutlinedTextField(value = headerHex, onValueChange = { headerHex = it }, label = { Text("Hdr (7C0)") }, singleLine = true, modifier = Modifier.weight(0.65f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = formula, onValueChange = { formula = it }, label = { Text("Formula e.g. (A*256+B)/10") }, singleLine = true, modifier = Modifier.weight(1.4f))
                OutlinedTextField(value = unit, onValueChange = { unit = it }, label = { Text("Unit") }, singleLine = true, modifier = Modifier.weight(0.6f))
            }
            Button(
                onClick = {
                    onAddCustomPid(name, hex, formula, unit, headerHex.ifBlank { null })
                    name = ""; hex = ""; headerHex = ""; formula = ""; unit = ""
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Add Custom PID") }

            OutlinedTextField(
                value = csvText,
                onValueChange = { csvText = it },
                label = { Text("Paste Torque CSV Lines") },
                modifier = Modifier.fillMaxWidth().heightIn(max = 90.dp)
            )
            OutlinedButton(
                onClick = {
                    onImportCsv(csvText)
                    csvText = ""
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Import Torque CSV") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdapterConnectionSheet(
    settings: UserSettings,
    connectionState: ConnectionState,
    backgroundServiceActive: Boolean,
    btPairedDevices: List<BtDeviceCandidate>,
    bleScannedDevices: List<BtDeviceCandidate>,
    bleScanning: Boolean,
    usbDevices: List<UsbSerialCandidate>,
    onRefreshLocalAdapters: () -> Unit,
    onScanBleAdapters: () -> Unit,
    onSelectConnectionType: (ObdConnectionType) -> Unit,
    onSaveWifiConnection: (host: String, port: Int, dualCell: Boolean) -> Unit,
    onSelectBtDevice: (BtDeviceCandidate) -> Unit,
    onSelectBleDevice: (BtDeviceCandidate) -> Unit,
    onSelectUsbDevice: (deviceId: Int, baudRate: Int) -> Unit,
    onRequestUsbPermission: (Int) -> Unit,
    onResumeBackgroundService: () -> Unit,
    onTerminateBackgroundService: () -> Unit,
    onDismiss: () -> Unit
) {
    var host by remember(settings.wifiHost) { mutableStateOf(settings.wifiHost) }
    var port by remember(settings.wifiPort) { mutableStateOf("${settings.wifiPort}") }
    var manualBtMac by remember(settings.btDeviceAddress) { mutableStateOf(settings.btDeviceAddress) }
    var selectedBaud by remember(settings.usbBaudRate) { mutableStateOf(settings.usbBaudRate) }

    LaunchedEffect(Unit) { onRefreshLocalAdapters() }

    val statusColor = when (connectionState) {
        is ConnectionState.Connected -> TorqueEvoColors.NominalGreen
        is ConnectionState.Connecting -> TorqueEvoColors.ElectricBlue
        is ConnectionState.Reconnecting -> TorqueEvoColors.WarnAmber
        is ConnectionState.Disconnected -> if (backgroundServiceActive) TorqueEvoColors.ElectricBlue else TorqueEvoColors.CriticalRed
    }
    val statusText = when (connectionState) {
        is ConnectionState.Connected -> "Connected · ${connectionState.handshake.protocolName}"
        is ConnectionState.Connecting -> "Connecting to ${settings.adapterDetailedEndpoint()}..."
        is ConnectionState.Reconnecting -> "Reconnecting (#${connectionState.attempt}) · ${settings.adapterDetailedEndpoint()}"
        is ConnectionState.Disconnected -> if (backgroundServiceActive) "Starting background listener..." else "Background listener stopped"
    }

    ApexLockedSheet(onBackRequest = onDismiss) {
        ApexSheetHeader(title = "OBD2 Adapter Connection", onClose = onDismiss)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(TorqueEvoColors.CardSurface)
                .border(1.dp, statusColor.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = statusText,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = TorqueEvoColors.TextPrimary
                )
                Text(
                    text = if (backgroundServiceActive) "Runs in background · Tap notification to stop" else "Tap Connect to start background listener",
                    fontSize = 10.sp,
                    color = TorqueEvoColors.TextSecondary
                )
            }
            Text(
                text = if (backgroundServiceActive) "Stop" else "Connect",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (backgroundServiceActive) TorqueEvoColors.CriticalRed else Color.Black,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (backgroundServiceActive) TorqueEvoColors.CriticalRed.copy(alpha = 0.14f) else TorqueEvoColors.ElectricBlue)
                    .border(
                        1.dp,
                        if (backgroundServiceActive) TorqueEvoColors.CriticalRed.copy(alpha = 0.5f) else TorqueEvoColors.ElectricBlue,
                        RoundedCornerShape(7.dp)
                    )
                    .clickable {
                        if (backgroundServiceActive) onTerminateBackgroundService() else onResumeBackgroundService()
                    }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }

        Text("Connection Mechanism", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ObdConnectionType.entries.forEach { mode ->
                ApexFilterChip(
                    selected = settings.connectionType == mode,
                    onClick = { onSelectConnectionType(mode) },
                    label = mode.label,
                    compact = true
                )
            }
        }

        HorizontalDivider(color = TorqueEvoColors.SubtleBorder)

        when (settings.connectionType) {
            ObdConnectionType.WIFI_TCP -> {
                Text("Wi-Fi TCP Endpoint (ELM327 Wi-Fi)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.ElectricBlue)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Wi-Fi Host IP") },
                        singleLine = true,
                        modifier = Modifier.weight(1.4f)
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Port") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(0.7f)
                    )
                }
                Text(
                    text = "Dedicated Low-Latency Wi-Fi · Dual mobile-data keepalive is disabled while connected for maximum OBD speed.",
                    color = TorqueEvoColors.TextSecondary,
                    fontSize = 11.sp
                )
                Button(
                    onClick = {
                        onSaveWifiConnection(host.ifBlank { "192.168.0.10" }, port.toIntOrNull() ?: 35000, false)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TorqueEvoColors.ElectricBlue, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save Wi-Fi Endpoint", fontWeight = FontWeight.Bold) }
            }

            ObdConnectionType.BLUETOOTH_CLASSIC -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Paired Bluetooth Classic (SPP)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.ElectricBlue)
                    Text(
                        "Refresh",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TorqueEvoColors.ElectricBlue,
                        modifier = Modifier.clickable { onRefreshLocalAdapters() }
                    )
                }
                if (btPairedDevices.isEmpty()) {
                    val hasPerm = BluetoothClassicObdTransport.hasConnectPermission(androidx.compose.ui.platform.LocalContext.current)
                    Text(
                        if (!hasPerm) "Allow Bluetooth Connect permission in Android Settings, then tap Refresh."
                        else "No paired Bluetooth devices found. Pair your ELM327 in Android Bluetooth settings or enter MAC below.",
                        fontSize = 11.sp,
                        color = TorqueEvoColors.TextSecondary
                    )
                } else {
                    btPairedDevices.forEach { dev ->
                        val selected = dev.address.equals(settings.btDeviceAddress, ignoreCase = true)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (selected) TorqueEvoColors.DimBlue else TorqueEvoColors.CardSurface)
                                .border(
                                    1.dp,
                                    if (selected) TorqueEvoColors.ElectricBlue else TorqueEvoColors.SubtleBorder,
                                    RoundedCornerShape(9.dp)
                                )
                                .clickable { onSelectBtDevice(dev) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(dev.name, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = TorqueEvoColors.TextPrimary)
                                Text(dev.address, fontSize = 10.sp, color = TorqueEvoColors.TextSecondary)
                            }
                            if (dev.likelyObd) {
                                Text("OBD", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = TorqueEvoColors.NominalGreen)
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = manualBtMac,
                    onValueChange = { manualBtMac = it },
                    label = { Text("Manual BT MAC (blank = Auto SPP OBD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val mac = manualBtMac.trim().uppercase()
                        onSelectBtDevice(BtDeviceCandidate(mac, mac.ifBlank { "BT SPP Auto" }, true))
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TorqueEvoColors.ElectricBlue, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Use Bluetooth Classic SPP", fontWeight = FontWeight.Bold) }
            }

            ObdConnectionType.BLUETOOTH_LE -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Bluetooth Low Energy (GATT)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.ElectricBlue)
                    OutlinedButton(onClick = onScanBleAdapters, enabled = !bleScanning) {
                        if (bleScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp, color = TorqueEvoColors.ElectricBlue)
                            Spacer(Modifier.width(6.dp))
                            Text("Scanning...", fontSize = 11.sp)
                        } else {
                            Text("Scan BLE", fontSize = 11.sp)
                        }
                    }
                }
                if (settings.bleDeviceAddress.isNotBlank()) {
                    Text(
                        "Saved target: ${settings.bleDeviceName.ifBlank { "BLE Adapter" }} (${settings.bleDeviceAddress})",
                        fontSize = 11.sp,
                        color = TorqueEvoColors.NominalGreen
                    )
                }
                if (bleScannedDevices.isEmpty() && !bleScanning) {
                    Text("Tap Scan BLE to find adapters.", fontSize = 11.sp, color = TorqueEvoColors.TextSecondary)
                } else {
                    bleScannedDevices.forEach { dev ->
                        val selected = dev.address.equals(settings.bleDeviceAddress, ignoreCase = true)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (selected) TorqueEvoColors.DimBlue else TorqueEvoColors.CardSurface)
                                .border(
                                    1.dp,
                                    if (selected) TorqueEvoColors.ElectricBlue else TorqueEvoColors.SubtleBorder,
                                    RoundedCornerShape(9.dp)
                                )
                                .clickable {
                                    onSelectBleDevice(dev)
                                    onDismiss()
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(dev.name, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = TorqueEvoColors.TextPrimary)
                                Text(dev.address, fontSize = 10.sp, color = TorqueEvoColors.TextSecondary)
                            }
                            if (dev.likelyObd) {
                                Text("OBD BLE", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = TorqueEvoColors.NominalGreen)
                            }
                        }
                    }
                }
            }

            ObdConnectionType.USB_SERIAL -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("USB Serial OTG Adapters", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.ElectricBlue)
                    Text(
                        "Refresh",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TorqueEvoColors.ElectricBlue,
                        modifier = Modifier.clickable { onRefreshLocalAdapters() }
                    )
                }
                Text("Baud Rate", fontSize = 11.sp, color = TorqueEvoColors.TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(38400, 115200, 500000).forEach { baud ->
                        ApexFilterChip(
                            selected = selectedBaud == baud,
                            onClick = {
                                selectedBaud = baud
                                onSelectUsbDevice(settings.usbDeviceId, baud)
                            },
                            label = "$baud bps",
                            compact = true
                        )
                    }
                }
                if (usbDevices.isEmpty()) {
                    Text(
                        "No USB OTG adapter detected. Plug an FTDI, CP210x, CH340, or CDC-ACM ELM327 USB cable via OTG.",
                        fontSize = 11.sp,
                        color = TorqueEvoColors.TextSecondary
                    )
                } else {
                    usbDevices.forEach { dev ->
                        val selected = settings.usbDeviceId == dev.deviceId || settings.usbDeviceId == 0
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (selected) TorqueEvoColors.DimBlue else TorqueEvoColors.CardSurface)
                                .border(
                                    1.dp,
                                    if (selected) TorqueEvoColors.ElectricBlue else TorqueEvoColors.SubtleBorder,
                                    RoundedCornerShape(9.dp)
                                )
                                .clickable {
                                    if (dev.hasPermission) {
                                        onSelectUsbDevice(dev.deviceId, selectedBaud)
                                    } else {
                                        onRequestUsbPermission(dev.deviceId)
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(dev.label, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = TorqueEvoColors.TextPrimary)
                                Text("${dev.chipFamily.name} · ${if (dev.hasPermission) "Permission Granted" else "Tap to Grant USB"}", fontSize = 10.sp, color = TorqueEvoColors.TextSecondary)
                            }
                        }
                    }
                }
                Button(
                    onClick = {
                        onSelectUsbDevice(usbDevices.firstOrNull()?.deviceId ?: settings.usbDeviceId, selectedBaud)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TorqueEvoColors.ElectricBlue, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Use USB Serial @ $selectedBaud bps", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(
    settings: UserSettings,
    onOpenAdapterConnection: () -> Unit,
    onSelectPolling: (PollingProfile) -> Unit,
    onSelectGaugeSmoothing: (GaugeSmoothingMode) -> Unit,
    onToggleLiveMicroMotion: () -> Unit = {},
    onSelectFuelCoasting: (FuelCoastingMode) -> Unit,
    onSelectGridScale: (HudGridScale) -> Unit,
    onSelectMapStylePreset: (MapStylePreset) -> Unit,
    onSelectMapCameraMode: (MapCameraMode) -> Unit,
    onSelectMapCursorStyle: (MapCursorStyle) -> Unit,
    onToggleShareMetric: (ShareCardMetric) -> Unit,
    onResetShareMetrics: () -> Unit,
    onToggleHighContrastRoads: () -> Unit,
    onSelectHudTheme: (HudThemePreset) -> Unit,
    onSelectCanvasBackdrop: (HudCanvasBackdrop) -> Unit,
    onSelectCardStyle: (HudCardStyle) -> Unit,
    onToggleWidgetBorders: () -> Unit,
    onUpdateUnits: (UnitPreferences) -> Unit,
    onUpdateCurrency: (String) -> Unit,
    onToggleHudMirror: () -> Unit,
    onOpenOfflineMaps: () -> Unit,
    onDismiss: () -> Unit
) {
    var unitState by remember(settings) { mutableStateOf(settings.toUnitPreferences()) }

    ApexLockedSheet(onBackRequest = onDismiss) {
        ApexSheetHeader(title = "Settings · Units, Polling & HUD", onClose = onDismiss)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(TorqueEvoColors.CardSurface)
                .border(1.dp, TorqueEvoColors.ElectricBlue.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                .clickable { onOpenAdapterConnection() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("OBD2 Adapter Mechanism", fontSize = 11.sp, color = TorqueEvoColors.TextSecondary)
                Text(
                    "${settings.connectionType.label} · ${settings.adapterDetailedEndpoint()}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TorqueEvoColors.TextPrimary
                )
            }
            Text("Change →", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = TorqueEvoColors.ElectricBlue)
        }

            MapDisplayControlsSection(
                mapStylePreset = settings.mapStylePreset,
                mapCameraMode = settings.mapCameraMode,
                mapCursorStyle = settings.mapCursorStyle,
                highContrastRoads = settings.highContrastMapRoads,
                onSelectMapStylePreset = onSelectMapStylePreset,
                onSelectMapCameraMode = onSelectMapCameraMode,
                onSelectMapCursorStyle = onSelectMapCursorStyle,
                onToggleHighContrastRoads = onToggleHighContrastRoads
            )
            OutlinedButton(onClick = onOpenOfflineMaps, modifier = Modifier.fillMaxWidth()) {
                Text("Manage Offline Vector Map Regions (MapLibre)")
            }

            ShareCardMetricPickerSection(
                selectedMetrics = settings.shareCardMetrics,
                onToggleMetric = onToggleShareMetric,
                onResetDefault = onResetShareMetrics
            )

            Text("Polling Rate", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.TextSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PollingProfile.entries.forEach { p ->
                    ApexFilterChip(
                        selected = settings.pollingProfile == p,
                        onClick = { onSelectPolling(p) },
                        label = p.label,
                        compact = true
                    )
                }
            }

            Text("Gauge Smoothing", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.TextSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GaugeSmoothingMode.entries.forEach { sm ->
                    ApexFilterChip(
                        selected = settings.gaugeSmoothingMode == sm,
                        onClick = { onSelectGaugeSmoothing(sm) },
                        label = sm.label,
                        compact = true
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Live Display Micro-Pulse (Test)", color = TorqueEvoColors.TextPrimary, fontSize = 12.5.sp)
                    Text(
                        "Sub-unit visual motion on live engine metrics without changing logged data",
                        color = TorqueEvoColors.TextSecondary,
                        fontSize = 10.sp
                    )
                }
                Switch(checked = settings.liveMicroMotion, onCheckedChange = { onToggleLiveMicroMotion() })
            }

            Text("Coasting Fuel Cut", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.TextSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FuelCoastingMode.entries.forEach { cm ->
                    ApexFilterChip(
                        selected = settings.fuelCoastingMode == cm,
                        onClick = { onSelectFuelCoasting(cm) },
                        label = cm.label,
                        compact = true
                    )
                }
            }

            Text("Grid Scale", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.TextSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HudGridScale.entries.forEach { scale ->
                    ApexFilterChip(
                        selected = settings.hudGridScale == scale,
                        onClick = { onSelectGridScale(scale) },
                        label = scale.label,
                        compact = true
                    )
                }
            }

            Text("Units", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.TextSecondary)
            UnitChipRow("Speed / Distance", SpeedUnit.entries, unitState.speed, { "${it.symbol} (${if (it == SpeedUnit.KMH) "km" else "mi"})" }) {
                unitState = unitState.copy(speed = it)
                onUpdateUnits(unitState)
            }
            UnitChipRow("Fuel Economy", FuelEconomyUnit.entries, unitState.fuelEconomy, { it.symbol }) {
                unitState = unitState.copy(fuelEconomy = it)
                onUpdateUnits(unitState)
            }
            UnitChipRow("Fuel Volume", VolumeUnit.entries, unitState.volume, { it.symbol }) {
                unitState = unitState.copy(volume = it)
                onUpdateUnits(unitState)
            }
            UnitChipRow("Temperature", TempUnit.entries, unitState.temperature, { it.symbol }) {
                unitState = unitState.copy(temperature = it)
                onUpdateUnits(unitState)
            }
            UnitChipRow("Pressure / Boost", PressureUnit.entries, unitState.pressure, { it.symbol }) {
                unitState = unitState.copy(pressure = it)
                onUpdateUnits(unitState)
            }
            UnitChipRow("Airflow", AirflowUnit.entries, unitState.airflow, { it.symbol }) {
                unitState = unitState.copy(airflow = it)
                onUpdateUnits(unitState)
            }
            UnitChipRow("Power", PowerUnit.entries, unitState.power, { it.symbol }) {
                unitState = unitState.copy(power = it)
                onUpdateUnits(unitState)
            }
            UnitChipRow("Torque", TorqueUnit.entries, unitState.torque, { it.symbol }) {
                unitState = unitState.copy(torque = it)
                onUpdateUnits(unitState)
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("HUD Windshield Mirror Flip", color = TorqueEvoColors.TextPrimary, fontSize = 13.sp)
                Switch(checked = settings.hudMirror, onCheckedChange = { onToggleHudMirror() })
            }

            HorizontalDivider(color = TorqueEvoColors.SubtleBorder)
            Text("HUD Appearance", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.SkyBlue)
            HudAppearanceControlsColumn(
                selectedTheme = settings.hudTheme,
                selectedBackdrop = settings.hudCanvasBackdrop,
                selectedCardStyle = settings.hudCardStyle,
                showBorders = settings.showWidgetBorders,
                onSelectTheme = onSelectHudTheme,
                onSelectBackdrop = onSelectCanvasBackdrop,
                onSelectCardStyle = onSelectCardStyle,
                onToggleBorders = onToggleWidgetBorders
            )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HudAppearanceControlsColumn(
    selectedTheme: HudThemePreset,
    selectedBackdrop: HudCanvasBackdrop,
    selectedCardStyle: HudCardStyle,
    showBorders: Boolean,
    onSelectTheme: (HudThemePreset) -> Unit,
    onSelectBackdrop: (HudCanvasBackdrop) -> Unit,
    onSelectCardStyle: (HudCardStyle) -> Unit,
    onToggleBorders: () -> Unit,
    selectedGridScale: HudGridScale? = null,
    onSelectGridScale: ((HudGridScale) -> Unit)? = null,
    selectedSmoothing: GaugeSmoothingMode? = null,
    onSelectSmoothing: ((GaugeSmoothingMode) -> Unit)? = null,
    liveMicroMotion: Boolean? = null,
    onToggleLiveMicroMotion: (() -> Unit)? = null,
    hudMirror: Boolean? = null,
    onToggleHudMirror: (() -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Show Component Borders", color = TorqueEvoColors.TextPrimary, fontSize = 13.sp)
            Switch(checked = showBorders, onCheckedChange = { onToggleBorders() })
        }

        Text("Theme", fontSize = 11.sp, color = TorqueEvoColors.TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HudThemePreset.entries.forEach { preset ->
                val selected = selectedTheme == preset
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Color(preset.accentHex).copy(alpha = 0.18f) else TorqueEvoColors.CardSurface)
                        .border(
                            1.dp,
                            if (selected) Color(preset.accentHex) else TorqueEvoColors.SubtleBorder,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onSelectTheme(preset) }
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(Color(preset.accentHex))
                    )
                    Text(
                        text = preset.label,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) TorqueEvoColors.TextPrimary else TorqueEvoColors.TextSecondary
                    )
                }
            }
        }

        Text("Canvas Background", fontSize = 11.sp, color = TorqueEvoColors.TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudCanvasBackdrop.entries.forEach { bg ->
                ApexFilterChip(selected = selectedBackdrop == bg, onClick = { onSelectBackdrop(bg) }, label = bg.label, compact = true)
            }
        }

        Text("Component Card Style", fontSize = 11.sp, color = TorqueEvoColors.TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudCardStyle.entries.forEach { cs ->
                ApexFilterChip(selected = selectedCardStyle == cs, onClick = { onSelectCardStyle(cs) }, label = cs.label, compact = true)
            }
        }

        if (selectedGridScale != null && onSelectGridScale != null) {
            Text("HUD Dashboard Grid Scale", fontSize = 11.sp, color = TorqueEvoColors.TextSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HudGridScale.entries.forEach { scale ->
                    ApexFilterChip(
                        selected = selectedGridScale == scale,
                        onClick = { onSelectGridScale(scale) },
                        label = scale.label,
                        compact = true
                    )
                }
            }
        }

        if (selectedSmoothing != null && onSelectSmoothing != null) {
            Text("Gauge Display Smoothing", fontSize = 11.sp, color = TorqueEvoColors.TextSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GaugeSmoothingMode.entries.forEach { sm ->
                    ApexFilterChip(
                        selected = selectedSmoothing == sm,
                        onClick = { onSelectSmoothing(sm) },
                        label = sm.label,
                        compact = true
                    )
                }
            }
            if (liveMicroMotion != null && onToggleLiveMicroMotion != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Live Display Micro-Pulse (Test)", color = TorqueEvoColors.TextPrimary, fontSize = 12.sp)
                    Switch(checked = liveMicroMotion, onCheckedChange = { onToggleLiveMicroMotion() })
                }
            }
        }

        if (hudMirror != null && onToggleHudMirror != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("HUD Windshield Mirror Flip", color = TorqueEvoColors.TextPrimary, fontSize = 12.5.sp)
                Switch(checked = hudMirror, onCheckedChange = { onToggleHudMirror() })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShareCardComposerDialog(
    draft: HudViewModel.ShareCardDraft,
    initialConfig: TripCardConfig,
    unitPrefs: UnitPreferences,
    fuelType: FuelType,
    isExporting: Boolean,
    onConfigChanged: (TripCardConfig) -> Unit,
    onPickBackgroundPhoto: (Uri, (String?) -> Unit) -> Unit,
    onLoadSurroundingRoads: suspend (List<TelemetrySampleEntity>, Int, Int, Boolean) -> TripCardRenderer.RoadContextLayer?,
    onConfirmExport: (TripCardConfig, HudViewModel.ShareCardExportAction, TripCardRenderer.RoadContextLayer?) -> Unit,
    onDismiss: () -> Unit
) {
    val defaultBg = when {
        initialConfig.backgroundMode == ShareCardBackgroundMode.CUSTOM_PHOTO && !initialConfig.customPhotoPath.isNullOrBlank() ->
            ShareCardBackgroundMode.CUSTOM_PHOTO
        initialConfig.backgroundMode == ShareCardBackgroundMode.CAR_PHOTO && draft.vehicle?.photoUri.isNullOrBlank() ->
            ShareCardBackgroundMode.PLAIN_DARK
        else -> initialConfig.backgroundMode
    }
    var config by remember(draft.session.id) {
        mutableStateOf(
            initialConfig.copy(
                backgroundMode = defaultBg,
                selectedMetrics = initialConfig.selectedMetrics.ifEmpty { ShareCardMetric.DEFAULT_SELECTION }
            )
        )
    }

    fun updateConfig(next: TripCardConfig) {
        config = next
        onConfigChanged(next)
    }

    var previewBitmap by remember(draft.session.id) { mutableStateOf<ImageBitmap?>(null) }
    var roadContextCache by remember(draft.session.id) { mutableStateOf<TripCardRenderer.RoadContextLayer?>(null) }
    var roadOpacityDraft by remember(config.surroundingRoadsOpacityPct) {
        mutableStateOf(config.surroundingRoadsOpacityPct.toFloat())
    }
    var isRenderingPreview by remember(draft.session.id) { mutableStateOf(true) }
    var isPickingBgPhoto by remember(draft.session.id) { mutableStateOf(false) }
    var showExportChooser by remember { mutableStateOf(false) }

    val cardBgPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            isPickingBgPhoto = true
            onPickBackgroundPhoto(uri) { savedPath ->
                isPickingBgPhoto = false
                if (!savedPath.isNullOrBlank()) {
                    updateConfig(
                        config.copy(
                            backgroundMode = ShareCardBackgroundMode.CUSTOM_PHOTO,
                            customPhotoPath = savedPath
                        )
                    )
                }
            }
        }
    }

    LaunchedEffect(draft, config, unitPrefs, fuelType) {
        isRenderingPreview = true
        try {
            val activeRoadLayer = if (config.showSurroundingRoads) {
                val (reqW, reqH) = TripCardRenderer.routeCanvasSizeForConfig(config)
                val cached = roadContextCache
                if (
                    cached != null &&
                    !cached.roadsAlphaBitmap.isRecycled &&
                    cached.boxWidth == reqW &&
                    cached.boxHeight == reqH &&
                    cached.mainRoadsOnly == config.surroundingRoadsMainOnly
                ) {
                    cached
                } else {
                    val captured = onLoadSurroundingRoads(draft.samples, reqW, reqH, config.surroundingRoadsMainOnly)
                    if (captured != null) {
                        roadContextCache = captured
                    }
                    captured
                }
            } else null

            val rendered = withContext(Dispatchers.Default) {
                TripCardRenderer.renderTripCardBitmap(
                    session = draft.session,
                    samples = draft.samples,
                    vehicle = draft.vehicle,
                    unitPrefs = unitPrefs,
                    fuelType = fuelType,
                    selectedMetrics = config.selectedMetrics,
                    config = config,
                    precomputedStats = draft.stats,
                    roadContextLayer = activeRoadLayer
                ).asImageBitmap()
            }
            previewBitmap = rendered
        } finally {
            isRenderingPreview = false
        }
    }

    if (showExportChooser) {
        AlertDialog(
            onDismissRequest = { if (!isExporting) showExportChooser = false },
            containerColor = TorqueEvoColors.ElevatedDark,
            title = {
                Text(
                    "Export Route Card (${config.aspectRatio.label})",
                    fontWeight = FontWeight.Bold,
                    color = TorqueEvoColors.TextPrimary
                )
            },
            text = {
                Text(
                    "Share directly to Instagram Stories, chats, or other apps, or save the PNG file to Pictures/ApexOBD.",
                    color = TorqueEvoColors.TextSecondary,
                    fontSize = 12.5.sp
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !isExporting,
                        onClick = {
                            showExportChooser = false
                            onConfirmExport(config, HudViewModel.ShareCardExportAction.DOWNLOAD_PNG, roadContextCache)
                        }
                    ) {
                        Text("Download PNG", color = TorqueEvoColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        enabled = !isExporting,
                        onClick = {
                            showExportChooser = false
                            onConfirmExport(config, HudViewModel.ShareCardExportAction.SHARE_TO_APPS, roadContextCache)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = TorqueEvoColors.ElectricBlue, contentColor = Color.Black)
                    ) {
                        Text("Share to Apps", fontWeight = FontWeight.ExtraBold)
                    }
                }
            },
            dismissButton = {
                TextButton(enabled = !isExporting, onClick = { showExportChooser = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    ApexLockedSheet(onBackRequest = onDismiss, scrollable = false) {
        ApexSheetHeader(
            title = "Create Route Card",
            onClose = onDismiss,
            actions = {
                TextButton(
                    onClick = {
                        val resetBg = if (!draft.vehicle?.photoUri.isNullOrBlank()) {
                            ShareCardBackgroundMode.CAR_PHOTO
                        } else {
                            ShareCardBackgroundMode.PLAIN_DARK
                        }
                        updateConfig(
                            TripCardConfig(
                                backgroundMode = resetBg,
                                aspectRatio = ShareCardAspectRatio.SQUARE_1_1,
                                selectedMetrics = ShareCardMetric.DEFAULT_SELECTION
                            )
                        )
                    }
                ) {
                    Text("Reset", color = TorqueEvoColors.ElectricBlue, fontSize = 12.sp)
                }
            }
        )

        val ratioFloat = config.aspectRatio.widthPx.toFloat() / config.aspectRatio.heightPx.toFloat()
        val previewCard: @Composable (Modifier) -> Unit = { boxMod ->
            Box(
                modifier = boxMod
                    .heightIn(max = 228.dp)
                    .aspectRatio(ratioFloat)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (config.backgroundMode == ShareCardBackgroundMode.TRANSPARENT) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cell = 12.dp.toPx()
                        val cols = (size.width / cell).toInt() + 1
                        val rows = (size.height / cell).toInt() + 1
                        for (r in 0 until rows) {
                            for (c in 0 until cols) {
                                val even = (r + c) % 2 == 0
                                drawRect(
                                    color = if (even) Color(0xFF1F242D) else Color(0xFF2D3440),
                                    topLeft = Offset(c * cell, r * cell),
                                    size = Size(cell, cell)
                                )
                            }
                        }
                    }
                }
                previewBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp,
                        contentDescription = "Route Card Preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (isRenderingPreview || isPickingBgPhoto || isExporting || previewBitmap == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x9905080E)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CircularProgressIndicator(
                                color = TorqueEvoColors.ElectricBlue,
                                modifier = Modifier.size(26.dp),
                                strokeWidth = 2.5.dp
                            )
                            Text(
                                text = when {
                                    isPickingBgPhoto -> "Loading photo..."
                                    isExporting -> "Exporting PNG..."
                                    else -> "Generating..."
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TorqueEvoColors.TextPrimary
                            )
                        }
                    }
                }
            }
        }

        val controlsBody: @Composable (Modifier) -> Unit = { scrollMod ->
            Column(
                modifier = scrollMod.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Card Aspect Ratio", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ShareCardAspectRatio.entries.forEach { ratioOpt ->
                        ApexFilterChip(
                            selected = config.aspectRatio == ratioOpt,
                            onClick = { updateConfig(config.copy(aspectRatio = ratioOpt)) },
                            label = ratioOpt.label,
                            compact = true
                        )
                    }
                }

                Text("Background Style", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.TextSecondary)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ShareCardBackgroundMode.entries.forEach { mode ->
                        val isCustom = mode == ShareCardBackgroundMode.CUSTOM_PHOTO
                        val hasCustomImg = !config.customPhotoPath.isNullOrBlank()
                        val isSelected = config.backgroundMode == mode
                        ApexFilterChip(
                            selected = isSelected,
                            onClick = {
                                if (isCustom && (!hasCustomImg || isSelected)) {
                                    cardBgPicker.launch("image/*")
                                } else {
                                    updateConfig(config.copy(backgroundMode = mode))
                                }
                            },
                            label = mode.label,
                            compact = true,
                            trailingIcon = if (isCustom && hasCustomImg) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Change custom image",
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            } else null
                        )
                    }
                }

                Text("Include Components", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.TextSecondary)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        ComposerCheckboxItem(
                            label = "App Brand Icon",
                            checked = config.showAppMark,
                            onCheckedChange = { updateConfig(config.copy(showAppMark = it)) },
                            modifier = Modifier.weight(1f)
                        )
                        ComposerCheckboxItem(
                            label = "Vehicle Name",
                            checked = config.showVehicleName,
                            onCheckedChange = { updateConfig(config.copy(showVehicleName = it)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        ComposerCheckboxItem(
                            label = "Vehicle Specs",
                            checked = config.showVehicleSpecs,
                            onCheckedChange = { updateConfig(config.copy(showVehicleSpecs = it)) },
                            modifier = Modifier.weight(1f)
                        )
                        ComposerCheckboxItem(
                            label = "Date",
                            checked = config.showDate,
                            onCheckedChange = { updateConfig(config.copy(showDate = it)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        ComposerCheckboxItem(
                            label = "Time",
                            checked = config.showTime,
                            onCheckedChange = { updateConfig(config.copy(showTime = it)) },
                            modifier = Modifier.weight(1f)
                        )
                        ComposerCheckboxItem(
                            label = "Route Path",
                            checked = config.showRouteLine,
                            onCheckedChange = { updateConfig(config.copy(showRouteLine = it)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        ComposerCheckboxItem(
                            label = "Surrounding Roads",
                            checked = config.showSurroundingRoads,
                            onCheckedChange = { updateConfig(config.copy(showSurroundingRoads = it)) },
                            modifier = Modifier.weight(1f)
                        )
                        ComposerCheckboxItem(
                            label = "Start / End Dots",
                            checked = config.showStartEndMarkers,
                            onCheckedChange = { updateConfig(config.copy(showStartEndMarkers = it)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        ComposerCheckboxItem(
                            label = "Metrics Backing",
                            checked = config.showMetricsPanelBg,
                            onCheckedChange = { updateConfig(config.copy(showMetricsPanelBg = it)) },
                            modifier = Modifier.weight(1f)
                        )
                        ComposerCheckboxItem(
                            label = "Telemetry Metrics",
                            checked = config.showMetrics,
                            onCheckedChange = { updateConfig(config.copy(showMetrics = it)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (config.showSurroundingRoads) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(TorqueEvoColors.CardSurface)
                            .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Surrounding Road Opacity",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TorqueEvoColors.TextPrimary
                            )
                            Text(
                                "${roadOpacityDraft.roundToInt()}%",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = TorqueEvoColors.ElectricBlue
                            )
                        }
                        Slider(
                            value = roadOpacityDraft,
                            onValueChange = { roadOpacityDraft = it },
                            onValueChangeFinished = {
                                val snapped = roadOpacityDraft.roundToInt().coerceIn(10, 60)
                                updateConfig(config.copy(surroundingRoadsOpacityPct = snapped))
                            },
                            valueRange = 10f..60f,
                            steps = 9,
                            colors = SliderDefaults.colors(
                                thumbColor = TorqueEvoColors.ElectricBlue,
                                activeTrackColor = TorqueEvoColors.ElectricBlue,
                                inactiveTrackColor = TorqueEvoColors.SubtleBorder
                            )
                        )
                        ComposerCheckboxItem(
                            label = "Main Roads Only",
                            checked = config.surroundingRoadsMainOnly,
                            onCheckedChange = { updateConfig(config.copy(surroundingRoadsMainOnly = it)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (config.showMetrics) {
                    ShareCardMetricPickerSection(
                        selectedMetrics = config.selectedMetrics,
                        onToggleMetric = { metric ->
                            val cur = config.selectedMetrics
                            val next = if (metric in cur) {
                                if (cur.size > 1) cur - metric else cur
                            } else {
                                if (cur.size < ShareCardMetric.MAX_SELECTED) cur + metric else cur
                            }
                            updateConfig(config.copy(selectedMetrics = next))
                        },
                        onResetDefault = {
                            updateConfig(config.copy(selectedMetrics = ShareCardMetric.DEFAULT_SELECTION))
                        }
                    )
                }
            }
        }

        BoxWithConstraints(modifier = Modifier.weight(1f, fill = false).fillMaxWidth()) {
            val isWide = maxWidth > 480.dp
            if (isWide) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    previewCard(Modifier.widthIn(min = 126.dp, max = 220.dp))
                    controlsBody(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        previewCard(Modifier.widthIn(min = 126.dp, max = 236.dp))
                    }
                    HorizontalDivider(color = TorqueEvoColors.SubtleBorder)
                    controlsBody(Modifier.weight(1f, fill = true).fillMaxWidth())
                }
            }
        }

        HorizontalDivider(color = TorqueEvoColors.SubtleBorder)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                enabled = !isExporting,
                onClick = onDismiss,
                modifier = Modifier.weight(1f)
            ) {
                Text("Cancel")
            }
            Button(
                enabled = !isExporting,
                onClick = { showExportChooser = true },
                colors = ButtonDefaults.buttonColors(containerColor = TorqueEvoColors.ElectricBlue, contentColor = Color.Black),
                modifier = Modifier.weight(1.4f)
            ) {
                if (isExporting) {
                    CircularProgressIndicator(
                        color = Color.Black,
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Export", fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable
private fun ComposerCheckboxItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = TorqueEvoColors.ElectricBlue,
                checkmarkColor = Color.Black,
                uncheckedColor = TorqueEvoColors.TextSecondary
            ),
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = 11.5.sp,
            color = if (checked) TorqueEvoColors.TextPrimary else TorqueEvoColors.TextSecondary,
            maxLines = 1
        )
    }
}

@Composable
fun SaveTripSummaryDialog(
    session: TripSessionEntity,
    vehicleName: String,
    unitPrefs: UnitPreferences,
    fuelType: FuelType,
    onSave: () -> Unit,
    onSaveAndShareCard: (() -> Unit)? = null,
    onDiscard: () -> Unit
) {
    val fmt = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }
    val (distVal, distUnit) = UnitConverter.convertDistanceKm(session.distanceKm, unitPrefs)
    val (kplVal, kplUnit) = UnitConverter.convertFuelEconomy(session.avgKpl, unitPrefs, fuelType)
    val (fuelVal, fuelUnit) = UnitConverter.convertVolume(session.fuelUsedLiters, unitPrefs, fuelType)
    val (avgSpdVal, spdUnit) = UnitConverter.convert(session.effectiveAvgSpeedKmh(), SensorQuantity.SPEED, unitPrefs, fuelType)
    val (maxSpdVal, _) = UnitConverter.convert(session.maxSpeedKmh, SensorQuantity.SPEED, unitPrefs, fuelType)
    val durationSec = session.effectiveDurationSec()

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        ConfigureEdgeToEdgeDialogWindow()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.union(WindowInsets.mandatorySystemGestures.only(WindowInsetsSides.Bottom))
                ),
            contentAlignment = Alignment.Center
        ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 540.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(TorqueEvoColors.ElevatedDark)
                .border(1.dp, TorqueEvoColors.ElectricBlue.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Save Recorded Trip Log?", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TorqueEvoColors.TextPrimary)
            Text(
                "$vehicleName · ${fmt.format(Date(session.startedAtMs))} · ${session.sampleCount} telemetry pts",
                fontSize = 11.5.sp,
                color = TorqueEvoColors.TextSecondary
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TripSummaryMetricBox("TRIP RANGE", "%.2f %s".format(distVal, distUnit), TorqueEvoColors.ElectricBlue, Modifier.weight(1f))
                TripSummaryMetricBox("TRIP TIME", TripRunCalculator.formatDurationClock(durationSec), TorqueEvoColors.TextPrimary, Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TripSummaryMetricBox("KPL · TRIP", if (session.avgKpl > 0.05) "%.1f %s".format(kplVal, kplUnit) else "—", TorqueEvoColors.NominalGreen, Modifier.weight(1f))
                TripSummaryMetricBox("TRIP FUEL USED", "%.2f %s".format(fuelVal, fuelUnit), TorqueEvoColors.SkyBlue, Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TripSummaryMetricBox("TRIP AVG SPEED", "%.0f %s".format(avgSpdVal, spdUnit), TorqueEvoColors.AccentViolet, Modifier.weight(1f))
                TripSummaryMetricBox("PEAKS", "%.0f %s · %.0f rpm".format(maxSpdVal, spdUnit, session.peakRpm), TorqueEvoColors.WarnAmber, Modifier.weight(1f))
            }

            Text(
                "Store all recorded metrics, route coordinates, and OBD telemetry logs to local device storage?",
                fontSize = 11.5.sp,
                color = TorqueEvoColors.TextSecondary
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onDiscard,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TorqueEvoColors.CriticalRed),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Discard Trip", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onSave,
                    colors = ButtonDefaults.buttonColors(containerColor = TorqueEvoColors.ElectricBlue, contentColor = Color.Black),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save Trip Log", fontWeight = FontWeight.ExtraBold)
                }
            }
            if (onSaveAndShareCard != null) {
                OutlinedButton(
                    onClick = onSaveAndShareCard,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save & Create Card", fontWeight = FontWeight.Bold, color = TorqueEvoColors.SkyBlue)
                }
            }
        }
        }
    }
}

@Composable
private fun TripSummaryMetricBox(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(TorqueEvoColors.CardSurface)
            .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(9.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TorqueEvoColors.TextSecondary)
        Text(
            value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> UnitChipRow(
    title: String,
    items: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit
) {
    Column {
        Text(title, fontSize = 11.sp, color = TorqueEvoColors.TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEach { item ->
                ApexFilterChip(selected = item == selected, onClick = { onSelect(item) }, label = label(item), compact = true)
            }
        }
    }
}
