package io.github.devasenan134.isaipetti.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.devasenan134.isaipetti.R
import io.github.devasenan134.isaipetti.data.DeviceDto
import io.github.devasenan134.isaipetti.ui.components.LocalApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePickerSheet(onDismiss: () -> Unit) {
    val app = LocalApp.current
    val devices by app.social.devices.collectAsStateWithLifecycle()
    val activeDeviceId by app.social.activeDeviceId.collectAsStateWithLifecycle()
    val nowPlaying by app.player.nowPlaying.collectAsStateWithLifecycle()
    val myDeviceId = app.social.myDeviceId
    val isLocalPlaying = nowPlaying.isPlaying

    // Determine the playing device or active device
    val playingDevice = devices.firstOrNull { if (it.id == myDeviceId) isLocalPlaying else it.playing }
    val effectiveActiveId = playingDevice?.id ?: activeDeviceId ?: if (isLocalPlaying) myDeviceId else null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_devices),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Connect to a device",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Play and control music across your devices",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (devices.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_devices),
                        contentDescription = null,
                        modifier = Modifier
                            .size(56.dp)
                            .padding(bottom = 12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    Text(
                        text = "No other devices connected right now.",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Open Jukebox on your laptop, browser, or another device to listen together.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(devices, key = { it.id }) { device ->
                        DeviceCard(
                            device = device,
                            isCurrent = device.id == myDeviceId,
                            isActive = device.id == effectiveActiveId,
                            isDevicePlaying = if (device.id == myDeviceId) isLocalPlaying else device.playing,
                            onTransfer = { app.social.transferPlayback(device.id) },
                            onRemoteCommand = { action -> app.social.sendRemoteCommand(device.id, action) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: DeviceDto,
    isCurrent: Boolean,
    isActive: Boolean,
    isDevicePlaying: Boolean,
    onTransfer: () -> Unit,
    onRemoteCommand: (String) -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val iconRes = when (device.type) {
        "android" -> R.drawable.ic_smartphone
        "web" -> R.drawable.ic_laptop
        "desktop" -> R.drawable.ic_desktop
        else -> R.drawable.ic_devices
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = !isActive) { onTransfer() },
        shape = RoundedCornerShape(16.dp),
        color = if (isActive) primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = if (isActive) BorderStroke(1.5.dp, primary) else null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = if (isActive) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )

            Spacer(Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isCurrent) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "(This phone)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(2.dp))

                Text(
                    text = when {
                        isDevicePlaying && device.song != null -> "Playing · ${device.song.title}"
                        isDevicePlaying -> "Playing"
                        isActive -> if (isCurrent) "Listening on this phone" else "Active"
                        else -> "Connected"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isActive) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!isCurrent && isActive) {
                    IconButton(
                        onClick = { onRemoteCommand(if (isDevicePlaying) "pause" else "play") },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            painter = painterResource(if (isDevicePlaying) R.drawable.ic_pause else R.drawable.ic_play),
                            contentDescription = if (isDevicePlaying) "Pause" else "Play",
                            tint = primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = { onRemoteCommand("next") },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_skip_next),
                            contentDescription = "Next song",
                            tint = primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                if (isActive) {
                    FilledTonalButton(
                        onClick = {},
                        enabled = false,
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            disabledContainerColor = primary.copy(alpha = 0.15f),
                            disabledContentColor = primary,
                        ),
                    ) {
                        Text(
                            text = if (isCurrent) "Listening here" else "Active",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                } else {
                    Button(
                        onClick = onTransfer,
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text(
                            text = if (isCurrent) "Play here" else "Play on device",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
