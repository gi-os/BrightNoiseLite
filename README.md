<a href="https://github.com/sponsors/gi-os"><img src="https://raw.githubusercontent.com/gi-os/gi-os/main/images/bright-sponsors.svg" width="100%" alt="Thank you to my sponsors"></a>

# BrightNoise Lite

> **This is the Lite version.** BrightNoise Lite is built only with Light's official SDK, so it can go in the Light Tool Library. It does less than the full app. For the full app, get [BrightNoise](https://github.com/gi-os/BrightNoise) ([on BrightMarket](https://brightmarket.gzl.dev/app/com.gios.lightnoise/)).

White noise and background sound for the Light Phone III, built as an official Light SDK tool.
Launcher label **BrightNoise Lite**, tool id `com.gios.brightnoiselite`.

This is the SDK version of [BrightNoise](https://github.com/gi-os/BrightNoise), a sideloaded
app. Almost all of it carries over: the same twelve synthesised sounds, the two-layer mixer, the
sleep timer with its fade-out, and the wheel. What changed is how the sound gets to the speaker,
because the SDK does not allow the audio path the full app uses. That change is explained below.

## What it does

**Sounds.** Twelve sounds, all synthesised on the phone. The tool carries no audio files.

| | Sound | How it is made |
|---|---|---|
| 1 | White noise | Uniform noise, rolled off above 14 kHz |
| 2 | Pink noise | Kellet economy filter, -3 dB/oct |
| 3 | Brown noise | Leaky integrator, -6 dB/oct |
| 4 | Rain | Hiss, a mid body and about 55 discrete drops a second, with slow gusts |
| 5 | Thunderstorm | Heavier rain plus a swept low rumble every 20-70 s |
| 6 | Ocean waves | A 7-13 s swell driving a lowpass sweep and foam |
| 7 | Stream | Two noise bands around 620 Hz and 1.65 kHz, plus drifting gurgles |
| 8 | Fan | Oscillating head on a 7.1 s sweep, mains hum, blade beating |
| 9 | Train | Rail rumble with a rail-joint clack every 1.5 s or so |
| 10 | Campfire | Ember bed with woody crackles that come in bursts |
| 11 | Cafe | Fourteen quiet talkers of formant-filtered noise, plus cutlery |
| 12 | Wind | One gust envelope driving level, cutoff and leaf hiss together |

The synthesis code (`audio/Dsp.kt`, `audio/Sounds.kt`) is the full app's, unchanged apart from
the package name and one read-only property on the limiter. Its test suite came across too.

**Three tabs**, with the transport (what is playing, and a PLAY/STOP button that inverts while
playing) always visible above them:

- **SOUNDS**: tap a sound to play it on layer A straight away.
- **MIX**: layer A and layer B, each with any of the twelve sounds or NONE, and their own level,
  plus the output volume. Levels are segmented bars you can tap or drag.
- **TIMER**: off, 15, 30, 45, 60, 90 or 120 minutes. The volume fades out over the last 25
  seconds and playback stops. Picking a timer while sound is playing starts it from now.

The mix and the timer choice are saved, so the tool reopens on whatever you fell asleep to.

**Sound keeps playing with the screen off and after you leave the tool.** It goes out through the
SDK's detached player (`detached-audio` in `lighttool.toml`). Open the tool again and it picks up
the running session: the transport, the countdown and STOP all work on it.

**The wheel changes the volume.** On the MIX tab it moves whichever level is marked (WHEEL); tap
a level's label or bar to move the wheel to it. On the other tabs it always moves the output
volume. One notch is one bar of sixteen. As in the full app, the first notch after a pause is
held until a second one confirms it, so a thumb brushing the wheel in the dark does not change
anything. The keys are read in `LightViewModel.onKeyDown`, using the same `WHEEL_CCW` /
`WHEEL_CW` labels and the same Pixart scancode fallback as the full app.

## How the sound is played, and why

The full app runs its own audio thread that writes the mix into an `AudioTrack` stream forever,
inside a foreground service with a partial wake lock. A tool cannot do that: a foreground service
means `android.app.Service`, and `android.app.*` is a blocked import. The SDK offers two ways to
make sound instead:

1. `LightAudioVoice`, which plays one short mono 16-bit PCM buffer at a time. It is a static
   `AudioTrack` that is restarted on every call, it takes transient audio focus, and it belongs
   to the screen. Chaining buffers by re-triggering it would leave a gap or a click at each
   restart, and it would stop when the tool went to the background.
2. `LightAudioPlayer` in detached mode, which is a media3 player hosted in the SDK's own
   service. It plays a queue of files and keeps going with the tool closed. It has no streaming
   input and no repeat mode.

Only the second can play all night, so the tool renders sound to files and queues them:

- Each layer is synthesised into a **three-minute loop**. The generator runs four seconds past
  the end, and those four seconds are crossfaded (equal power) into the start, so the end of the
  loop flows into material that was generated after it. There is no seam, only a three-minute
  repeat, which is long enough that thunder and swells do not line up into a pattern.
- The two layers are mixed with the full app's gain law, makeup gain, look-ahead limiter and
  soft clip. The limiter is run around the loop (warmed up on its end, flushed with its start),
  so it is in the right state at the join.
- The result is written as a 16-bit mono WAV, along with a two-second fade-in to start on and,
  when the timer is on, a tail that fades out over 25 seconds and ends. The queue is fade-in,
  then the loop as many times as needed, then the tail. With the timer off it is 240 loops,
  just over twelve hours, because a queue has to end somewhere.
- WAV is uncompressed, so there is no encoder padding and the player moves from one copy of
  the loop to the next without a gap.

Some things follow from this:

- **Choosing a sound takes a moment.** A new sound has to be synthesised before it can play
  ("Preparing..." under the title). The saved sounds are rendered when the tool opens, so the
  first PLAY is usually quick.
- **Level and volume changes have a short gap.** A level change remixes the loop and swaps the
  file that is playing for the same material at the new level, from the same point. Nothing
  restarts and the timer keeps its end time, but the player needs a fraction of a second to open
  the new file. Changes are collected for 0.6 s, so one turn of the wheel is one swap.
- **Memory and storage.** Two rendered layers and the mix take about 48 MB of memory while the
  tool is open, and the files on disk are about 16 MB per three minutes of loop. Old files are
  deleted as soon as the player stops using them.

## What was dropped, and why

| Feature in BrightNoise | Why it is not here |
| --- | --- |
| The foreground service with its own `AudioTrack` stream | `android.app.Service` is blocked. Replaced by the SDK's detached player and pre-rendered loops, as above. |
| The partial wake lock | `PowerManager` comes from `getSystemService`, which is blocked. The SDK's playback service is what keeps audio going with the screen off. |
| The notification with a Stop action | Tools have no notification API (`NotificationManager` needs `getSystemService`). Stop from the tool. The SDK's media session may still show a system media control, depending on LightOS. |
| Smooth level ramps while a level moves | The live engine ramped gains sample by sample. A file player can only switch files, so a change lands after a short gap. |
| Wheel scrolling of the sound list and mixer | The wheel now sets the volume, which is the more useful job when sound is already playing in the dark. The lists still scroll by touch. |
| Shake to report a glitch, the crash log and report sheet | Need `SensorManager` from `getSystemService`, and filing a report used `Intent`. Both are blocked. |
| The Akkurat font pulled from `SystemFonts` | The tool uses the SDK's `LightText` styles, which already follow LightOS typography. |

## Tests

The synthesis and loop code has no Android imports, so it runs on the JVM:

```sh
./gradlew -DlightSdk.toolOnly=true :tool:testDebugUnitTest
```

- `DspTest` is the full app's suite: every sound rendered for 60-120 s with no non-finite
  samples, no clipping, no DC drift and a sensible level; the fan, campfire and cafe character
  checks; and the limiter's ceiling, transparency and release.
- `LoopTest` checks that the loop join is no rougher than the middle of the loop, that the
  crossfade keeps the level flat, that a full two-layer mix stays under full scale, that levels
  follow the square law, and that every timer preset ends on the exact sample with a full fade.
- `QueueTest` covers how a remix carries on from the current point; `WavTest` the file format;
  `WheelGuardTest` the stray-notch guard; `SettingsTest` the saved settings.

## Build

You need JDK 17 and the Android SDK (platform 36).

```sh
./gradlew -DlightSdk.toolOnly=true :tool:assembleDebug
./gradlew -DlightSdk.toolOnly=true :tool:testDebugUnitTest :tool:lintDebug
```

CI (`.github/workflows/build-tool.yml`) runs the tests, lint and the debug build on every push
with the same flags as Light's tool builder. It publishes nothing.

To install on a phone or emulator running the Light SDK server, see `./gradlew :tool:uploadTool`
in `tool/build.gradle.kts`.

## Permissions

None. The only capability is `detached-audio`, for playing with the tool closed.

## Tool Library submission notes

- The tool id, label and version live in `tool/lighttool.toml`. Bump `versionCode` for every
  submission.
- Everything is in the `tool` module. `sdk/`, `plugin/` and `lint-rules/` are upstream and
  unmodified, so this repository can be rebased onto
  [lightphone/light-sdk](https://github.com/lightphone/light-sdk).
- No dependencies beyond the SDK client: Compose, DataStore and coroutines come with it.
- No reflection, no blocked imports, no custom manifest.

## Credits

Built on [lightphone/light-sdk](https://github.com/lightphone/light-sdk) (MIT); see `LICENSE`.
The sounds, mixer and limiter are ported from
[gi-os/BrightNoise](https://github.com/gi-os/BrightNoise) (MIT).
