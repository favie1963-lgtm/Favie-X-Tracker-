# Favie-X-Tracker 🎯

A powerful screen tracking application that combines AI and computer vision to monitor, analyze, and report on screen activity with advanced analytics.

---

## ✨ Features

- 🎥 **Real-time Screen Capture** - Continuous monitoring of screen activity
- 🧠 **AI-Powered Analysis** - Machine learning-based pattern recognition
- 👁️ **Computer Vision** - Advanced visual tracking and detection
- 📊 **Detailed Analytics** - Comprehensive reports and insights
- 🎯 **Cup/Object Tracking** - Specialized tracking for game objects (Thimbles integration)
- 💾 **Local Storage** - Offline mode with data persistence
- 📈 **Activity Reports** - Generate detailed usage and activity reports
- 🔒 **Privacy-First** - Local processing, no cloud dependency

---

## 🚀 Quick Start

### Prerequisites

- **Node.js 20+**
- **JDK 21** and the **Android SDK** (only needed to build the APK)
- **Python 3.8+** with OpenCV (only needed to run the vision parity tests)

### Build the Android APK

```bash
git clone https://github.com/favie1963-lgtm/Favie-X-Tracker-.git
cd Favie-X-Tracker-
npm install
npm run apk
```

The debug APK is written to:

```
android/app/build/outputs/apk/debug/app-debug.apk
```

Install it on a connected device with:

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

The first time tracking is started the app asks for screen-capture consent.
Capture then runs in a foreground service so it keeps working while the app is
in the background.

### Web / desktop development

```bash
npm install
npm run dev      # dev server on http://localhost:3000
npm run build    # production bundle in dist/
npm run preview  # serve the built bundle
```

In a browser the capture source falls back to `getDisplayMedia`, so a screen,
window or tab can be shared without an Android device.

### Vision parity tests

The JavaScript port is checked against an OpenCV reference implementation:

```bash
pip install opencv-python numpy
python3 scripts/reference_tracker.py .parity
npm run test:vision
```

---

## 📁 Project Structure

```
Favie-X-Tracker-/
├── android/                      # Capacitor Android project
│   └── app/src/main/
│       ├── AndroidManifest.xml
│       └── java/com/favie/xtracker/
│           ├── MainActivity.java
│           ├── ScreenCapturePlugin.java   # MediaProjection capture
│           └── ScreenCaptureService.java  # Foreground service
├── app/
│   └── tracking.py               # OpenCV reference tracker (parity oracle)
├── scripts/
│   ├── reference_tracker.py      # Generates the parity fixture
│   └── vision-parity.mjs         # Compares JS detections to the reference
├── src/
│   ├── App.jsx                   # Tab shell
│   ├── components/               # Tracking / Dashboard / Analytics tabs
│   ├── services/
│   │   ├── analytics.js
│   │   ├── storage.js
│   │   ├── tracking.js           # Cup tracking
│   │   ├── capture/              # Frame sources (Android + getDisplayMedia)
│   │   └── vision/               # Detection, config, OpenCV helpers
│   └── main.jsx
├── thimbles-cup-highlighter.user.js  # Optional Tampermonkey integration
├── capacitor.config.json
├── requirements.txt
└── package.json
```

---

## 🎮 Usage

### Basic Screen Tracking

Start tracking from the Tracking tab. On Android the app asks for screen-capture
consent and then streams frames through a foreground service. In a browser the
same pipeline runs against a screen, window or tab shared via `getDisplayMedia`.

### Cup/Object Tracking (Thimbles)

The app includes specialized tracking for cup-based games:
- Real-time cup position detection
- Ball movement prediction
- Probability analysis

### Tampermonkey Integration

`thimbles-cup-highlighter.user.js` is an optional, standalone browser
userscript. It observes game traffic directly in the page and is independent of
the app; it does not require the Android build or the dev server.

1. Install [Tampermonkey](https://www.tampermonkey.net/)
2. Import `thimbles-cup-highlighter.user.js`
3. Enable on target gaming sites

---

## 📊 Features Breakdown

### Screen Capture Module
- Captures screen frames at configurable intervals
- Processes images for AI analysis
- Optimized for performance

### Analytics Engine
- Pattern recognition and anomaly detection
- Activity timeline generation
- Statistical reports

### Tracking System
- Multi-object tracking
- Coordinate mapping
- Velocity and trajectory analysis

---

## ⚙️ Configuration

Vision tuning lives in `src/services/vision/config.js` (colour ranges, minimum
detection area, frame history, cup-tracking frame rate). `.env.example`
documents the desktop/development values. The Android APK performs capture and
analysis on-device and reads no environment file.

---

## 🧪 Testing

The JavaScript vision port is checked against an OpenCV reference:

```bash
pip install opencv-python numpy
python3 scripts/reference_tracker.py .parity
npm run test:vision
```

This writes a synthetic fixture to `.parity/` and asserts that every detection
matches `app/tracking.py` within tolerance. The `Build and Test` workflow runs
it on every push and pull request.

---

## 📖 Documentation

- [Installation Guide](./INSTALL.md)
- [API Reference](./API.md)
- [Configuration Guide](./CONFIG.md)
- [Android Build Notes](./PUBLISH.md)

---

## 🤝 Contributing

Contributions are welcome! Please:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

---

## 📜 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

## 🙋 Support

For issues, questions, or suggestions:
- Open an [Issue](https://github.com/favie1963-lgtm/Favie-X-Tracker-/issues)
- See the [Installation Guide](./INSTALL.md)
- Contact: favie1963@gmail.com

---

## 🔄 Changelog

### v1.0.0 (Initial Release)
- Basic screen tracking functionality
- AI-powered analysis
- Cup tracking integration
- Local data persistence

---

**Built with ❤️ by favie1963-lgtm**
