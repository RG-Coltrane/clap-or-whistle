# Clap or Whistle

Find-my-phone for Android. Start it, put the phone down somewhere, then **clap twice or whistle**. The phone rings at full alarm volume and blinks the flashlight until you stop it (or after one minute).

## Install

1. Open the **Releases** page of this repo on your phone and download `app-debug.apk` from the `latest` release.
2. Tap the file and allow "install unknown apps" for your browser when Android asks.
3. Open **Clap or Whistle**, tap **Start listening**, allow the microphone and notifications.

## Tuning

- The bar on screen shows what the mic hears. A clap should spike it.
- Too many false alarms: slide **Sensitivity** down. Not hearing you: slide it up.
- Clap detection wants two sharp claps within 1.5 seconds. Whistle detection wants a steady, pure tone for about a third of a second.

## How it's built

- Plain Kotlin, no libraries. `SoundDetector` does the listening (loudness + a small FFT), `AlarmController` does the noise and flashlight, `ListenerService` keeps it alive in the background, `MainActivity` is the one screen.
- GitHub Actions builds the APK on every push to `main` (see `.github/workflows/build.yml`).
