# Configuration Guide

## Environment Variables

Create a `.env` file in the root directory:

```env
# Capture Settings
CAPTURE_INTERVAL=500              # Time between frames (ms)
RESOLUTION=1920x1080              # Screen resolution
OUTPUT_FORMAT=json                # Data format

# Features
ENABLE_AI_ANALYSIS=true           # Enable analytics features
DEBUG_MODE=false                  # Enable debug logging

# Storage
STORAGE_PATH=./data               # Where to save data

# Server
FLASK_ENV=development             # Flask environment
FLASK_PORT=5000                   # Backend port
```

## Capture Settings

### CAPTURE_INTERVAL (milliseconds)
- **Default**: 500ms (2 FPS)
- **Range**: 100ms - 5000ms
- **Lower** = Higher FPS, more CPU usage
- **Higher** = Lower FPS, less CPU usage

Example:
```env
CAPTURE_INTERVAL=1000  # 1 FPS (1 frame per second)
CAPTURE_INTERVAL=100   # 10 FPS (10 frames per second)
```

### RESOLUTION
- **Default**: 1920x1080
- **Format**: WIDTHxHEIGHT
- Auto-detects primary monitor if not set

Example:
```env
RESOLUTION=1280x720    # 720p
RESOLUTION=3840x2160   # 4K
```

## Feature Flags

### ENABLE_AI_ANALYSIS
- **true**: Runs analytics on each frame (more CPU usage)
- **false**: Captures only, no analysis

```env
ENABLE_AI_ANALYSIS=true   # Default: enabled
```

### DEBUG_MODE
- **true**: Verbose logging for troubleshooting
- **false**: Production logging

```env
DEBUG_MODE=false  # Default: disabled
```

## Storage Configuration

### STORAGE_PATH
- **Default**: ./data
- Location where analytics and reports are saved
- Created automatically if doesn't exist

```env
STORAGE_PATH=./data              # Relative path
STORAGE_PATH=/var/favie/data     # Absolute path
```

## Performance Tuning

### For Lower CPU Usage
```env
CAPTURE_INTERVAL=2000            # Lower FPS
ENABLE_AI_ANALYSIS=false         # Disable analytics
DEBUG_MODE=false                 # Disable logging
```

### For Better Accuracy
```env
CAPTURE_INTERVAL=100             # Higher FPS
ENABLE_AI_ANALYSIS=true          # Enable all features
DEBUG_MODE=false                 # Production mode
```

### Balanced (Recommended)
```env
CAPTURE_INTERVAL=500             # 2 FPS
ENABLE_AI_ANALYSIS=true          # Full features
DEBUG_MODE=false                 # Production
```

## Runtime Configuration

You can also change settings via API:

```bash
# Get current config
curl http://localhost:5000/api/config

# Update config
curl -X POST http://localhost:5000/api/config \
  -H "Content-Type: application/json" \
  -d '{"capture_interval": 1000}'
```

## Troubleshooting

### High CPU Usage
1. Increase CAPTURE_INTERVAL (e.g., 1000ms)
2. Disable ENABLE_AI_ANALYSIS
3. Lower RESOLUTION if possible

### Frame Drops / Low FPS
1. Check CPU usage: `top` or Task Manager
2. Reduce background applications
3. Increase CAPTURE_INTERVAL

### Storage Issues
1. Check disk space
2. Verify STORAGE_PATH exists and is writable
3. Clear old data files in storage directory

### Performance Issues on Linux
1. Install required libraries:
   ```bash
   sudo apt-get install python3-tk libsm6 libxext6
   ```
2. Try different screen capture methods in `screen_capture.py`

## Platform-Specific Settings

### macOS
- Grant screen recording permissions in System Preferences
- Higher CAPTURE_INTERVAL may be needed (500-1000ms)

### Windows
- Run as Administrator for best results
- CAPTURE_INTERVAL can be lower (100-500ms)

### Linux
- Install required development libraries
- May need Wayland workarounds for screen capture
- Lower performance than macOS/Windows

## Default Configuration File

The app comes with `.env.example`. Copy it to `.env` and customize:

```bash
cp .env.example .env
```

Then edit with your preferred settings.
