package io.carmo.airplay.receiver

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class StreamOverlayUiState(
    val receiverName: String = "Receiver",
    val status: String = "Streaming",
    val resolution: String = "",
    val frameRate: String = "",
    val quality: String = "",
    val screenFit: String = "",
    val audioRoute: String = "",
    val audioSync: String = "",
    val trafficVisible: Boolean = false,
    val appTheme: String = ReceiverPreferences.APP_THEME_MIDNIGHT,
    val autoAdjustment: String = "",
    val selectedAction: StreamAction = StreamAction.STOP
)

enum class StreamAction {
    STOP,
    SCREEN_FIT,
    AUDIO_SYNC,
    SETTINGS,
    DIAGNOSTICS,
    TRAFFIC
}

@Composable
fun StreamOverlay(
    state: StreamOverlayUiState
) {
    val colors = AppVisualTheme.colors(state.appTheme)
    MaterialTheme {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                color = colors.surface,
                shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
                border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 18.dp)
                ) {
                    Text(
                        text = state.receiverName,
                        color = colors.onSurface,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        text = state.status,
                        color = colors.mutedText,
                        fontSize = 17.sp,
                        modifier = Modifier.padding(top = 4.dp),
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = listOf(
                            state.resolution,
                            state.frameRate,
                            state.quality,
                            state.screenFit
                        ).filter { it.isNotBlank() }.joinToString("  |  "),
                        color = colors.subtleText,
                        fontSize = 15.sp,
                        maxLines = 1
                    )
                    Text(
                        text = listOf(
                            state.audioRoute,
                            state.audioSync,
                            if (state.trafficVisible) {
                                stringResource(R.string.stream_overlay_traffic_visible)
                            } else {
                                stringResource(R.string.stream_overlay_traffic_hidden)
                            }
                        ).filter { it.isNotBlank() }.joinToString("  |  "),
                        color = colors.subtleText,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(top = 4.dp),
                        maxLines = 1
                    )
                    if (state.autoAdjustment.isNotBlank()) {
                        Text(
                            text = state.autoAdjustment,
                            color = colors.primary,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(top = 6.dp),
                            maxLines = 1
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .widthIn(max = 904.dp)
                    ) {
                        StreamActionButton(
                            text = stringResource(R.string.stream_action_stop),
                            selected = state.selectedAction == StreamAction.STOP,
                            colors = colors
                        )
                        StreamActionButton(
                            text = stringResource(R.string.stream_action_fit_short),
                            selected = state.selectedAction == StreamAction.SCREEN_FIT,
                            colors = colors
                        )
                        StreamActionButton(
                            text = stringResource(R.string.stream_action_sync_short),
                            selected = state.selectedAction == StreamAction.AUDIO_SYNC,
                            colors = colors
                        )
                        StreamActionButton(
                            text = stringResource(R.string.settings_button),
                            selected = state.selectedAction == StreamAction.SETTINGS,
                            colors = colors,
                            wide = true
                        )
                        StreamActionButton(
                            text = stringResource(R.string.stream_action_diagnostics),
                            selected = state.selectedAction == StreamAction.DIAGNOSTICS,
                            colors = colors,
                            wide = true
                        )
                        StreamActionButton(
                            text = stringResource(R.string.stream_action_traffic),
                            selected = state.selectedAction == StreamAction.TRAFFIC,
                            colors = colors,
                            wide = true
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamActionButton(
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
            .width(if (wide) 132.dp else 92.dp)
            .height(52.dp)
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
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
