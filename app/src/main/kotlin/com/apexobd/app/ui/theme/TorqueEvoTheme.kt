package com.apexobd.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object TorqueEvoColors {
    val PureBlack = Color(0xFF000000)
    val ElevatedDark = Color(0xFF0A0E17)
    val ElectricBlue = Color(0xFF00A8FF)
    val SkyBlue = Color(0xFF38BDF8)
    val DimBlue = Color(0x2800A8FF)
    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF8B98AB)
    val NominalGreen = Color(0xFF22C55E)
    val WarnAmber = Color(0xFFF59E0B)
    val CriticalRed = Color(0xFFEF4444)
}

private val TorqueEvoColorScheme = darkColorScheme(
    primary = TorqueEvoColors.ElectricBlue,
    secondary = TorqueEvoColors.SkyBlue,
    background = TorqueEvoColors.PureBlack,
    surface = TorqueEvoColors.ElevatedDark,
    onPrimary = TorqueEvoColors.PureBlack,
    onBackground = TorqueEvoColors.TextPrimary,
    onSurface = TorqueEvoColors.TextPrimary,
    error = TorqueEvoColors.CriticalRed
)

val TabularHeroStyle = TextStyle(
    fontWeight = FontWeight.Bold,
    fontSize = 26.sp,
    fontFeatureSettings = "tnum",
    color = TorqueEvoColors.TextPrimary
)

@Composable
fun TorqueEvoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TorqueEvoColorScheme,
        content = content
    )
}
