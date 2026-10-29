package com.simpletavern.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Card-table palette. The table follows the system theme; the cards themselves are always ivory paper. */
@Immutable
data class TablePalette(
    val tableCenter: Color,
    val tableMid: Color,
    val tableEdge: Color,
    val text: Color,
    val textSoft: Color,
    val paper: Color = Color(0xFFF6EEDB),
    val ink: Color = Color(0xFF2A1D14),
    val gold: Color = Color(0xFFC9A24B),
    val goldDeep: Color = Color(0xFF8A6A22),
    val red: Color = Color(0xFFA3202C),
    val cloth: Color = Color(0xFF5B1722),
) {
    val table: Brush
        get() = Brush.radialGradient(0f to tableCenter, 0.68f to tableMid, 1f to tableEdge)
}

val LightTable = TablePalette(
    tableCenter = Color(0xFFEEE4CB),
    tableMid = Color(0xFFDCCDAA),
    tableEdge = Color(0xFFCBB98F),
    text = Color(0xFF2A1D14),
    textSoft = Color(0xFF7A6544),
)

val DarkTable = TablePalette(
    tableCenter = Color(0xFF3D131B),
    tableMid = Color(0xFF1C0A0E),
    tableEdge = Color(0xFF0B0506),
    text = Color(0xFFECDCB8),
    textSoft = Color(0xFFA8905C),
)

/** Gold foil used on card borders and tabs. */
val Foil: Brush = Brush.linearGradient(
    0f to Color(0xFF8A6A22),
    0.30f to Color(0xFFF1D27A),
    0.55f to Color(0xFFB8902F),
    0.75f to Color(0xFFF6E3A1),
    1f to Color(0xFF8A6A22),
)

val Serif: FontFamily = FontFamily.Serif

object Type {
    val title = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = 0.1.em)
    val sub = TextStyle(fontFamily = Serif, fontStyle = FontStyle.Italic, fontSize = 13.sp)
    val ornament = TextStyle(fontFamily = Serif, fontSize = 10.sp, letterSpacing = 0.3.em)
    val who = TextStyle(fontFamily = Serif, fontStyle = FontStyle.Italic, fontSize = 13.sp, letterSpacing = 0.04.em)
    val body = TextStyle(fontFamily = Serif, fontSize = 16.5.sp, lineHeight = 1.7.em)
    val bodyLarge = TextStyle(fontFamily = Serif, fontSize = 20.sp, lineHeight = 1.9.em)
    val index = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    val pill = TextStyle(fontFamily = Serif, fontStyle = FontStyle.Italic, fontSize = 14.sp)
    val tab = TextStyle(fontFamily = Serif, fontSize = 16.sp)
}

val LocalTable = staticCompositionLocalOf { LightTable }

@Composable
fun TavernTheme(content: @Composable () -> Unit) {
    val palette = if (isSystemInDarkTheme()) DarkTable else LightTable
    CompositionLocalProvider(LocalTable provides palette, content = content)
}
