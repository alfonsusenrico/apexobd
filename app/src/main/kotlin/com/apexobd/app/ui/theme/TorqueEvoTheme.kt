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
    val HomeBg = Color(0xFF07090D)
    val ElevatedDark = Color(0xFF0D1016)
    val CardSurface = Color(0xFF10141C)
    val CarProfileSurface = Color(0xFF121722)
    val SettingsSurface = Color(0xFF141923)
    val RefuelSurface = Color(0xFF0F1614)
    val MapFallbackBg = Color(0xFF070E17)
    val ElectricBlue = Color(0xFF00A8FF)
    val SkyBlue = Color(0xFF38BDF8)
    val DimBlue = Color(0x2800A8FF)
    val SubtleBorder = Color(0x1EFFFFFF)
    val TextPrimary = Color(0xFFF3F5F8)
    val TextSecondary = Color(0xFF7E8796)
    val NominalGreen = Color(0xFF22C55E)
    val WarnAmber = Color(0xFFF59E0B)
    val CriticalRed = Color(0xFFEF4444)
    val AccentViolet = Color(0xFFA78BFA)
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
    fontSize = 24.sp,
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
