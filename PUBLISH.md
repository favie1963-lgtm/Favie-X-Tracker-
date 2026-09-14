# Publishing Guide - Favie X Tracker

## 📦 Option 1: Build Standalone Desktop Executables (RECOMMENDED)

This creates .exe (Windows), .dmg (macOS), and .AppImage (Linux) files.

### Prerequisites
```bash
# Make sure you're in the repo root
cd Favie-X-Tracker-

# Install dependencies
npm install
pip install -r requirements.txt
```

### Build for Your Platform

#### Windows (.exe)
```bash
npm run build
```
Output: `dist/Favie X Tracker Setup 1.0.0.exe`

#### macOS (.dmg)
```bash
npm run build
```
Output: `dist/Favie X Tracker-1.0.0.dmg`

#### Linux (.AppImage)
```bash
npm run build
```
Output: `dist/Favie X Tracker-1.0.0.AppImage`

---

## 📤 Option 2: Publish to GitHub Releases (FREE)

Make your app available for download from your GitHub repo.

### Step 1: Create a Release
```bash
# Commit your changes (if any)
git add .
git commit -m "v1.0.0 - Initial Release"

# Create a tag
git tag -a v1.0.0 -m "First production release"

# Push to GitHub
git push origin main
git push origin v1.0.0
```

### Step 2: Build Executables
```bash
npm run build
```

### Step 3: Upload to GitHub Releases

**Option A: Via GitHub Web UI**
1. Go to https://github.com/favie1963-lgtm/Favie-X-Tracker-
2. Click **Releases** (right sidebar)
3. Click **Create a new release**
4. Select tag: `v1.0.0`
5. Title: `Favie X Tracker v1.0.0`
6. Add description:
```
🎉 First Release

## Features
- Real-time screen tracking
- AI-powered object detection
- Cup tracking for games
- Advanced analytics & reports
- Desktop app (Windows, macOS, Linux)

## Downloads
- Windows: Favie X Tracker Setup 1.0.0.exe
- macOS: Favie X Tracker-1.0.0.dmg
- Linux: Favie X Tracker-1.0.0.AppImage
```
7. Upload files from `dist/` folder
8. Click **Publish release**

**Option B: Via GitHub CLI**
```bash
npm run build

gh release create v1.0.0 \
  --title "Favie X Tracker v1.0.0" \
  --notes "Initial release with screen tracking and analytics" \
  dist/*
```

---

## 🌐 Option 3: Publish to App Stores

### Windows Store (Microsoft Store)
1. Get a Microsoft Partner account
2. Join Microsoft Partner Center
3. Submit your app (requires review, ~24-48 hours)
4. Use Windows App Packaging Project in Electron-builder

### macOS App Store
1. Create Apple Developer account ($99/year)
2. Generate certificates for code signing
3. Configure in `package.json`:
```json
{
  "build": {
    "appId": "com.favie.tracker",
    "mac": {
      "certificateFile": "path/to/certificate.p12",
      "certificatePassword": "${CSC_KEY_PASSWORD}"
    }
  }
}
```
4. Submit via App Store Connect

### Snap Store (Linux)
```bash
# Install snapcraft
sudo apt install snapcraft

# Create snap
snapcraft

# Upload
snapcraft upload favie-x-tracker_1.0.0_amd64.snap
```

---

## 🚀 Option 4: Publish via Package Managers

### Homebrew (macOS)
1. Create a tap: `homebrew-favie-tracker`
2. Add formula file
3. Users install: `brew install favie1963-lgtm/favie-tracker/favie-x-tracker`

### Chocolatey (Windows)
1. Create package
2. Submit to Chocolatey
3. Users install: `choco install favie-x-tracker`

### Snap (Linux)
```bash
snapcraft push favie-x-tracker_1.0.0_amd64.snap --release=stable
```

---

## 🔐 Code Signing (For Distribution)

### macOS Code Signing
```bash
# Generate certificate (requires Apple Developer account)
# Then configure in electron-builder

npm run build -- --mac
```

### Windows Code Signing
```bash
# Get signing certificate
# Configure in electron-builder

npm run build -- --win
```

---

## 📝 Update Your package.json for Publishing

Before building, update this section:

```json
{
  "name": "favie-x-tracker",
  "version": "1.0.0",
  "description": "Screen tracking with AI analysis and cup detection",
  "author": "favie1963-lgtm <favie1963@gmail.com>",
  "homepage": "https://github.com/favie1963-lgtm/Favie-X-Tracker-",
  "repository": {
    "type": "git",
    "url": "https://github.com/favie1963-lgtm/Favie-X-Tracker-"
  },
  "build": {
    "appId": "com.favie.tracker",
    "productName": "Favie X Tracker",
    "files": [
      "build/**/*",
      "electron/**/*",
      "node_modules/**/*"
    ],
    "directories": {
      "buildResources": "assets"
    },
    "win": {
      "target": ["nsis", "portable"]
    },
    "nsis": {
      "oneClick": false,
      "allowToChangeInstallationDirectory": true
    },
    "mac": {
      "target": ["dmg", "zip"]
    },
    "linux": {
      "target": ["AppImage", "deb"]
    }
  }
}
```

---

## 🎯 Recommended Publishing Path (TODAY!)

### Step 1: Build Executables (10 minutes)
```bash
npm run build
```

### Step 2: Create GitHub Release (5 minutes)
```bash
git tag -a v1.0.0 -m "First release"
git push origin v1.0.0
```

Then go to: https://github.com/favie1963-lgtm/Favie-X-Tracker-/releases
- Click "Create release"
- Upload the files from `dist/` folder
- Publish!

### Step 3: Share Download Link
Share: `https://github.com/favie1963-lgtm/Favie-X-Tracker-/releases/tag/v1.0.0`

Users can download and run directly!

---

## 📊 Distribution Comparison

| Method | Time | Cost | Users | Support |
|--------|------|------|-------|---------|
| **GitHub Releases** | 15 min | FREE | Tech-savvy | Direct link |
| **Windows Installer** | 30 min | FREE | Windows users | .exe installer |
| **App Stores** | 1-2 days | $99-299 | All users | Official store |
| **Homebrew** | 1 hour | FREE | macOS users | `brew install` |
| **Chocolatey** | 1 hour | FREE | Windows users | `choco install` |

---

## 🔄 Continuous Updates

### Auto-Updates Setup
```bash
# Install electron-updater
npm install electron-updater

# In electron/main.js:
const { autoUpdater } = require('electron-updater');
autoUpdater.checkForUpdatesAndNotify();
```

Then users get updates automatically!

---

## 🎁 Extra: Create an Installer Wizard

Your current build already includes:
- ✅ Auto-installer (NSIS for Windows)
- ✅ DMG installer (macOS)
- ✅ AppImage (Linux)

Just run: `npm run build`

---

## ❓ FAQ

**Q: Do I need to sign my app?**
A: For public distribution, yes (but optional for GitHub releases)

**Q: How do I update users?**
A: Use electron-updater for automatic updates

**Q: Can I sell it?**
A: Yes! Change license from MIT to commercial

**Q: How many downloads can GitHub handle?**
A: Unlimited!

---

## ✅ PUBLISH TODAY - QUICK SUMMARY

```bash
# 1. Build executables
npm run build

# 2. Create GitHub release tag
git tag -a v1.0.0 -m "Initial release"
git push origin v1.0.0

# 3. Go to GitHub and upload files from dist/ folder

# 4. Share the release link!
# https://github.com/favie1963-lgtm/Favie-X-Tracker-/releases
```

**That's it! Your app is live!** 🚀
