# Talking Bill

Talking Bill is an Android app that listens to selected app notifications, detects incoming money messages, and speaks the amount using text-to-speech (TTS).

## Key Features

- Monitors notification content from configured apps
- Detects transaction messages with keywords and regex patterns
- Announces detected amounts with TTS
- Runs as a foreground notification listener service for better reliability
- Stores notification history (optional)
- Restarts monitoring after reboot (when previously enabled)
- Lets you customize spoken prefix and currency text

## Requirements

- Android 8.0+ (API 26+)
- Notification Listener access
- Notification permission on Android 13+ (`POST_NOTIFICATIONS`)
- Battery optimization disabled for stable background behavior
- Boot permission (`RECEIVE_BOOT_COMPLETED`) for auto-restart support

## Quick Start

1. Install and open the app.
2. Grant Notification Listener access when prompted.
3. Allow notifications (Android 13+).
4. Disable battery optimization for the app.
5. Keep the app enabled; monitoring starts automatically after required permissions are granted.

## App Configuration

The detection behavior is configured in `app/src/main/assets/app_config.json`.

Each app entry can define:
- `receive_keyword`: keywords used to identify incoming-money messages
- `m_regex`: amount matching patterns

Default config currently includes:
- `agribank`
- `momo`
- `messaging`

## Usage

- **Enable/Disable voice behavior**: Use the in-app toggles for filtering and announcements
- **Save history**: Turn on notification saving to keep detected events
- **Clear history**: Remove stored notifications from the app screen
- **Reset settings**: Restore default app preferences
- **Speech text customization**: Set your own prefix/currency phrase for announcements

## Reliability Notes

- The listener service uses foreground mode (`dataSync`) to reduce service interruption.
- A boot receiver listens for `BOOT_COMPLETED` and restores monitoring state after restart.
- Some OEM battery managers may still stop background tasks; whitelist the app if needed.

## Troubleshooting

If announcements stop working:

1. Re-check Notification Listener access.
2. Confirm notification permission is still granted (Android 13+).
3. Ensure battery optimization is disabled and the app is not restricted by OEM power manager.
4. Trigger a sample notification from a configured app and verify the pattern in `app_config.json`.
5. Restart the app or use the in-app reset option.

## Development

### Tech Stack

- Kotlin
- Android SDK / Jetpack
- Notification Listener Service
- Android TextToSpeech

### Build

From project root:

```bash
./gradlew assembleDebug
```

Debug APK output:

`app/build/outputs/apk/debug/app-debug.apk`