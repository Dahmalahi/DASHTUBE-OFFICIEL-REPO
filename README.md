DashTube
<img width="96" height="96" alt="Dashtube" src="https://github.com/user-attachments/assets/3c095f60-23ca-4750-8de5-58f246712372" />

DashTube/
├── README.md
├── LICENSE
├── .gitignore
├── build.properties
├── build.xml
├── src/
│   ├── VideoProxyBrowserMIDlet.java
│   ├── HtmlPageCanvas.java
│   ├── SiteParser.java
│   ├── Utils.java
│   ├── FileManager.java
│   ├── DownloadQueue.java
│   ├── DownloadChoiceCanvas.java
│   ├── DownloadProgressScreen.java
│   ├── CaptchaScreen.java
│   ├── LoginScreen.java
│   ├── SavedFilesScreen.java
│   ├── AboutCanvas.java
│   ├── ConvertScreen.java
│   ├── MusicCanvas.java
│   ├── MusicItem.java
│   ├── ImageSearchCanvas.java
│   └── MusicSearchScreen.java
├── res/
│   └── Dashtube.png
├── lib/
│   └── (no external JARs - pure MIDP)
└── docs/
    ├── INSTALL.md
    └── USER_GUIDE.md

File Contents
README.md
markdown

# DashTube v1.2.1

**YouTube/Video/Audio Downloader & Streamer for J2ME Feature Phones**

[![MIDP 2.0](https://img.shields.io/badge/MIDP-2.0-blue.svg)]()
[![CLDC 1.1](https://img.shields.io/badge/CLDC-1.1-green.svg)]()
[![JSR-75](https://img.shields.io/badge/JSR--75-FileConnection-orange.svg)]()

DashTube is a feature-rich multimedia browser and downloader for Java ME (J2ME) enabled feature phones. It allows you to search, stream, and download videos and music from various sources including YouTube, TikTok, and the 2yxa.mobi platform.

![DashTube Screenshot](https://via.placeholder.com/240x320?text=DashTube)

## Features

- 🎥 **Video Streaming & Download** - Stream MP4/3GP or save to storage
- 🎵 **Music Search** - Search and download MP3/AAC files
- 🖼️ **Image Search** - Find and save images with size/resolution options
- 📦 **Download Queue** - Sequential download manager with progress tracking
- 🔐 **Login Support** - Save credentials to bypass CAPTCHAs
- 📱 **Touch Support** - Works on Nokia 5800 and touch-enabled devices
- 💾 **File Storage** - Uses JSR-75 FileConnection for permanent storage
- 🎨 **Dark UI** - Modern dark theme with accent colors

## Supported Sites

- video.2yxa.mobi (primary API)
- YouTube (via extraction)
- TikTok (via extraction)

## Requirements

- **MIDP 2.0** - Mobile Information Device Profile
- **CLDC 1.1** - Connected Limited Device Configuration
- **JSR-75** - FileConnection API (for saving files)
- **JSR-135** - MMAPI (for media playback)
- Network connectivity (GPRS/EDGE/3G/WiFi)

## Supported Devices

- Nokia S60 3rd/5th Edition (5800, N95, N73, etc.)
- Sony Ericsson Java phones
- Samsung Java feature phones
- Any MIDP 2.0 device with JSR-75

## Installation

### From JAR file
1. Download `DashTube.jar` and `DashTube.jad`
2. Transfer to your phone via Bluetooth/USB
3. Install through phone's file manager

### Building from source
```bash
ant jar

Quick Start

    Login (optional) - Enter credentials to avoid CAPTCHAs

    Search - Use the search box to find videos

    Select - Choose from search results

    Download/Stream - Pick your preferred quality

    View Queue - Monitor progress in Download Queue

Key Commands
Action	Key
Navigate Up/Down	D-pad
Select Item	FIRE / OK
Back	Right softkey
Search	0 key
Saved Videos	7 key
Options Menu	Left softkey
Configuration

The app uses a Cloudflare proxy server (2yxa-proxy.ndukadavid70.workers.dev) to bypass network restrictions. No additional configuration required.
Building
Prerequisites

    Java JDK 8 or earlier (for WTK compatibility)

    Sun Java Wireless Toolkit 2.5.2 or newer

Steps
bash

# Clone the repository
git clone https://github.com/yourusername/DashTube.git
cd DashTube

# Build with Ant
ant jar

# Output: dist/DashTube.jar

License

MIT License - See LICENSE file for details
Acknowledgments

    2yxa.mobi for providing the API

    J2ME community for MIDP/CLDC specifications

Disclaimer

This app is for educational purposes. Users are responsible for complying with copyright laws in their jurisdiction when downloading content.
Support

For issues and feature requests, please open a GitHub issue.

Developed by DASH ANIMATION V2
