package io.carmo.airplay.receiver

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class QuickSettingsUiState(
    val receiverName: String = "Receiver",
    val quality: String = "",
    val screenFit: String = "",
    val audioSync: String = "",
    val audioRoute: String = "",
    val security: String = "",
    val preset: String = "",
    val appTheme: String = ReceiverPreferences.APP_THEME_MIDNIGHT,
    val selectedAction: QuickSettingAction = QuickSettingAction.QUALITY
)

enum class QuickSettingAction {
    QUALITY,
    PRESET,
    SCREEN_FIT,
    AUDIO_SYNC,
    SECURITY,
    RESTART_DISCOVERY,
    SETTINGS
}

@Composable
fun QuickSettingsOverlay(state: QuickSettingsUiState) {
    val colors = AppVisualTheme.colors(state.appTheme)
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 56.dp, vertical = 44.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = colors.surface,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.25f)),
                modifier = Modifier.widthIn(min = 620.dp, max = 980.dp)
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = state.receiverName,
                        color = colors.onSurface,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Quick Settings",
                        color = colors.subtleText,
                        fontSize = 18.sp,
                        modifier = Modifier.padding(top = 4.dp),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(20.dp))
                    QuickValue("Quality", state.quality, colors)
                    QuickValue("Screen fit", state.screenFit, colors)
                    QuickValue("Audio sync", state.audioSync, colors)
                    QuickValue("Audio output", state.audioRoute, colors)
                    QuickValue("Security", state.security, colors)
                    if (state.preset.isNotBlank()) {
                        QuickValue("Room preset", state.preset, colors)
                    }

                    Spacer(modifier = Modifier.height(22.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        QuickButton("Quality", state.selectedAction == QuickSettingAction.QUALITY, colors)
                        if (state.preset.isNotBlank()) {
                            QuickButton("Preset", state.selectedAction == QuickSettingAction.PRESET, colors)
                        }
                        QuickButton("Fit", state.selectedAction == QuickSettingAction.SCREEN_FIT, colors)
                        QuickButton("Sync", state.selectedAction == QuickSettingAction.AUDIO_SYNC, colors)
                        QuickButton("Security", state.selectedAction == QuickSettingAction.SECURITY, colors, wide = true)
                        QuickButton("Restart", state.selectedAction == QuickSettingAction.RESTART_DISCOVERY, colors, wide = true)
                        QuickButton("Settings", state.selectedAction == QuickSettingAction.SETTINGS, colors, wide = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickValue(label: String, value: String, colors: AppThemeColors) {
    Row(
        modifier = Modifier
            .widthIn(min = 520.dp, max = 780.dp)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = colors.subtleText,
            fontSize = 17.sp,
            modifier = Modifier.width(170.dp)
        )
        Text(
            text = value,
            color = colors.onSurface,
            fontSize = 17.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = 540.dp)
        )
    }
}

@Composable
private fun QuickButton(
    text: String,
    selected: Boolean,
    colors: AppThemeColors,
    wide: Boolean = false
) {
    Button(
        onClick = {},
        colors = ButtonDefaults.buttonColors(
            backgroundColor = if (selected) colors.selectedButton else colors.button,
            contentColor = colors.onSurface
        ),
        modifier = Modifier
            .width(if (wide) 118.dp else 96.dp)
            .height(50.dp)
            .border(
                width = if (selected) 3.dp else 0.dp,
                color = if (selected) colors.primary else Color.Transparent,
                shape = RoundedCornerShape(5.dp)
            )
    ) {
        Text(
            text = text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}
