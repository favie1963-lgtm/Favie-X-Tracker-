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

- **Python 3.8+** (for backend)
- **Node.js 14+** (for app framework - optional)
- **macOS / Windows / Linux**

### Installation

1. **Clone the repository**
   ```bash
   git clone https://github.com/favie1963-lgtm/Favie-X-Tracker-.git
   cd Favie-X-Tracker-
   ```

2. **Install dependencies**
   ```bash
   pip install -r requirements.txt
   npm install  # if using Node.js
   ```

3. **Run the application**
   ```bash
   python app.py
   ```

---

## 📁 Project Structure

```
Favie-X-Tracker-/
├── app/
│   ├── main.py              # Main application entry point
│   ├── screen_capture.py    # Screen capture module
│   └── analytics.py         # Analytics engine
├── src/
│   ├── logic.js             # Core game/tracking logic
│   ├── tracking.js          # Object tracking system
│   ├── simulator.js         # Simulation engine
│   └── thimbles-cup-highlighter.user.js  # Browser integration
├── data/                    # Local data storage
├── tests/                   # Unit tests
├── requirements.txt         # Python dependencies
├── package.json            # Node.js dependencies
└── README.md
```

---

## 🎮 Usage

### Basic Screen Tracking

```python
from app.screen_capture import ScreenTracker

tracker = ScreenTracker()
tracker.start()
# App will run in background, capturing and analyzing screen activity
```

### Cup/Object Tracking (Thimbles)

The app includes specialized tracking for cup-based games:
- Real-time cup position detection
- Ball movement prediction
- Probability analysis

### Tampermonkey Integration

For browser-based tracking:
1. Install [Tampermonkey](https://www.tampermonkey.net/)
2. Import `src/thimbles-cup-highlighter.user.js`
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

Create a `config.json` file to customize:

```json
{
  "capture_interval": 1000,
  "resolution": "1920x1080",
  "output_format": "json",
  "enable_ai_analysis": true,
  "storage_path": "./data"
}
```

---

## 🧪 Testing

```bash
pytest tests/
```

---

## 📖 Documentation

- [Installation Guide](./docs/INSTALL.md)
- [API Reference](./docs/API.md)
- [Configuration Guide](./docs/CONFIG.md)
- [Troubleshooting](./docs/TROUBLESHOOTING.md)

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
- Check [Documentation](./docs/)
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
