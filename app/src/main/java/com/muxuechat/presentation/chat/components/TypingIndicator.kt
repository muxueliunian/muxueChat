package com.muxuechat.presentation.chat.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun TypingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")
    val alphas = listOf(0, 150, 300).map { delay ->
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700, delayMillis = delay, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "dot-$delay",
        )
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        alphas.forEach { anim ->
            val value by anim
            Dot(alpha = value)
        }
    }
}

@Composable
private fun Dot(alpha: Float) {
    Row(
        modifier = Modifier
            .size(6.dp)
            .clip(CircleShape)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.onSurfaceVariant),
    ) {}
}
