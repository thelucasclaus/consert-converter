# Concert Converter — Android source project (prototype v0.1)

A native Android app with buttons for multi-file selection, peak analysis, gain reduction, and concatenation into one 24-bit or 16-bit PCM WAV. Designed to stream large files without loading the whole recording into memory.

## Build
1. Open this folder in Android Studio (on a Windows/macOS/Linux computer).
2. Let Gradle sync and install Android SDK 35 if prompted.
3. Choose **Build > Build APK(s)** and install the debug APK on your Samsung tablet.

**This ZIP is source code, not an APK.** An APK has not been compiled or tested here. The app cannot be installed directly from the ZIP.

## Use
1. Tap **Add WAV files**. Multi-select the clips in intended order (Android's file picker may return an order different from the order tapped; check the numbered list).
2. Tap **Analyze peak levels**. The app suggests a negative gain setting that places the highest sample peak at or below -3 dBFS.
3. Choose 24-bit WAV and tap **Join + convert**. Select an output filename/location.
4. Keep the app open while it works. Keep the original recordings as backups.

## Limitations / planned features
- Current prototype **does not encode FLAC or MP3**, and does not have EQ, compression, limiting, or embedded metadata yet.
- Input supports standard RIFF WAV with 16/24/32-bit integer PCM or 32/64-bit IEEE float; not RF64/W64, not compressed WAV.
- All files must have identical sample rates and channel counts. Clips are **joined end-to-end**, not overlapped/mixed. The result is a continuous show, but there is no crossfade or gap removal.
- Standard RIFF WAV output is limited to about 4 GB. The app checks this before exporting.
- Android file picker ordering is not guaranteed; add files one at a time to control the sequence.
- Long conversions are not run in a foreground service. Keep the app open and battery optimization off during processing.
- The conversion clips peaks at full scale if user-entered gain is insufficient; use Analyze first.
- Export currently does not add dither when reducing floating-point audio to integer PCM.
- Prototype has not been device-tested. Verify output duration, peak levels, and audible joins before deleting originals.
