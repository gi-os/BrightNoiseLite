package com.gios.brightnoiselite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** The tool's theme, following LightOS's light/dark setting. */
@Composable
fun Themed(content: @Composable () -> Unit) {
    val colors by LightThemeController.colors.collectAsState()
    LightTheme(colors = colors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            content()
        }
    }
}

/** A hairline rule. */
@Composable
fun Rule() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LightThemeTokens.colors.contentSecondary),
    )
}

/** A small heading over a group of controls. */
@Composable
fun SectionLabel(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Fine,
        lighten = true,
        modifier = Modifier.padding(top = 1f.gridUnitsAsDp(), bottom = 0.25f.gridUnitsAsDp()),
    )
}

/**
 * Full-width tappable row. Selection inverts the row rather than tinting it: on a greyscale
 * matte panel an inversion is the only state change that reads at arm's length in a dark room.
 */
@Composable
fun SoundRow(
    label: String,
    sub: String?,
    selected: Boolean,
    trailing: String?,
    onClick: () -> Unit,
) {
    val colors = LightThemeTokens.colors
    val fg = if (selected) colors.background else colors.content
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.content else colors.background)
            .lightClickable(onClick = onClick)
            .padding(horizontal = 0.5f.gridUnitsAsDp(), vertical = 0.4f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            LightText(
                text = label,
                variant = LightTextVariant.Copy,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sub != null) {
                LightText(
                    text = sub,
                    variant = LightTextVariant.Detail,
                    color = if (selected) colors.background else colors.contentSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            LightText(text = trailing, variant = LightTextVariant.Fine, color = fg)
        }
    }
}

/** Inverting chip, for the layer pickers and the timer presets. */
@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LightThemeTokens.colors
    Box(
        modifier = Modifier
            .background(if (selected) colors.content else colors.background)
            .lightClickable(onClick = onClick)
            .padding(horizontal = 0.4f.gridUnitsAsDp(), vertical = 0.3f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Fine,
            color = if (selected) colors.background else colors.content,
            maxLines = 1,
        )
    }
}

/**
 * Segmented level control, as in the full app. A slider thumb is a poor target on a 3.92"
 * screen and its track is nearly invisible in greyscale; blocks read across the room and can
 * be hit anywhere along their length. [wheel] marks the level the wheel is moving.
 */
@Composable
fun LevelBar(
    label: String,
    value: Float,
    enabled: Boolean,
    wheel: Boolean,
    segments: Int = 16,
    onFocus: () -> Unit,
    onChange: (Float) -> Unit,
) {
    val colors = LightThemeTokens.colors
    val filled = (value * segments + 0.001f).toInt().coerceIn(0, segments)
    Column(modifier = Modifier.padding(vertical = 0.3f.gridUnitsAsDp())) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable(enabled = enabled, onClick = onFocus),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightText(
                text = if (wheel && enabled) "$label  (WHEEL)" else label,
                variant = LightTextVariant.Fine,
                lighten = !enabled,
                modifier = Modifier.weight(1f),
            )
            LightText(
                text = "${(value * 100).toInt()}",
                variant = LightTextVariant.Fine,
                lighten = !enabled,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.9f.gridUnitsAsDp())
                .padding(top = 0.2f.gridUnitsAsDp())
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    detectTapGestures { offset ->
                        onFocus()
                        onChange((offset.x / w).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    detectHorizontalDragGestures { change, _ ->
                        onChange((change.position.x / w).coerceIn(0f, 1f))
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            repeat(segments) { i ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(
                            when {
                                !enabled -> colors.contentSecondary.copy(alpha = 0.25f)
                                i < filled -> colors.content
                                else -> colors.contentSecondary.copy(alpha = 0.35f)
                            },
                        ),
                )
            }
        }
    }
}
