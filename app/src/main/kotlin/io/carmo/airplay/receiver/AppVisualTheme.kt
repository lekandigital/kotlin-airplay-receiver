package io.carmo.airplay.receiver

import androidx.compose.ui.graphics.Color

data class AppThemeColors(
    val background: Color,
    val surface: Color,
    val primary: Color,
    val secondary: Color,
    val onBackground: Color,
    val onSurface: Color,
    val mutedText: Color,
    val subtleText: Color,
    val button: Color,
    val selectedButton: Color
)

object AppVisualTheme {
    fun colors(theme: String): AppThemeColors {
        return when (theme) {
            ReceiverPreferences.APP_THEME_WARM -> AppThemeColors(
                background = Color(0xFF160F0A),
                surface = Color(0xF21B120C),
                primary = Color(0xFFFFB15C),
                secondary = Color(0xFFE58A3A),
                onBackground = Color(0xFFFFF7EE),
                onSurface = Color(0xFFFFF3E3),
                mutedText = Color(0xCCFFE4C2),
                subtleText = Color(0x99FFD19B),
                button = Color(0xFF5B4633),
                selectedButton = Color(0xFF80613F)
            )
            ReceiverPreferences.APP_THEME_LIGHT -> AppThemeColors(
                background = Color(0xFFF3F5F7),
                surface = Color(0xF2FFFFFF),
                primary = Color(0xFF0B6B57),
                secondary = Color(0xFF36677C),
                onBackground = Color(0xFF101820),
                onSurface = Color(0xFF17212B),
                mutedText = Color(0xCC263747),
                subtleText = Color(0x99324452),
                button = Color(0xFFE1E7EB),
                selectedButton = Color(0xFFC7D6DD)
            )
            else -> AppThemeColors(
                background = Color(0xFF061322),
                surface = Color(0xF0061322),
                primary = Color(0xFF23D18B),
                secondary = Color(0xFF72A7FF),
                onBackground = Color.White,
                onSurface = Color.White,
                mutedText = Color(0xCCFFFFFF),
                subtleText = Color(0x88FFFFFF),
                button = Color(0xFF46505A),
                selectedButton = Color(0xFF64717E)
            )
        }
    }
}
