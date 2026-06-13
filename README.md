DashTube

<img width="96" height="96" alt="Dashtube" src="https://github.com/user-attachments/assets/3c095f60-23ca-4750-8de5-58f246712372" />



DashTube/

DashTube/
├── .gitignore
├── LICENSE
├── README.md
├── build.properties
├── build.xml
├── docs/
│   ├── INSTALL.md
│   └── USER_GUIDE.md
├── lib/
│   └── README.md             # Explains pure MIDP architecture
├── res/
│   └── Dashtube.png          # App icon (24x24 or 32x32 px)
└── src/
    ├── VideoProxyBrowserMIDlet.java  # Main Application Entry Point
    ├── SiteParser.java               # 2yxa / YT / TikTok HTML & JSON parser
    ├── Utils.java                    # String manipulation, network wrappers
    ├── FileManager.java              # JSR-75 FileConnection operations
    ├── DownloadQueue.java            # Sequential background worker thread
    │
    └── ui/                           # Grouped UI components for clarity
        ├── HtmlPageCanvas.java       # Core rendering engine (Dark UI)
        ├── DownloadChoiceCanvas.java # Formats/Qualities selector
        ├── DownloadProgressScreen.java
        ├── CaptchaScreen.java
        ├── LoginScreen.java
        ├── SavedFilesScreen.java
        ├── AboutCanvas.java
        ├── ConvertScreen.java
        ├── MusicCanvas.java
        ├── MusicItem.java
        ├── ImageSearchCanvas.java
        └── MusicSearchScreen.java

```

---

## ⚙️ Build Automation Files

### `.gitignore`

```gitignore
# Ant & WTK Build Artifacts
build/
dist/
bin/
classes/
tmp/
*.jar
*.jad

# IDE configs
.eclipse/
.idea/
*.iml
.project
.classpath

# OS files
.DS_Store
Thumbs.db

```

### `build.properties`

```properties
# Path to your Sun Wireless Toolkit installation
wtk.home=C:/WTK2.5.2

# Target Device Profile Specs
midp.version=2.0
cldc.version=1.1

# Application Metadata
app.name=DashTube
app.version=1.2.1
app.vendor=BLACK ANIMATION V2

```

### `build.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project name="DashTube" default="jar" basesdir=".">
    <property file="build.properties"/>

    <property name="src.dir" value="src"/>
    <property name="res.dir" value="res"/>
    <property name="build.dir" value="build"/>
    <property name="classes.dir" value="${build.dir}/classes"/>
    <property name="dist.dir" value="dist"/>

    <target name="clean">
        <delete dir="${build.dir}"/>
        <delete dir="${dist.dir}"/>
    </target>

    <target name="init" depends="clean">
        <mkdir dir="${classes.dir}"/>
        <mkdir dir="${dist.dir}"/>
    </target>

    <target name="compile" depends="init">
        <javac srcdir="${src.dir}" 
               destdir="${classes.dir}" 
               target="1.3" 
               source="1.3"
               bootclasspath="${wtk.home}/lib/midpapi20.jar;${wtk.home}/lib/cldcapi11.jar"
               includeantruntime="false"
               debug="true"/>
    </target>

    <target name="preverify" depends="compile">
        <exec executable="${wtk.home}/bin/preverify">
            <arg value="-classpath"/>
            <arg value="${wtk.home}/lib/midpapi20.jar;${wtk.home}/lib/cldcapi11.jar"/>
            <arg value="-d"/>
            <arg value="${classes.dir}"/>
            <arg value="${classes.dir}"/>
        </exec>
    </target>

    <target name="jar" depends="preverify">
        <!-- Generate JAD File -->
        <echo file="${dist.dir}/${app.name}.jad" append="false">MIDlet-Name: ${app.name}
MIDlet-Version: ${app.version}
MIDlet-Vendor: ${app.vendor}
MIDlet-Jar-URL: ${app.name}.jar
MIDlet-1: ${app.name}, /Dashtube.png, VideoProxyBrowserMIDlet
MicroEdition-Profile: MIDP-${midp.version}
MicroEdition-Configuration: CLDC-${cldc.version}
</echo>

        <!-- Generate JAR Archive -->
        <jar destfile="${dist.dir}/${app.name}.jar" manifest="${dist.dir}/${app.name}.jad">
            <fileset dir="${classes.dir}"/>
            <fileset dir="${res.dir}"/>
        </jar>
        
        <!-- Update JAR Size info in JAD -->
        <length file="${dist.dir}/${app.name}.jar" property="jar.length"/>
        <echo file="${dist.dir}/${app.name}.jad" append="true">MIDlet-Jar-Size: ${jar.length}
</echo>
    </target>
</project>

```

---



# DashTube v1.2.1

[![Platform](https://img.shields.io/badge/Platform-J2ME%20%2F%20MIDP%202.0-orange.svg)](https://en.wikipedia.org/wiki/Java_Platform,_Micro_Edition)
[![Configuration](https://img.shields.io/badge/Config-CLDC%201.1-blue.svg)]()
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

DashTube is a highly optimized multimedia browser, streaming client, and download manager built natively for Java ME (J2ME) enabled feature phones. It provides legacy mobile hardware with a streamlined portal to discover, play back, and download video and audio content across modern web platforms.

---

## 🎥 Features

* **Advanced Media Downloader:** Multi-format pipeline targeting MP4, 3GP, and MP3 processing formats.
* **Background Queue Manager:** Seamless sequential background downloading tracking status, speeds, and block sizes via JSR-75.
* **Optimized Parsing Engine:** Handled by low-overhead extraction scripts for YouTube, TikTok, and backend platforms.
* **Dynamic Media Search:** Separate modules for dedicated music searches and multi-resolution image extraction.
* **Cyber-Neon Dark UI:** custom `CustomCanvas` styling framework engineered specifically for performance on 240x320 resolution arrays, featuring low-contrast scrolling indicators and touch responsiveness.
* **Legacy Security Integrations:** Specialized handler screens accommodating explicit User Sign-in and interactive captcha bypass routines.

---

## 🌐 Architecture & Network Flow

To overcome modern HTTPS (TLS 1.2/1.3) handshakes and hefty JSON payloads that easily crash KVM memory boundaries on feature phones, DashTube offloads scraping logic to an external infrastructure edge proxy.


```

[MIDP Client] ──(HTTP/Basic Payload)──> [Cloudflare Worker Proxy]
│
(Data Extraction)
▼
[Target Files] <──(Direct Byte Stream)── [YT / TikTok / 2yxa]

```

> [!NOTE]
> Network requests flow securely through the pre-configured deployment endpoint: `2yxa-proxy.ndukadavid70.workers.dev`.

---

## 🛠️ Requirements

### Runtime API Dependencies
* **CLDC 1.1** (Connected Limited Device Configuration)
* **MIDP 2.0** (Mobile Information Device Profile)
* **JSR-75** (FileConnection API) — Required for local media storage initialization
* **JSR-135** (Mobile Media API / MMAPI) — Required for audio and streaming processing pipelines

### Target Hardware Compatibility Profile
* **Nokia S60 Series:** 3rd Edition, 5th Edition (Nokia 5800 XpressMusic, N95, E72, E5, etc.)
* **Low-Resource Feature Phones:** Itel 5615, Tecno T528, and generic MediaTek-powered environments.
* **Touch Optimization:** Includes full pointer-event tracking constraints for early resistive/capacitive touch screen standards.

---

## ⌨️ Global Keybindings Reference

| Mapping Option | Key Assignment | Contextual System Response |
| :--- | :--- | :--- |
| **Up / Down** | `D-Pad / Navigation keys` | Traverses link list node indices |
| **Select / Execute** | `FIRE / OK Key` | Activates action handlers / canvas changes |
| **Return Route** | `Right Softkey` | Cancels processes / backs out of screens |
| **Search Prompt** | `0 Key` | Fires input textbox overlays globally |
| **Local Directory** | `7 Key` | Instant jump route to `SavedFilesScreen` |
| **Contextual Menu** | `Left Softkey` | Exposes target-specific action option sets |

---

## 📦 Compilation Environment Setup

### Prerequisites
1. **Java Development Kit (JDK 8 or lower):** J2ME byte-code verification stages rely on internal classes compiled specifically under target format configurations matching `-target 1.3`.
2. **Sun Java Wireless Toolkit (WTK) 2.5.2** (or equivalent vendor SDK environments like Nokia SDK).

### Build Procedure
Clone and deploy compilation targets via Apache Ant:

```bash
# Clone source structure safely
git clone [https://github.com/yourusername/DashTube.git](https://github.com/yourusername/DashTube.git)
cd DashTube

# Fire compile, preverification, and package rules
ant jar

```

Generated distributions (`DashTube.jar` / `DashTube.jad`) will output inside the local `/dist` directory folder automatically.

---

## 📜 Credits & Disclaimers

* **Lead Developer:** `BLACK ANIMATION V2`
* **API Providers:** Powered in connection with the backend interfaces of `2yxa.mobi`.

*This utility is provided explicitly as an educational utility asset. Users retain total liability and assume structural responsibility regarding compliance layout rules involving digital rights protections active across target asset networks inside local legal jurisdictions.*

```

```
