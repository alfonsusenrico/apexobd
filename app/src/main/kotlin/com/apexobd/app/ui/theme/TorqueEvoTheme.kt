package com.apexobd.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.apexobd.app.data.prefs.HudCanvasBackdrop
import com.apexobd.app.data.prefs.HudCardStyle
import com.apexobd.app.data.prefs.HudThemePreset

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

@Immutable
data class HudVisualPalette(
    val preset: HudThemePreset,
    val backdrop: HudCanvasBackdrop,
    val cardStyle: HudCardStyle,
    val canvasTop: Color,
    val canvasBottom: Color,
    val canvasGlow: Color?,
    val cardFill: Color,
    val cardSheen: Color?,
    val cardBorder: Color,
    val accent: Color,
    val textPrimary: Color,
    val labelColor: Color,
    val textSecondary: Color,
    val dialTrack: Color,
    val showBorders: Boolean
)

/** Resolves full-canvas backdrop gradients, card surface translucency, borders, and gauge accents. */
fun resolveHudVisualPalette(
    preset: HudThemePreset = HudThemePreset.OLED_BLUE,
    backdrop: HudCanvasBackdrop = HudCanvasBackdrop.MATCH_THEME,
    cardStyle: HudCardStyle = HudCardStyle.THEMED,
    showBorders: Boolean = true
): HudVisualPalette {
    data class ThemeTokens(
        val bgTop: Color,
        val bgBottom: Color,
        val bgGlow: Color?,
        val cardFill: Color,
        val cardSheen: Color?,
        val cardBorder: Color,
        val accent: Color,
        val textPrimary: Color,
        val labelColor: Color,
        val textSecondary: Color,
        val dialTrack: Color
    )

    val base = when (preset) {
        HudThemePreset.OLED_BLUE -> ThemeTokens(
            bgTop = Color(0xFF000000),
            bgBottom = Color(0xFF000000),
            bgGlow = null,
            cardFill = Color(0x0BFFFFFF),
            cardSheen = null,
            cardBorder = Color(0x26FFFFFF),
            accent = Color(0xFF00A8FF),
            textPrimary = Color(0xFFF3F5F8),
            labelColor = Color(0xFFEDF2F8),
            textSecondary = Color(0xFFA0ABB8),
            dialTrack = Color(0x24FFFFFF)
        )
        HudThemePreset.GLASSY_DIMMED -> ThemeTokens(
            bgTop = Color(0xFF0F1726),
            bgBottom = Color(0xFF070B12),
            bgGlow = Color(0x2838BDF8),
            cardFill = Color(0x8E1D293D),
            cardSheen = Color(0x36FFFFFF),
            cardBorder = Color(0x36FFFFFF),
            accent = Color(0xFF38BDF8),
            textPrimary = Color(0xFFF8FAFC),
            labelColor = Color(0xFFF1F5F9),
            textSecondary = Color(0xFFAAB8CC),
            dialTrack = Color(0x33FFFFFF)
        )
        HudThemePreset.MOTORSPORT_CRIMSON -> ThemeTokens(
            bgTop = Color(0xFF18090C),
            bgBottom = Color(0xFF080405),
            bgGlow = Color(0x24EF4444),
            cardFill = Color(0xB8281216),
            cardSheen = Color(0x24FB7185),
            cardBorder = Color(0x4DEF4444),
            accent = Color(0xFFEF4444),
            textPrimary = Color(0xFFF9FAFB),
            labelColor = Color(0xFFFDE8EC),
            textSecondary = Color(0xFFCBB2B7),
            dialTrack = Color(0x28FFFFFF)
        )
        HudThemePreset.PIT_EMERALD -> ThemeTokens(
            bgTop = Color(0xFF081710),
            bgBottom = Color(0xFF040A07),
            bgGlow = Color(0x2222C55E),
            cardFill = Color(0xB80F261B),
            cardSheen = Color(0x2234D399),
            cardBorder = Color(0x4822C55E),
            accent = Color(0xFF22C55E),
            textPrimary = Color(0xFFF0FDF4),
            labelColor = Color(0xFFDCFCE7),
            textSecondary = Color(0xFFA3C7B3),
            dialTrack = Color(0x28FFFFFF)
        )
        HudThemePreset.CLUSTER_AMBER -> ThemeTokens(
            bgTop = Color(0xFF171007),
            bgBottom = Color(0xFF080502),
            bgGlow = Color(0x22F59E0B),
            cardFill = Color(0xBF261B0D),
            cardSheen = Color(0x20FBBF24),
            cardBorder = Color(0x48F59E0B),
            accent = Color(0xFFF59E0B),
            textPrimary = Color(0xFFFEF3C7),
            labelColor = Color(0xFFFEF3C7),
            textSecondary = Color(0xFFCBB492),
            dialTrack = Color(0x28FFFFFF)
        )
        HudThemePreset.CARBON_TITANIUM -> ThemeTokens(
            bgTop = Color(0xFF13171F),
            bgBottom = Color(0xFF08090C),
            bgGlow = Color(0x2094A3B8),
            cardFill = Color(0xAD1E2430),
            cardSheen = Color(0x2CFFFFFF),
            cardBorder = Color(0x3DCBD5E1),
            accent = Color(0xFFE2E8F0),
            textPrimary = Color(0xFFF8FAFC),
            labelColor = Color(0xFFF1F5F9),
            textSecondary = Color(0xFFA8B5C7),
            dialTrack = Color(0x2CFFFFFF)
        )
        HudThemePreset.NIGHT_VIOLET -> ThemeTokens(
            bgTop = Color(0xFF100E22),
            bgBottom = Color(0xFF05050C),
            bgGlow = Color(0x24A78BFA),
            cardFill = Color(0xB81B1836),
            cardSheen = Color(0x26C4B5FD),
            cardBorder = Color(0x48A78BFA),
            accent = Color(0xFFA78BFA),
            textPrimary = Color(0xFFF5F3FF),
            labelColor = Color(0xFFEDE9FE),
            textSecondary = Color(0xFFB7B0D8),
            dialTrack = Color(0x2BFFFFFF)
        )
    }

    val (resolvedTop, resolvedBottom, resolvedGlow) = when (backdrop) {
        HudCanvasBackdrop.MATCH_THEME -> Triple(base.bgTop, base.bgBottom, base.bgGlow)
        HudCanvasBackdrop.GLASSY_DIMMED -> Triple(Color(0xFF0F1726), Color(0xFF070B12), Color(0x2838BDF8))
        HudCanvasBackdrop.PURE_BLACK -> Triple(Color(0xFF000000), Color(0xFF000000), null)
    }

    val (resolvedFill, resolvedSheen, resolvedBorder) = if (cardStyle == HudCardStyle.CLEAR_GLASS) {
        Triple(Color(0x0DFFFFFF), Color(0x1CFFFFFF), Color(0x2EFFFFFF))
    } else {
        Triple(base.cardFill, base.cardSheen, base.cardBorder)
    }

    return HudVisualPalette(
        preset = preset,
        backdrop = backdrop,
        cardStyle = cardStyle,
        canvasTop = resolvedTop,
        canvasBottom = resolvedBottom,
        canvasGlow = resolvedGlow,
        cardFill = resolvedFill,
        cardSheen = resolvedSheen,
        cardBorder = resolvedBorder,
        accent = base.accent,
        textPrimary = base.textPrimary,
        labelColor = base.labelColor,
        textSecondary = base.textSecondary,
        dialTrack = base.dialTrack,
        showBorders = showBorders
    )
}

val LocalHudVisualStyle = compositionLocalOf { resolveHudVisualPalette() }

/** Paints the configured full-canvas background gradient and ambient glass glow onto a HUD surface. */
fun Modifier.hudCanvasBackdrop(palette: HudVisualPalette): Modifier = this.drawBehind {
    drawRect(
        brush = Brush.verticalGradient(listOf(palette.canvasTop, palette.canvasBottom))
    )
    palette.canvasGlow?.let { glow ->
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(glow, Color.Transparent),
                center = Offset(size.width * 0.72f, size.height * 0.28f),
                radius = (size.maxDimension * 0.72f).coerceAtLeast(1f)
            )
        )
    }
}

private val TorqueEvoColorScheme = darkColorScheme(
    primary = TorqueEvoColors.ElectricBlue,
    onPrimary = TorqueEvoColors.PureBlack,
    primaryContainer = TorqueEvoColors.DimBlue,
    onPrimaryContainer = TorqueEvoColors.ElectricBlue,
    secondary = TorqueEvoColors.SkyBlue,
    onSecondary = TorqueEvoColors.PureBlack,
    secondaryContainer = TorqueEvoColors.DimBlue,
    onSecondaryContainer = TorqueEvoColors.ElectricBlue,
    background = TorqueEvoColors.PureBlack,
    onBackground = TorqueEvoColors.TextPrimary,
    surface = TorqueEvoColors.ElevatedDark,
    onSurface = TorqueEvoColors.TextPrimary,
    surfaceVariant = TorqueEvoColors.CardSurface,
    onSurfaceVariant = TorqueEvoColors.TextSecondary,
    outline = Color(0x2EFFFFFF),
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
