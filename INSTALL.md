# Favie X Tracker - Installation & Setup Guide

## 🚀 Quick Start

### Prerequisites
- **Python 3.8+** - [Download](https://www.python.org/downloads/)
- **Node.js 14+** - [Download](https://nodejs.org/)
- **Git** - [Download](https://git-scm.com/)

### Installation

**On macOS/Linux:**
```bash
# Clone repository
git clone https://github.com/favie1963-lgtm/Favie-X-Tracker-.git
cd Favie-X-Tracker-

# Run setup script
chmod +x setup.sh
./setup.sh

# Start development
npm run dev
```

**On Windows:**
```bash
# Clone repository
git clone https://github.com/favie1963-lgtm/Favie-X-Tracker-.git
cd Favie-X-Tracker-

# Run setup script
setup.bat

# Start development
npm run dev
```

### Manual Installation (if setup script fails)

```bash
# Create Python virtual environment
python3 -m venv venv
source venv/bin/activate  # On Windows: venv\Scripts\activate

# Install Python dependencies
pip install -r requirements.txt

# Install Node dependencies
npm install

# Create .env file
cp .env.example .env

# Create directories
mkdir -p data tmp
```

## 📖 Running the Application

### Development Mode
```bash
npm run dev
```
This starts both the Python backend and Electron app with hot reload.

### Production Build
```bash
npm run build
```
Creates an executable for your platform.

### Backend Only
```bash
npm run backend
```
Starts just the Python Flask server on `http://localhost:5000`

### Frontend Only
```bash
npm run react-start
```
Starts React dev server on `http://localhost:3000`

## ⚙️ Configuration

Edit `.env` file to customize:

```env
CAPTURE_INTERVAL=500          # Milliseconds between captures
RESOLUTION=1920x1080          # Screen resolution
OUTPUT_FORMAT=json            # JSON or other formats
ENABLE_AI_ANALYSIS=true       # Enable AI features
STORAGE_PATH=./data           # Where to store data
DEBUG_MODE=false              # Enable debug logging
```

## 🎯 Features

### 🎥 Real-time Screen Capture
- Continuous screen monitoring
- Configurable capture intervals
- Frame optimization for performance

### 👁️ Object Detection
- Color-based detection (Red, Blue, Yellow, Green)
- Cup/object tracking for games
- Circularity analysis
- Confidence scoring

### 📊 Analytics
- Motion detection
- Brightness analysis
- Color distribution
- Detection trends
- Session reports

### 🎮 Dashboard
- Live tracking controls
- Real-time status display
- Object list with properties
- Interactive controls

### 📈 Reports
- Comprehensive analytics
- Session summaries
- Trend visualization
- Export capabilities

## 🔧 Troubleshooting

### "Python not found"
- Install Python 3.8+ and add to PATH
- Verify: `python3 --version`

### "Node not found"
- Install Node.js 14+
- Verify: `node --version`

### "Backend connection failed"
- Make sure backend is running on port 5000
- Check Python process: `ps aux | grep python`
- Restart: Stop and run `npm run dev` again

### "Screen capture not working"
- Ensure you have screen capture permissions
- On macOS: System Preferences > Security & Privacy > Screen Recording
- On Windows: Run as Administrator
- On Linux: Install required libraries

### "Port 5000 already in use"
- Change port in `.env`: `FLASK_PORT=5001`
- Or kill existing process: `lsof -ti:5000 | xargs kill -9`

## 📁 Project Structure

```
Favie-X-Tracker-/
├── app/                    # Python backend
│   ├── main.py            # Flask server
│   ├── screen_capture.py  # Screen capture module
│   ├── analytics.py       # Analytics engine
│   └── tracking.py        # Object tracking
├── electron/              # Electron desktop app
│   ├── main.js           # Electron main process
│   └── preload.js        # IPC bridge
├── src/                   # React frontend
│   ├── components/       # UI components
│   ├── App.js           # Main app
│   └── index.js         # Entry point
├── public/               # Static assets
├── data/                 # Analytics data storage
├── package.json          # Node dependencies
├── requirements.txt      # Python dependencies
└── .env                  # Environment config
```

## 🤝 Support

- 📧 Email: favie1963@gmail.com
- 🐛 Issues: [GitHub Issues](https://github.com/favie1963-lgtm/Favie-X-Tracker-/issues)
- 💬 Discussions: [GitHub Discussions](https://github.com/favie1963-lgtm/Favie-X-Tracker-/discussions)

## 📜 License

MIT License - See LICENSE file for details
