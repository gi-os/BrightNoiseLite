package com.gios.brightnoiselite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.brightnoiselite.audio.Settings
import com.gios.brightnoiselite.audio.Slot
import com.gios.brightnoiselite.audio.SoundId
import com.gios.brightnoiselite.audio.formatCountdown
import com.gios.brightnoiselite.play.NoiseViewModel
import com.gios.brightnoiselite.play.Tab
import com.gios.brightnoiselite.play.WheelTarget
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.delay

/**
 * The whole tool on one screen, laid out like the full app: three tabs, with the transport
 * always visible above them.
 */
@InitialScreen
class NoiseScreen(private val sealedActivity: SealedLightActivity) :
    LightScreen<Unit, NoiseViewModel>(sealedActivity) {

    override val viewModelClass = NoiseViewModel::class.java
    override fun createViewModel() = NoiseViewModel(
        audio = DefaultLightAudio(sealedActivity),
        dataStore = lightContext.dataStore,
        filesDir = lightContext.filesDir,
    )

    @Composable
    override fun Content() {
        val vm = viewModel
        val tab by vm.tab.collectAsState()
        val settings by vm.settings.collectAsState()
        val playing by vm.isPlaying.collectAsState()
        val busy by vm.busy.collectAsState()
        val endsAt by vm.endsAt.collectAsState()
        val message by vm.message.collectAsState()
        val target by vm.wheelTarget.collectAsState()

        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(endsAt) {
            while (endsAt > 0L) {
                now = System.currentTimeMillis()
                delay(1_000L - now % 1_000L)
            }
        }
        val leftSeconds = if (endsAt > 0L) ((endsAt - now + 999L) / 1000L).coerceAtLeast(0L) else 0L

        Themed {
            Column(modifier = Modifier.fillMaxSize()) {
                LightTopBar(
                    center = LightTopBarCenter.Text(
                        when (tab) {
                            Tab.Sounds -> "Sounds"
                            Tab.Mix -> "Mix"
                            Tab.Timer -> "Sleep timer"
                        },
                    ),
                    modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        Tab.Sounds -> SoundsTab(settings) { vm.playNow(it) }
                        Tab.Mix -> MixTab(settings, target, vm)
                        Tab.Timer -> TimerTab(settings, endsAt, leftSeconds) { vm.setTimer(it) }
                    }
                }
                Transport(
                    label = settings.nowPlayingLabel,
                    status = when {
                        message != null -> message.orEmpty()
                        busy -> "Preparing…"
                        playing && endsAt > 0L -> "Playing · ${formatCountdown(leftSeconds)} left"
                        playing -> "Playing"
                        else -> "Stopped"
                    },
                    active = playing || busy,
                    onToggle = vm::togglePlay,
                )
                LightBottomBar(
                    listOf(
                        tabButton("SOUNDS", tab == Tab.Sounds) { vm.selectTab(Tab.Sounds) },
                        tabButton("MIX", tab == Tab.Mix) { vm.selectTab(Tab.Mix) },
                        tabButton("TIMER", tab == Tab.Timer) { vm.selectTab(Tab.Timer) },
                    ),
                )
            }
        }
    }

    /** LightOS action-bar idiom: the active tab is bracketed. */
    private fun tabButton(label: String, active: Boolean, onClick: () -> Unit) =
        LightBarButton.Text(text = if (active) "[$label]" else label, onClick = onClick)
}

@Composable
private fun TabBody(content: @Composable ColumnScope.() -> Unit) {
    LightScrollView(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 1f.gridUnitsAsDp()),
        scrollState = rememberScrollState(),
    ) {
        content()
        Box(modifier = Modifier.height(0.5f.gridUnitsAsDp()))
    }
}

/** Tab 1: the twelve sounds. Tapping one plays it on layer A straight away. */
@Composable
private fun SoundsTab(settings: Settings, onPick: (SoundId) -> Unit) {
    TabBody {
        SoundId.entries.forEach { id ->
            SoundRow(
                label = id.label,
                sub = id.blurb,
                selected = settings.pickA == id,
                trailing = if (settings.pickB == id) "B" else null,
                onClick = { onPick(id) },
            )
        }
    }
}

/** Tab 2: two layers with their own levels, plus the output volume. */
@Composable
private fun MixTab(settings: Settings, target: WheelTarget, vm: NoiseViewModel) {
    TabBody {
        LayerSection("LAYER A", Slot.A, settings, target == WheelTarget.LevelA, WheelTarget.LevelA, vm)
        LayerSection("LAYER B", Slot.B, settings, target == WheelTarget.LevelB, WheelTarget.LevelB, vm)
        SectionLabel("OUTPUT")
        LevelBar(
            label = "VOLUME",
            value = settings.master,
            enabled = true,
            wheel = target == WheelTarget.Volume,
            onFocus = { vm.setWheelTarget(WheelTarget.Volume) },
            onChange = vm::setMaster,
        )
    }
}

@Composable
private fun LayerSection(
    title: String,
    slot: Slot,
    settings: Settings,
    wheel: Boolean,
    wheelTarget: WheelTarget,
    vm: NoiseViewModel,
) {
    val current = settings.pick(slot)
    SectionLabel(title)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Chip("NONE", current == null) { vm.setPick(slot, null) }
        SoundId.entries.forEach { id ->
            Chip(id.label.uppercase(), current == id) { vm.setPick(slot, id) }
        }
    }
    LevelBar(
        label = "LEVEL",
        value = settings.level(slot),
        enabled = current != null,
        wheel = wheel,
        onFocus = { vm.setWheelTarget(wheelTarget) },
        onChange = { vm.setLevel(slot, it) },
    )
}

/** Tab 3: the sleep timer. */
@Composable
private fun TimerTab(settings: Settings, endsAt: Long, leftSeconds: Long, onPick: (Int) -> Unit) {
    TabBody {
        LightText(
            text = when {
                endsAt > 0L -> formatCountdown(leftSeconds)
                settings.timerMinutes > 0 -> "${settings.timerMinutes} min"
                else -> "Off"
            },
            variant = LightTextVariant.Title,
            align = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 1f.gridUnitsAsDp()),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Settings.TIMER_PRESETS.forEach { minutes ->
                Chip(
                    label = if (minutes == 0) "OFF" else "$minutes MIN",
                    selected = settings.timerMinutes == minutes,
                ) { onPick(minutes) }
            }
        }
        LightText(
            text = "The sound fades out over the last 25 seconds, then stops.",
            variant = LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
        )
    }
}

/**
 * Always-visible transport. Big target, inverted while playing, because this is a tool you
 * reach for in the dark.
 */
@Composable
private fun Transport(label: String, status: String, active: Boolean, onToggle: () -> Unit) {
    val colors = LightThemeTokens.colors
    Column {
        Rule()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(2f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 1f.gridUnitsAsDp()),
            ) {
                LightText(text = label, variant = LightTextVariant.Copy, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LightText(
                    text = status,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .background(if (active) colors.content else colors.background)
                    .lightClickable(onClick = onToggle)
                    .padding(horizontal = 1f.gridUnitsAsDp()),
                contentAlignment = Alignment.Center,
            ) {
                LightText(
                    text = if (active) "STOP" else "PLAY",
                    variant = LightTextVariant.Button,
                    color = if (active) colors.background else colors.content,
                )
            }
        }
        Rule()
    }
}
