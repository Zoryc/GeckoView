package app.snapcast.ui.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.snapcast.ui.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val PULSE_COUNT = 3

/**
 * Floating "videos on this page" button with a count badge. Hidden while [visible] is false (scale/fade);
 * every emission of [pulses] plays a short attention animation (scale bounce + expanding glow ring, 3x).
 * Pulses are collected even while hidden so the first video's pulse plays as the button appears.
 */
@Composable
fun VideoFab(
    count: Int,
    pulses: Flow<Any>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = count > 0,
) {
    val scale = remember { Animatable(1f) }
    val ring = remember { Animatable(0f) }
    LaunchedEffect(pulses) {
        pulses.collectLatest {
            repeat(PULSE_COUNT) {
                coroutineScope {
                    launch {
                        ring.snapTo(0f)
                        ring.animateTo(1f, tween(durationMillis = 700, easing = LinearOutSlowInEasing))
                        ring.snapTo(0f)
                    }
                    scale.animateTo(1.16f, tween(durationMillis = 170, easing = FastOutSlowInEasing))
                    scale.animateTo(
                        1f,
                        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                    )
                }
            }
        }
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = scaleIn(initialScale = 0.4f) + fadeIn(),
        exit = scaleOut(targetScale = 0.4f) + fadeOut(),
    ) {
        val ringColor = MaterialTheme.colorScheme.primary
        val description = stringResource(R.string.ui_video_fab_description, count)
        BadgedBox(
            badge = {
                Badge(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                ) {
                    Text(if (count > 99) "99+" else count.toString())
                }
            },
        ) {
            FloatingActionButton(
                onClick = onClick,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .semantics { contentDescription = description }
                    .drawBehind {
                        val progress = ring.value
                        if (progress > 0f && progress < 1f) {
                            val radius = size.minDimension / 2f * (1f + progress * 0.9f)
                            val alpha = (1f - progress)
                            drawCircle(color = ringColor.copy(alpha = alpha * 0.25f), radius = radius)
                            drawCircle(
                                color = ringColor.copy(alpha = alpha * 0.7f),
                                radius = radius,
                                style = Stroke(width = 3.dp.toPx()),
                            )
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                    },
            ) {
                Icon(imageVector = Icons.Filled.SmartDisplay, contentDescription = null)
            }
        }
    }
}

/**
 * One-time onboarding tip shown above the video button the first time it appears. The pointer at the
 * bottom-end points down at the button.
 */
@Composable
fun VideoFabTip(visible: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + scaleIn(initialScale = 0.8f, transformOrigin = TransformOrigin(1f, 1f)),
        exit = fadeOut() + scaleOut(targetScale = 0.8f, transformOrigin = TransformOrigin(1f, 1f)),
    ) {
        val container = MaterialTheme.colorScheme.inverseSurface
        Column(horizontalAlignment = Alignment.End) {
            Surface(
                color = container,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = MaterialTheme.shapes.medium,
                shadowElevation = 6.dp,
                modifier = Modifier.widthIn(max = 280.dp),
            ) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Filled.TipsAndUpdates,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.inversePrimary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.ui_video_tip),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    Row(modifier = Modifier.align(Alignment.End), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.ui_video_tip_got_it), color = MaterialTheme.colorScheme.inversePrimary)
                        }
                    }
                }
            }
            // Pointer centered over the FAB (56dp wide, so 28dp from the end).
            Canvas(
                modifier = Modifier
                    .padding(end = 20.dp)
                    .size(width = 16.dp, height = 8.dp),
            ) {
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width / 2f, size.height)
                    close()
                }
                drawPath(path, color = container)
            }
        }
    }
}
