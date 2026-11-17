package com.apexobd.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apexobd.app.BuildConfig
import com.apexobd.app.data.db.RefuelLogEntity
import com.apexobd.app.data.db.TripSessionEntity
import com.apexobd.app.data.db.VehicleProfileEntity
import com.apexobd.app.data.prefs.HudGridScale
import com.apexobd.app.data.prefs.MapProvider
import com.apexobd.app.data.prefs.UserSettings
import com.apexobd.app.platform.GpsSnapshot
import com.apexobd.app.platform.OfflineMapManager
import com.apexobd.app.platform.OfflineRegionSummary
import com.apexobd.app.platform.PmtilesPackSummary
import com.apexobd.app.ui.components.DualMapCanvas
import com.apexobd.app.ui.components.OfflineFramePickerMap
import com.apexobd.app.ui.theme.TorqueEvoColors
import com.apexobd.core.model.AirflowUnit
import com.apexobd.core.model.FuelEconomyUnit
import com.apexobd.core.model.FuelType
import com.apexobd.core.model.PowerUnit
import com.apexobd.core.model.PressureUnit
import com.apexobd.core.model.SpeedUnit
import com.apexobd.core.model.TempUnit
import com.apexobd.core.model.TorqueUnit
import com.apexobd.core.model.UnitConverter
import com.apexobd.core.model.UnitPreferences
import com.apexobd.core.model.VolumeUnit
import com.apexobd.core.protocol.DiagnosticReport
import com.apexobd.core.protocol.ObdPidRegistry
import com.apexobd.core.scheduler.PollingProfile
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ApexFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    enabled: Boolean = true,
    compact: Boolean = false
) {
    FilterChip(
        selected = selected,
        enabled = enabled,
        onClick = onClick,
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VehicleProfileSheet(
    vehicles: List<VehicleProfileEntity>,
    activeVehicle: VehicleProfileEntity?,
    unitPrefs: UnitPreferences,
    onSelectVehicle: (Long) -> Unit,
    onSaveVehicle: (
        existing: VehicleProfileEntity?,
        name: String,
        displacement: Double,
        weightKg: Double,
        fuelType: FuelType,
        tankCapDisplay: Double,
        remDisplay: Double,
        odoDisplay: Double,
        redline: Int
    ) -> Unit,
    onDeleteVehicle: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var editingExisting by remember(activeVehicle?.id) { mutableStateOf(activeVehicle) }
    var confirmDelete by remember { mutableStateOf(false) }
    var name by remember(editingExisting?.id) { mutableStateOf(editingExisting?.name ?: "My Vehicle") }
    var disp by remember(editingExisting?.id) { mutableStateOf("%.1f".format(editingExisting?.displacementLiters ?: 1.5)) }
    var weight by remember(editingExisting?.id) { mutableStateOf("${(editingExisting?.weightKg ?: 1200.0).toInt()}") }
    var fuelType by remember(editingExisting?.id) {
        mutableStateOf(
            editingExisting?.let { runCatching { FuelType.valueOf(it.fuelType) }.getOrNull() } ?: FuelType.GASOLINE
        )
    }
    var redline by remember(editingExisting?.id) { mutableStateOf("${editingExisting?.redlineRpm ?: 7000}") }

    val volUnit = unitPrefs.volumeSymbol(fuelType)
    val distUnit = unitPrefs.distanceSymbol()

    val capDispInit = UnitConverter.convertVolume(editingExisting?.tankCapacityLiters ?: 45.0, unitPrefs, fuelType).first
    val remDispInit = UnitConverter.convertVolume(editingExisting?.estimatedRemainingFuelLiters ?: 45.0, unitPrefs, fuelType).first
    val odoDispInit = UnitConverter.convertDistanceKm(editingExisting?.allTimeOdometerKm ?: 0.0, unitPrefs).first

    var tankCap by remember(editingExisting?.id, fuelType, unitPrefs.volume) { mutableStateOf("%.1f".format(capDispInit)) }
    var remFuel by remember(editingExisting?.id, fuelType, unitPrefs.volume) { mutableStateOf("%.1f".format(remDispInit)) }
    var odometer by remember(editingExisting?.id, unitPrefs.speed) { mutableStateOf("%.1f".format(odoDispInit)) }

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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = TorqueEvoColors.ElevatedDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Vehicle Profile", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TorqueEvoColors.TextPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (editingExisting != null && vehicles.size > 1) {
                        TextButton(onClick = { confirmDelete = true }) {
                            Text("Delete", color = TorqueEvoColors.CriticalRed)
                        }
                    }
                    TextButton(onClick = {
                        editingExisting = null
                        name = "New Car"
                        disp = "1.5"
                        weight = "1250"
                        fuelType = FuelType.GASOLINE
                        tankCap = "45.0"
                        remFuel = "45.0"
                        odometer = "0.0"
                        redline = "6800"
                    }) {
                        Text("+ New Car", color = TorqueEvoColors.ElectricBlue)
                    }
                }
            }

            if (vehicles.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    vehicles.forEach { v ->
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

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Vehicle Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

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
                    value = disp,
                    onValueChange = { disp = it },
                    label = { Text("Engine (L)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = weight,
                    onValueChange = { weight = it },
                    label = { Text("Weight (kg)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = redline,
                    onValueChange = { redline = it },
                    label = { Text("Redline RPM") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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

            OutlinedTextField(
                value = odometer,
                onValueChange = { odometer = it },
                label = { Text("All-Time Odometer ($distUnit)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    onSaveVehicle(
                        editingExisting,
                        name,
                        disp.toDoubleOrNull() ?: 1.3,
                        weight.toDoubleOrNull() ?: 1175.0,
                        fuelType,
                        tankCap.toDoubleOrNull() ?: 45.0,
                        remFuel.toDoubleOrNull() ?: 45.0,
                        odometer.toDoubleOrNull() ?: 0.0,
                        redline.toIntOrNull() ?: 7000
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
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RefuelLogSheet(
    vehicle: VehicleProfileEntity?,
    logs: List<RefuelLogEntity>,
    defaultCurrency: String,
    unitPrefs: UnitPreferences,
    onAddRefuel: (volume: Double, price: Double, currency: String, ron: Int?, fuelName: String?, odo: Double) -> Unit,
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
    var ron by remember { mutableStateOf(if (fuelType == FuelType.GASOLINE) "95" else "") }
    var fuelName by remember { mutableStateOf("") }
    var odometer by remember { mutableStateOf("%.1f".format(initOdo)) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = TorqueEvoColors.ElevatedDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Refuel Checkpoint Log", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TorqueEvoColors.TextPrimary)
            Text(
                "Calculates fuel average strictly between consecutive refuel checkpoints without affecting OBD All-Time Avg or per-run tracking.",
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
                            odometer.toDoubleOrNull() ?: initOdo
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = TorqueEvoColors.ElevatedDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Fault Codes & Readiness (03/07/0A)", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TorqueEvoColors.TextPrimary, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onScan, enabled = !isScanning) {
                    Text(if (isScanning) "Scanning..." else "Rescan", fontSize = 12.sp)
                }
            }
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunLogsAndMapsSheet(
    sessions: List<TripSessionEntity>,
    unitPrefs: UnitPreferences,
    fuelType: FuelType,
    onExport: (TripSessionEntity, Boolean) -> Unit,
    onOpenOfflineMaps: () -> Unit,
    onDismiss: () -> Unit
) {
    val fmt = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = TorqueEvoColors.ElevatedDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ApexFilterChip(selected = true, onClick = {}, label = "Saved Runs (${sessions.size})")
                ApexFilterChip(selected = false, onClick = onOpenOfflineMaps, label = "Offline Maps (Free)")
            }

            if (sessions.isEmpty()) {
                Text("No saved runs yet. Start a run from the HUD Mini-Map or Run Logger.", color = TorqueEvoColors.TextSecondary, fontSize = 12.sp)
            } else {
                sessions.forEach { s ->
                    val (dVal, dSym) = UnitConverter.convertDistanceKm(s.distanceKm, unitPrefs)
                    val (kVal, kSym) = UnitConverter.convertFuelEconomy(s.avgKpl, unitPrefs, fuelType)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(TorqueEvoColors.CardSurface)
                            .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(fmt.format(Date(s.startedAtMs)), fontWeight = FontWeight.Bold, color = TorqueEvoColors.TextPrimary)
                            Text("%.1f %s · %.1f %s".format(dVal, dSym, kVal, kSym), color = TorqueEvoColors.ElectricBlue, fontSize = 12.sp)
                        }
                        Text(
                            "Max %.0f km/h · Peak %.0f RPM · %d pts".format(s.maxSpeedKmh, s.peakRpm, s.sampleCount),
                            fontSize = 11.sp,
                            color = TorqueEvoColors.TextSecondary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                            OutlinedButton(onClick = { onExport(s, false) }) { Text("CSV", fontSize = 11.sp) }
                            OutlinedButton(onClick = { onExport(s, true) }) { Text("JSON", fontSize = 11.sp) }
                        }
                    }
                }
            }
        }
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
    onDiscoverExplorer: () -> Unit,
    onStopExplorer: () -> Unit,
    onAddCustomPid: (name: String, hex: String, formula: String, unit: String) -> Unit,
    onImportCsv: (String) -> Unit,
    onRunDeepDump: () -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var hex by remember { mutableStateOf("") }
    var formula by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    var csvText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { onDiscoverExplorer() }

    ModalBottomSheet(
        onDismissRequest = {
            onStopExplorer()
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = TorqueEvoColors.ElevatedDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("ECU Supported PIDs (${supportedPids.size})", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TorqueEvoColors.TextPrimary)
                OutlinedButton(onClick = onRunDeepDump) { Text("Deep Scan", fontSize = 11.sp) }
            }
            explorerStatus?.let {
                Text(it, fontSize = 11.sp, color = TorqueEvoColors.SkyBlue)
            }

            ObdPidRegistry.standardPids
                .filter { supportedPids.isEmpty() || it.pidHex in supportedPids }
                .forEach { pid ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(TorqueEvoColors.CardSurface)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("01${pid.pidHex} · ${pid.name}", fontSize = 12.sp, color = TorqueEvoColors.TextPrimary)
                        val v = liveValues[pid.key]
                        Text(
                            if (v != null) "%.1f %s".format(v, pid.unit) else "—",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TorqueEvoColors.ElectricBlue
                        )
                    }
                }

            Spacer(Modifier.height(6.dp))
            Text("Add Custom / UDS Mode 21–22 PID", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TorqueEvoColors.TextPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = hex, onValueChange = { hex = it }, label = { Text("Mode/PID e.g. 221E1C") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = formula, onValueChange = { formula = it }, label = { Text("Formula e.g. (A*256+B)/10") }, singleLine = true, modifier = Modifier.weight(1.4f))
                OutlinedTextField(value = unit, onValueChange = { unit = it }, label = { Text("Unit") }, singleLine = true, modifier = Modifier.weight(0.6f))
            }
            Button(
                onClick = {
                    onAddCustomPid(name, hex, formula, unit)
                    name = ""; hex = ""; formula = ""; unit = ""
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
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(
    settings: UserSettings,
    onSaveConnection: (host: String, port: Int, useSim: Boolean, dualCell: Boolean) -> Unit,
    onSelectPolling: (PollingProfile) -> Unit,
    onSelectGridScale: (HudGridScale) -> Unit,
    onSelectMapProvider: (MapProvider) -> Unit,
    onUpdateUnits: (UnitPreferences) -> Unit,
    onUpdateCurrency: (String) -> Unit,
    onToggleHudMirror: () -> Unit,
    onOpenOfflineMaps: () -> Unit,
    onDismiss: () -> Unit
) {
    var host by remember(settings.wifiHost) { mutableStateOf(settings.wifiHost) }
    var port by remember(settings.wifiPort) { mutableStateOf("${settings.wifiPort}") }
    var useSim by remember(settings.useSimulator) { mutableStateOf(settings.useSimulator) }
    var dualCell by remember(settings.dualCellularKeepalive) { mutableStateOf(settings.dualCellularKeepalive) }
    var unitState by remember(settings) { mutableStateOf(settings.toUnitPreferences()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = TorqueEvoColors.ElevatedDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 26.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Adapter, Units, Maps & Polling", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TorqueEvoColors.TextPrimary)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Wi-Fi Host") }, singleLine = true, modifier = Modifier.weight(1.4f))
                OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(0.7f))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Demo OBD Simulator Mode", color = TorqueEvoColors.TextPrimary, fontSize = 13.sp)
                Switch(checked = useSim, onCheckedChange = { useSim = it })
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Keep Mobile Data Active With Wi-Fi OBD", color = TorqueEvoColors.TextPrimary, fontSize = 13.sp)
                    Text("Routes OBD over Wi-Fi while allowing Google Maps & apps via Cellular", fontSize = 10.sp, color = TorqueEvoColors.TextSecondary)
                }
                Switch(checked = dualCell, onCheckedChange = { dualCell = it })
            }
            Button(
                onClick = { onSaveConnection(host, port.toIntOrNull() ?: 35000, useSim, dualCell) },
                colors = ButtonDefaults.buttonColors(containerColor = TorqueEvoColors.ElectricBlue, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Apply Connection Settings", fontWeight = FontWeight.Bold) }

            Text("Map Provider", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.TextSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MapProvider.entries.forEach { mp ->
                    val enabled = mp != MapProvider.GOOGLE_MAPS || BuildConfig.MAPS_API_KEY.isNotBlank()
                    ApexFilterChip(
                        selected = settings.mapProvider == mp,
                        enabled = enabled,
                        onClick = { onSelectMapProvider(mp) },
                        label = if (mp == MapProvider.GOOGLE_MAPS && !enabled) "Google Maps (No Key)" else mp.label,
                        compact = true
                    )
                }
            }
            OutlinedButton(onClick = onOpenOfflineMaps, modifier = Modifier.fillMaxWidth()) {
                Text("Manage Offline Vector Map Regions (MapLibre)")
            }

            Text("Polling Efficiency Profile", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.TextSecondary)
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

            Text("HUD Canvas Grid Scale", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.TextSecondary)
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

            Text("Global Measurement Units", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.TextSecondary)
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
        }
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
