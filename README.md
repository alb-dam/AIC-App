# AIC - AI Cameraman App

An Android live streaming application built on top of [StreamPack](https://github.com/ThibaultBee/StreamPack), designed to provide a smooth, reliable, and energy-efficient streaming experience. The application allows users to broadcast live using the SRT protocol with robust configuration options and automatic reconnect capabilities.

## Key Features

- **SRT Live Streaming:** High-quality, low-latency streaming.
- **Auto-Reconnect:** The stream automatically reconnects to the server upon network loss or glitches (e.g. switching from WiFi to 4G).
- **In-App Configuration:** Change the SRT Server URL and Video Bitrate (in Mbps) directly from the settings panel.
- **Dynamic Resolution & FPS:** Adjust between HD, FHD, UHD, 8K, and 30/60 FPS.
- **Energy Saving Mode:** The screen automatically dims to save battery after 30 seconds of inactivity while recording/streaming.
- **Advanced Camera Controls:** 
  - Switch between Front and Back cameras.
  - Manual and Auto Focus modes.
  - Audio Mute toggle.
- **Responsive UI:** UI elements gracefully rotate according to device orientation without stopping the stream or recreating the activity.

## Getting Started

1. Clone or download this repository.
2. Open the project in Android Studio.
3. Build and run the application on an Android device (an emulator may not support all camera features).
4. Tap the Settings icon to configure your **SRT URL** and **Bitrate**.
5. Tap the **Live** button (the big round button) to start broadcasting.