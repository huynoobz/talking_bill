# Talking Bill

An Android application that monitors banking notifications and announces money transactions using text-to-speech.

## Features

- **Notification Monitoring**: Monitors notifications from banking apps
- **Transaction Announcements**: Announces money transactions using text-to-speech
- **Background Service**: Runs continuously in the background to monitor notifications
- **Auto-Restart After Reboot**: The service automatically restarts after device reboot if it was enabled before
- **Customizable Speech**: Customize the prefix and currency text for announcements
- **Notification History**: Saves and displays notification history
- **Battery Optimization**: Handles battery optimization settings for reliable background operation
- **Notification Channel**: Uses a dedicated notification channel for reliable foreground service operation

## Requirements

- Android 8.0 (API level 26) or higher
- Notification access permission
- Battery optimization exemption
- Notification permission (Android 13+)
- Boot completed permission (`RECEIVE_BOOT_COMPLETED`)

## Setup & Onboarding

1. Install the app
2. Grant notification access permission when prompted
3. Grant notification permission (Android 13+)
4. Disable battery optimization for the app when prompted
5. The service will start automatically after all permissions are granted—no manual start needed
6. Customize speech settings if needed

## Configuration

The app uses a JSON configuration file (`app_config.json`) to define:
- Supported banking apps
- Keywords for transaction detection
- Regular expressions for money amount extraction

## Usage

- **Automatic Service Start**: The notification monitoring service starts automatically after all required permissions are granted. There is no manual 'Start' switch.
- **Save Notifications**: Toggle the save switch to store notification history
- **Filter & Voice**: Toggle to enable/disable filtering and voice announcements
- **Clear History**: Use the clear button to remove all stored notifications
- **Reset App**: Use the reset button to restore default settings

## Background Persistence & Auto-Restart

- The app registers a broadcast receiver for `BOOT_COMPLETED` events.
- If the notification monitoring service was enabled before reboot, it will automatically restart after the device boots.
- This ensures continuous monitoring without manual intervention after device restarts.

## Customization

- **Speech Prefix**: Customize the text spoken before the amount
- **Currency Text**: Customize the currency text spoken after the amount

## Troubleshooting

If the app stops monitoring notifications:
1. Check if notification access is still enabled
2. Verify battery optimization is disabled for the app
3. Try restarting the app
4. If issues persist, use the reset function
5. Ensure the app has permission to receive boot events

## Development

Built with:
- Kotlin
- Android Jetpack
- Material Design components
- Android Notification Listener Service

## License

This project is free and open source. Feel free to use, modify, and distribute it as you wish.