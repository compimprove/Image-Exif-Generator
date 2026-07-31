package org.compi.image_exif_reset

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.compi.image_exif_reset.model.ImageTask
import org.compi.image_exif_reset.model.TaskStatus
import org.compi.image_exif_reset.ui.imageFileDropTarget

private val AppBackground = Color(0xFFF5F4F0)
private val Ink = Color(0xFF20211F)
private val MutedInk = Color(0xFF6F716C)
private val Accent = Color(0xFF3A6755)
private val AccentSoft = Color(0xFFDDE9E2)

@Composable
@Preview
fun App(
    tasks: List<ImageTask> = emptyList(),
    onFilesDropped: (List<String>) -> Unit = {},
) {
    var isDragActive by remember { mutableStateOf(false) }
    val completedCount = tasks.count { it.status == TaskStatus.COMPLETED }
    val isWorking = tasks.any { it.status == TaskStatus.QUEUED || it.status == TaskStatus.PROCESSING }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = AppBackground,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imageFileDropTarget(
                        onDropped = onFilesDropped,
                        onActiveChanged = { isDragActive = it },
                    )
                    .padding(32.dp),
            ) {
                Text(
                    text = "Image EXIF Reset",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                )
                Text(
                    text = "Drop images once. Clean copies appear beside the originals.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedInk,
                )

                Spacer(Modifier.height(24.dp))

                DropZone(
                    active = isDragActive,
                    compact = tasks.isNotEmpty(),
                )

                AnimatedVisibility(tasks.isNotEmpty()) {
                    Column {
                        Spacer(Modifier.height(24.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (isWorking) "Resetting images…" else "$completedCount new images created",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Ink,
                            )
                            Text(
                                text = "${tasks.size} ${if (tasks.size == 1) "file" else "files"}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MutedInk,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(tasks, key = { it.id }) { task ->
                                TaskRow(task)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DropZone(active: Boolean, compact: Boolean) {
    val borderColor = if (active) Accent else Color(0xFFC8CAC4)
    val background = if (active) AccentSoft else Color.White.copy(alpha = 0.72f)
    val height = if (compact) 132.dp else 260.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(background, RoundedCornerShape(20.dp))
            .border(2.dp, borderColor, RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (active) "Release to reset" else "Drop JPEG, PNG, or WebP images",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = if (active) Accent else Ink,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Processing starts automatically",
                style = MaterialTheme.typography.bodyMedium,
                color = MutedInk,
            )
        }
    }
}

@Composable
private fun TaskRow(task: ImageTask) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusMark(task.status)
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.inputName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = task.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (task.status == TaskStatus.FAILED) MaterialTheme.colorScheme.error else MutedInk,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StatusMark(status: TaskStatus) {
    when (status) {
        TaskStatus.QUEUED -> Text("•", color = MutedInk, style = MaterialTheme.typography.headlineSmall)
        TaskStatus.PROCESSING -> CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = Accent,
        )
        TaskStatus.COMPLETED -> Text("✓", color = Accent, fontWeight = FontWeight.Bold)
        TaskStatus.SKIPPED -> Text("–", color = MutedInk, fontWeight = FontWeight.Bold)
        TaskStatus.FAILED -> Text("×", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
    }
}
