package com.gios.brightnoiselite.play

import android.view.KeyEvent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.viewModelScope
import com.gios.brightnoiselite.audio.LiveQueue
import com.gios.brightnoiselite.audio.QueuePlan
import com.gios.brightnoiselite.audio.SAMPLE_RATE
import com.gios.brightnoiselite.audio.Settings
import com.gios.brightnoiselite.audio.Slot
import com.gios.brightnoiselite.audio.SoundId
import com.gios.brightnoiselite.hw.LightKey
import com.gios.brightnoiselite.hw.LightKeys
import com.gios.brightnoiselite.hw.WheelGuard
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioPlayerException
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightAudioUsage
import com.thelightphone.sdk.audio.LightMediaMetadata
import com.thelightphone.sdk.audio.NO_MEDIA_ITEM
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The three tabs, as in the full app. */
enum class Tab { Sounds, Mix, Timer }

/** Which level the wheel moves on the Mix tab. Everywhere else it moves the volume. */
enum class WheelTarget { LevelA, LevelB, Volume }

/**
 * Owns playback for the whole tool.
 *
 * Sound goes out through one detached SDK player, so it keeps going with the screen off and
 * after the tool is closed. The player plays files, so every change the user makes ends in
 * rendering (when a sound changes), mixing (when a level changes) and writing WAV files, then
 * handing the player a new queue. See [Renders] and the README for why it works this way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoiseViewModel(
    private val audio: LightAudio,
    private val dataStore: DataStore<Preferences>,
    filesDir: File,
) : LightViewModel<Unit>() {

    private val dir = File(filesDir, "noise")

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _tab = MutableStateFlow(Tab.Sounds)
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    private val _wheelTarget = MutableStateFlow(WheelTarget.Volume)
    val wheelTarget: StateFlow<WheelTarget> = _wheelTarget.asStateFlow()

    /** True while sound is being rendered or written, before the player has it. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Wall clock ms when a timed session ends, or 0. */
    private val _endsAt = MutableStateFlow(Renders.endsAt)
    val endsAt: StateFlow<Long> = _endsAt.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val playerFlow = MutableStateFlow<LightAudioPlayer?>(null)

    val isPlaying: StateFlow<Boolean> = playerFlow
        .flatMapLatest { it?.isPlaying ?: flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val sessionLock = Mutex()
    private var sessionJob: Job? = null
    private var remixJob: Job? = null
    private val wheel = WheelGuard()

    init {
        viewModelScope.launch {
            _settings.value = load()
            val player = connect() ?: return@launch
            playerFlow.value = player
            if (!player.awaitReady()) return@launch
            if (player.currentMediaItemIndex.value == NO_MEDIA_ITEM) {
                // Nothing survived from last time: forget it and clear out its files.
                Renders.live = null
                setEndsAt(0L)
                withContext(Dispatchers.IO) { Renders.sweep(dir, emptyList()) }
            }
            // Render the saved sounds now, so the first PLAY does not wait for synthesis.
            sessionLock.withLock {
                withContext(Dispatchers.Default) { Renders.ensureLayers(_settings.value) }
            }
        }
        viewModelScope.launch {
            playerFlow.flatMapLatest { it?.error ?: flowOf(null) }.collect { error ->
                if (error != null) _message.value = "Playback stopped (${error.diagnostic})"
            }
        }
        viewModelScope.launch {
            isPlaying.collect { playing ->
                if (playing) _message.value = null
                val ends = _endsAt.value
                // A timed session that ran out has ended for good.
                if (!playing && ends > 0L && System.currentTimeMillis() >= ends - 1_000L) {
                    Renders.live = null
                    setEndsAt(0L)
                }
            }
        }
    }

    /** Only one detached handle may exist; a screen being replaced may still hold the last one. */
    private suspend fun connect(): LightAudioPlayer? {
        repeat(20) {
            try {
                return audio.newPlayer(LightAudioUsage.Music, LightAudioPlayback.Detached)
            } catch (e: LightAudioPlayerException) {
                delay(250)
            }
        }
        _message.value = "Audio is busy. Close and reopen the tool."
        return null
    }

    // ---------------------------------------------------------------------------- transport

    fun togglePlay() {
        if (isPlaying.value || _busy.value) stop() else start()
    }

    fun stop() {
        sessionJob?.cancel()
        remixJob?.cancel()
        _busy.value = false
        playerFlow.value?.let { p ->
            p.stop()
            p.setMediaQueue(emptyList())
        }
        Renders.live = null
        setEndsAt(0L)
    }

    /** Tapping a sound in the list plays it right away on layer A. */
    fun playNow(id: SoundId) {
        update(_settings.value.withPick(Slot.A, id))
        start()
    }

    private fun start() {
        if (!_settings.value.hasSound) {
            _message.value = "Both layers are off. Pick a sound first."
            return
        }
        remixJob?.cancel()
        launchSession { startFresh() }
    }

    // ------------------------------------------------------------------------------- mixing

    fun selectTab(tab: Tab) {
        _tab.value = tab
    }

    fun setWheelTarget(target: WheelTarget) {
        _wheelTarget.value = target
    }

    fun setPick(slot: Slot, id: SoundId?) {
        if (_settings.value.pick(slot) == id) return
        update(_settings.value.withPick(slot, id))
        if (sessionActive()) {
            if (_settings.value.hasSound) start() else stop()
        }
    }

    fun setLevel(slot: Slot, v: Float) {
        update(_settings.value.withLevel(slot, v))
        scheduleRemix()
    }

    fun setMaster(v: Float) {
        update(_settings.value.copy(master = v.coerceIn(0f, 1f)))
        scheduleRemix()
    }

    fun setTimer(minutes: Int) {
        update(_settings.value.copy(timerMinutes = minutes))
        // As in the full app, choosing a timer while playing starts it from now.
        if (sessionActive()) start()
    }

    private fun sessionActive(): Boolean = isPlaying.value || _busy.value || Renders.live != null

    /** Level moves are bunched up, so a turn of the wheel is one remix, not twenty. */
    private fun scheduleRemix() {
        if (!sessionActive()) return
        remixJob?.cancel()
        remixJob = viewModelScope.launch {
            delay(REMIX_DEBOUNCE_MS)
            launchSession { remix() }
        }
    }

    private fun launchSession(block: suspend () -> Unit) {
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch {
            sessionLock.withLock {
                _busy.value = true
                try {
                    block()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _message.value = "Could not prepare the sound (${e.message ?: "error"})"
                } finally {
                    _busy.value = false
                }
            }
        }
    }

    /** Builds a whole new session: fade-in head, loops, and a fading tail if the timer is on. */
    private suspend fun startFresh() {
        val player = playerFlow.value ?: return
        if (!player.awaitReady()) return
        val s = _settings.value
        val plan = QueuePlan.forTimer(s.timerMinutes * 60)
        val queue = LiveQueue.fresh(plan)
        val items = withContext(Dispatchers.Default) {
            val loop = Renders.loopFor(s) ?: return@withContext null
            withContext(Dispatchers.IO) { filesFor(queue, loop) }
        } ?: return
        player.setMediaQueue(items.toAudioItems(s))
        player.play()
        Renders.live = queue
        setEndsAt(
            if (plan.hasTail) System.currentTimeMillis() + plan.totalFrames * 1000L / SAMPLE_RATE else 0L,
        )
        withContext(Dispatchers.IO) { Renders.sweep(dir, items) }
    }

    /**
     * Applies new levels without restarting: the item that is playing is cut at the current
     * position and replaced by the same material from the new mix, and the rest of the queue
     * follows as before. The timer keeps its end time. There is a short gap while the player
     * opens the new file; there is no restart and no second fade-in.
     */
    private suspend fun remix() {
        val player = playerFlow.value ?: return
        if (!player.awaitReady()) return
        val live = Renders.live
        val s = _settings.value
        if (live == null) {
            if (isPlaying.value) startFresh()
            return
        }
        val loop = withContext(Dispatchers.Default) { Renders.loopFor(s) } ?: return
        val wasPlaying = isPlaying.value
        val index = player.currentMediaItemIndex.value
        val positionMs = player.positionMs.value
        val sampledAt = System.currentTimeMillis()
        val items = withContext(Dispatchers.IO) {
            // Account for the time spent writing, so the new file starts where the old one is.
            val writeAllowanceMs = 300L
            val elapsed = if (wasPlaying) System.currentTimeMillis() - sampledAt + writeAllowanceMs else 0L
            val offset = ((positionMs + elapsed) * SAMPLE_RATE / 1000L).toInt()
            val next = live.resumeAt(index, offset) { Renders.partFrames(it, live.tailFrames) }
                ?: return@withContext null
            next to filesFor(next, loop)
        }
        if (items == null) {
            startFresh()
            return
        }
        val (next, files) = items
        player.setMediaQueue(files.toAudioItems(s))
        if (wasPlaying) player.play()
        Renders.live = next
        withContext(Dispatchers.IO) { Renders.sweep(dir, files) }
    }

    private fun filesFor(queue: LiveQueue, loop: ShortArray): List<File> =
        queue.segments.map { seg -> Renders.fileFor(dir, loop, seg.part, seg.offsetFrames, queue.tailFrames) }

    private fun List<File>.toAudioItems(s: Settings): List<LightAudioItem> {
        val meta = LightMediaMetadata(title = s.nowPlayingLabel, artist = "BrightNoise Lite")
        return map { LightAudioItem(LightAudioSource.FileSource(it), meta) }
    }

    private fun setEndsAt(ms: Long) {
        Renders.endsAt = ms
        _endsAt.value = ms
    }

    // -------------------------------------------------------------------------------- wheel

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val key = LightKeys.of(keyCode, event) ?: return super.onKeyDown(keyCode, event)
        val notches = wheel.accept(if (key == LightKey.WheelUp) 1 else -1)
        if (notches != 0) nudge(notches)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (LightKeys.of(keyCode, event) != null) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun nudge(notches: Int) {
        val step = notches / LEVEL_STEPS.toFloat()
        val s = _settings.value
        val target = if (_tab.value == Tab.Mix) _wheelTarget.value else WheelTarget.Volume
        when (target) {
            WheelTarget.LevelA -> if (s.pickA != null) setLevel(Slot.A, snap(s.levelA + step))
            WheelTarget.LevelB -> if (s.pickB != null) setLevel(Slot.B, snap(s.levelB + step))
            WheelTarget.Volume -> setMaster(snap(s.master + step))
        }
    }

    private fun snap(v: Float): Float =
        ((v * LEVEL_STEPS).roundToInt() / LEVEL_STEPS.toFloat()).coerceIn(0f, 1f)

    // ---------------------------------------------------------------------------- settings

    private fun update(s: Settings) {
        _settings.value = s
        viewModelScope.launch {
            dataStore.edit { p ->
                p[KEY_A] = Settings.encodePick(s.pickA)
                p[KEY_B] = Settings.encodePick(s.pickB)
                p[KEY_LEVEL_A] = s.levelA
                p[KEY_LEVEL_B] = s.levelB
                p[KEY_MASTER] = s.master
                p[KEY_TIMER] = s.timerMinutes
            }
        }
    }

    private suspend fun load(): Settings {
        val d = Settings()
        val p = dataStore.data.first()
        return Settings(
            pickA = Settings.decodePick(p[KEY_A], d.pickA),
            pickB = Settings.decodePick(p[KEY_B], d.pickB),
            levelA = p[KEY_LEVEL_A] ?: d.levelA,
            levelB = p[KEY_LEVEL_B] ?: d.levelB,
            master = p[KEY_MASTER] ?: d.master,
            timerMinutes = p[KEY_TIMER]?.takeIf { it in Settings.TIMER_PRESETS } ?: d.timerMinutes,
        )
    }

    override fun onCleared() {
        // Detached: releasing the handle leaves the sound playing, which is the point.
        playerFlow.value?.release()
        super.onCleared()
    }

    private companion object {
        const val LEVEL_STEPS = 16
        const val REMIX_DEBOUNCE_MS = 600L

        val KEY_A = stringPreferencesKey("pick_a")
        val KEY_B = stringPreferencesKey("pick_b")
        val KEY_LEVEL_A = floatPreferencesKey("level_a")
        val KEY_LEVEL_B = floatPreferencesKey("level_b")
        val KEY_MASTER = floatPreferencesKey("master")
        val KEY_TIMER = intPreferencesKey("timer_minutes")
    }
}
