# Favie X Tracker - Direct Download APK Guide

## 🚀 Build APK for Direct Download (NOT Google Play)

Since you want to distribute via direct download link, here's the complete guide:

---

## 📦 Step 1: Build Release APK

### On Your Computer:

```bash
cd app-native
npm install
```

### Generate Signing Key (First time only):

```bash
keytool -genkey -v -keystore favie-tracker.keystore \
  -keyalg RSA -keysize 2048 -validity 10000 -alias favie-key
```

Answer prompts:
- **Password**: Create a strong password (remember it!)
- **Name**: favie1963-lgtm
- **Organization**: Favie
- **City**: Your city
- **State**: Your state
- **Country**: US (or your country code)

### Build Release APK:

```bash
cd android
./gradlew assembleRelease
```

Output location:
```
app-native/android/app/build/outputs/apk/release/app-release.apk
```

---

## 🌐 Step 2: Host the APK for Download

### Option A: GitHub Releases (RECOMMENDED & FREE)

```bash
# Create a new release
git tag -a android-v1.0.0 -m "Favie X Tracker APK Release"
git push origin android-v1.0.0
```

Then:
1. Go to: https://github.com/favie1963-lgtm/Favie-X-Tracker-/releases
2. Click **"Create a new release"**
3. Select tag: **android-v1.0.0**
4. Title: **Favie X Tracker v1.0.0 - Android APK**
5. Description:
```
📱 Direct Download APK

🎯 Features:
✅ Real-time screen tracking
✅ AI-powered object detection
✅ Floating overlay monitoring
✅ Cup/ball tracking
✅ Background service
✅ Local data storage

📥 Installation:
1. Download the APK file below
2. Go to Settings > Security > Unknown Sources (Enable)
3. Open the downloaded APK file
4. Tap "Install"
5. Grant permissions when prompted
6. Enjoy!

⚠️ Permissions Required:
- Camera - For screen monitoring
- Accessibility Service - For app tracking
- Storage - For data storage
- Overlay - For floating window

🔗 Direct Download Link:
https://github.com/favie1963-lgtm/Favie-X-Tracker-/releases/download/android-v1.0.0/app-release.apk
```
6. Upload: `app-native/android/app/build/outputs/apk/release/app-release.apk`
7. Click **"Publish release"**

**Your download link will be:**
```
https://github.com/favie1963-lgtm/Favie-X-Tracker-/releases/download/android-v1.0.0/app-release.apk
```

### Option B: DropBox / Google Drive

1. Upload APK to your cloud storage
2. Get public sharing link
3. Share with users

### Option C: Personal Website

1. Upload APK to your server
2. Create download page
3. Share link

---

## 📥 User Installation Instructions

Share this with users who download:

### For Android Users:

**Step 1: Enable Unknown Sources**
- Go to `Settings` → `Security`
- Toggle ON: "Unknown Sources" or "Install from unknown sources"

**Step 2: Download APK**
- Click your download link
- APK file downloads

**Step 3: Install**
- Open Downloads folder
- Tap `app-release.apk`
- Tap "Install"
- Wait for installation

**Step 4: Grant Permissions**
- Open "Favie X Tracker"
- Allow Camera, Storage, Accessibility Service
- Go to Settings > Accessibility Services > Favie X Tracker > Enable

**Step 5: Enable Overlay**
- Settings > Apps > Favie X Tracker
- Tap "Display over other apps"
- Toggle ON

**Step 6: Start Tracking**
- Open app
- Tap "Start Tracking"
- Floating overlay appears on screen!

---

## 📊 What Users Get

✅ **No App Store approval needed**
✅ **Direct download via simple link**
✅ **Floating overlay stays visible**
✅ **Monitors all apps**
✅ **Tracks object movement**
✅ **Real-time analytics**
✅ **Works offline**
✅ **No data sent to servers**

---

## 🔄 Updates

To release a new version:

```bash
# Update version in package.json and build.gradle
# Rebuild APK
cd android && ./gradlew assembleRelease

# Create new release tag
git tag -a android-v1.0.1 -m "Favie X Tracker v1.0.1"
git push origin android-v1.0.1

# Upload new APK to releases
```

---

## ⚙️ Customization

### Change App Name:
`android/app/src/main/res/values/strings.xml`
```xml
<string name="app_name">Your App Name</string>
```

### Change Package Name:
`android/app/build.gradle`
```gradle
applicationId "com.yourname.tracker"
```

### Change App Icon:
Replace: `android/app/src/main/res/mipmap-*/ic_launcher.png`

---

## 🎯 Direct Link Format

Once hosted on GitHub Releases:
```
https://github.com/favie1963-lgtm/Favie-X-Tracker-/releases/download/android-v1.0.0/app-release.apk
```

Users simply click and download!

---

## ✅ Complete Checklist

- [ ] APK built successfully
- [ ] Signing key generated and secured
- [ ] Release APK created
- [ ] GitHub release created
- [ ] APK uploaded to release
- [ ] Download link tested
- [ ] Installation instructions prepared
- [ ] Shared with users

---

## 🚀 QUICK START (Today!)

```bash
# 1. Build
cd app-native/android
./gradlew assembleRelease

# 2. Find APK
ls app/build/outputs/apk/release/app-release.apk

# 3. Create GitHub release and upload APK

# 4. Share download link with users!
```

**That's it! Users can download and install directly.** 🎉
