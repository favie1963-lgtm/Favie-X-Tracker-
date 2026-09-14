# Favie X Tracker

🎯 A powerful desktop application for real-time screen tracking with AI-powered analysis and specialized object detection.

## ✨ Features

- 🎥 **Real-time Screen Capture** - Continuous monitoring at configurable intervals
- 👁️ **Object Detection** - Color-based detection for cups, balls, and other game objects
- 🎮 **Cup Tracking** - Specialized tracking for Thimbles-style games
- 📊 **Advanced Analytics** - Motion detection, brightness analysis, color distribution
- 📈 **Reports & Visualization** - Comprehensive analytics with charts and trends
- 💾 **Local Storage** - All data stored locally with full privacy
- 🖥️ **Desktop App** - Electron-based cross-platform application
- ⚡ **High Performance** - Optimized Python backend with Flask API

## 🚀 Quick Start

### Requirements
- Python 3.8+
- Node.js 14+
- Git

### Installation

**On macOS/Linux:**
```bash
git clone https://github.com/favie1963-lgtm/Favie-X-Tracker-.git
cd Favie-X-Tracker-
./setup.sh
npm run dev
```

**On Windows:**
```bash
git clone https://github.com/favie1963-lgtm/Favie-X-Tracker-.git
cd Favie-X-Tracker-
setup.bat
npm run dev
```

For detailed instructions, see [INSTALL.md](INSTALL.md)

## 📋 Usage

### Development
```bash
# Start with hot reload
npm run dev

# Or run components separately
npm run backend        # Python backend only
npm run react-start    # React frontend only
```

### Production
```bash
# Build desktop app
npm run build

# Or run the built app
npm start
```

## 🎮 Using the App

### Dashboard Tab
- **Start/Stop Tracking** - Control screen capture
- **Real-time Status** - Monitor FPS, frames, and objects
- **Detected Objects** - View all tracked objects with details

### Tracking Tab
- **Live Feed** - See real-time screen capture
- **Auto-refresh** - Updates every 500ms
- **Full Resolution** - Captures at native screen resolution

### Analytics Tab
- **Session Metrics** - Duration, frames analyzed, objects detected
- **Charts** - Detection trends, color distribution
- **Detailed Reports** - Motion levels, brightness, and more

## 🔧 Configuration

Edit `.env` file:
```env
CAPTURE_INTERVAL=500          # Milliseconds between captures
RESOLUTION=1920x1080          # Screen resolution
ENABLE_AI_ANALYSIS=true       # Enable analytics features
STORAGE_PATH=./data           # Data storage location
DEBUG_MODE=false              # Enable debug logging
```

## 📚 Documentation

- [Installation Guide](INSTALL.md) - Detailed setup instructions
- [API Reference](API.md) - REST API endpoints and examples
- [Configuration Guide](CONFIG.md) - Customization options

## 🏗️ Architecture

### Backend (Python)
- **Flask REST API** - Server on port 5000
- **Screen Capture** - Real-time frame acquisition via `mss`
- **Object Detection** - OpenCV-based color and edge detection
- **Analytics Engine** - Pattern recognition and statistical analysis
- **Data Storage** - JSON-based local storage

### Frontend (React + Electron)
- **React Components** - Dashboard, Tracking, Analytics
- **Electron** - Cross-platform desktop app wrapper
- **Charts** - Recharts for data visualization
- **IPC Bridge** - Secure frontend-backend communication

## 📁 Project Structure

```
Favie-X-Tracker-/
├── app/                    # Python backend
│   ├── main.py            # Flask server (port 5000)
│   ├── screen_capture.py  # Real-time frame capture
│   ├── analytics.py       # Analytics & reporting
│   └── tracking.py        # Object detection & tracking
├── electron/              # Electron desktop app
│   ├── main.js           # Electron main process
│   └── preload.js        # IPC bridge
├── src/                   # React frontend
│   ├── components/       # Dashboard, Tracking, Analytics
│   ├── App.js           # Main application
│   └── index.js         # React entry point
├── public/               # Static assets
├── data/                 # Analytics data (created at runtime)
├── package.json          # Node.js dependencies
├── requirements.txt      # Python dependencies
└── .env                  # Configuration
```

## 🛠️ Troubleshooting

### Backend won't start
```bash
# Check Python installation
python3 --version

# Check if port 5000 is in use
lsof -i :5000

# Reinstall dependencies
pip install -r requirements.txt
```

### Screen capture permissions (macOS)
- System Preferences → Security & Privacy → Screen Recording
- Add Terminal or your app to the list

### Screen capture permissions (Linux)
```bash
# Install required packages
sudo apt-get install python3-tk python3-dev
```

## 🤝 Contributing

Contributions welcome! Please:
1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit changes (`git commit -m 'Add amazing feature'`)
4. Push to branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## 📄 License

MIT License - See [LICENSE](LICENSE) file for details

## 📧 Support

- **Email**: favie1963@gmail.com
- **Issues**: [GitHub Issues](https://github.com/favie1963-lgtm/Favie-X-Tracker-/issues)
- **Discussions**: [GitHub Discussions](https://github.com/favie1963-lgtm/Favie-X-Tracker-/discussions)

---

**Built with ❤️ by favie1963-lgtm**

Version 1.0.0 | Updated September 2026
