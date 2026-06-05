package io.carmo.airplay.receiver

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ReadyUiState(
    val title: String = "AirPlay Receiver",
    val deviceName: String = "Receiver",
    val status: String = "Ready to AirPlay",
    val connectionHint: String = "",
    val qualitySummary: String = "",
    val securitySummary: String = "",
    val settingsHint: String = "",
    val settingsButtonText: String = "Settings",
    val quickButtonText: String = "Quick",
    val helpButtonText: String = "Help",
    val selectedAction: ReadyAction = ReadyAction.SETTINGS,
    val clockText: String = "",
    val clockVisible: Boolean = false,
    val clockOffsetX: Float = 0f,
    val clockOffsetY: Float = 0f,
    val idleTheme: String = ReceiverPreferences.IDLE_THEME_CLOCK,
    val appTheme: String = ReceiverPreferences.APP_THEME_MIDNIGHT,
    val weatherStatus: String = ""
)

enum class ReadyAction {
    SETTINGS,
    QUICK,
    HELP
}

@Composable
fun ReadyOverlay(
    state: ReadyUiState
) {
    val colors = AppVisualTheme.colors(state.appTheme)
    val minimal = state.idleTheme == ReceiverPreferences.IDLE_THEME_MINIMAL
    val themeBackground = when (state.idleTheme) {
        ReceiverPreferences.IDLE_THEME_ART -> colors.background.copy(alpha = 0.96f)
        ReceiverPreferences.IDLE_THEME_PHOTOS -> Color(0xF0000000)
        ReceiverPreferences.IDLE_THEME_WEATHER -> colors.background.copy(alpha = 0.98f)
        else -> colors.background
    }
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(themeBackground)
                .padding(horizontal = 56.dp, vertical = 44.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = if (minimal) Color.Transparent else colors.surface,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, if (minimal) Color.Transparent else colors.primary.copy(alpha = 0.25f)),
                modifier = Modifier.widthIn(min = if (minimal) 240.dp else 520.dp, max = 920.dp)
            ) {
                Column(
                    modifier = Modifier.padding(
                        start = 34.dp,
                        top = if (minimal) 18.dp else 28.dp,
                        end = 34.dp,
                        bottom = if (minimal) 18.dp else 28.dp
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (state.clockVisible) {
                        Text(
                            text = state.clockText,
                            color = colors.onSurface,
                            fontFamily = FontFamily.Monospace,
                            fontSize = if (minimal) 24.sp else 48.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.graphicsLayer {
                                translationX = state.clockOffsetX
                                translationY = state.clockOffsetY
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Text(
                        text = if (minimal) "Ready" else state.title,
                        color = colors.onSurface,
                        fontSize = if (minimal) 18.sp else 34.sp,
                        fontWeight = FontWeight.Light,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = state.deviceName,
                        color = colors.mutedText,
                        fontSize = if (minimal) 14.sp else 24.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(top = if (minimal) 6.dp else 12.dp)
                            .widthIn(max = 760.dp)
                    )

                    Text(
                        text = state.status,
                        color = colors.subtleText,
                        fontSize = if (minimal) 13.sp else 18.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp)
                    )

                    if (!minimal) {
                        Text(
                            text = state.connectionHint,
                            color = colors.subtleText,
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .widthIn(max = 760.dp)
                        )
                    }

                    if (state.qualitySummary.isNotBlank() && !minimal) {
                        Text(
                            text = state.qualitySummary,
                            color = colors.subtleText,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    if (state.weatherStatus.isNotBlank() && !minimal) {
                        Text(
                            text = state.weatherStatus,
                            color = colors.subtleText,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }

                    if (state.securitySummary.isNotBlank() && !minimal) {
                        Text(
                            text = state.securitySummary,
                            color = colors.subtleText.copy(alpha = 0.75f),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }

                    if (!minimal) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.padding(top = 22.dp)
                        ) {
                            ReadyActionButton(
                                text = state.settingsButtonText,
                                selected = state.selectedAction == ReadyAction.SETTINGS,
                                colors = colors
                            )
                            ReadyActionButton(
                                text = state.quickButtonText,
                                selected = state.selectedAction == ReadyAction.QUICK,
                                colors = colors
                            )
                            ReadyActionButton(
                                text = state.helpButtonText,
                                selected = state.selectedAction == ReadyAction.HELP,
                                colors = colors
                            )
                        }
                    }

                    if (state.settingsHint.isNotBlank() && !minimal) {
                        Text(
                            text = state.settingsHint,
                            color = colors.subtleText.copy(alpha = 0.75f),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadyActionButton(
    text: String,
    selected: Boolean,
    colors: AppThemeColors,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(132.dp)
            .height(52.dp)
            .background(
                color = if (selected) colors.selectedButton else colors.button,
                shape = RoundedCornerShape(5.dp)
            )
            .border(
                width = if (selected) 3.dp else 0.dp,
                color = if (selected) colors.primary else Color.Transparent,
                shape = RoundedCornerShape(5.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = colors.onSurface,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
