# Android App Setup & Publishing Guide

## 🎯 Quick Start (15 minutes)

### Prerequisites
```bash
# 1. Install Node.js (if not already installed)
# Download from https://nodejs.org/

# 2. Install Android Studio
# Download from https://developer.android.com/studio

# 3. Install JDK 11+
# Download from https://www.oracle.com/java/technologies/javase-jdk11-downloads.html
```

### Setup Steps

```bash
# 1. Navigate to app directory
cd app-native

# 2. Install dependencies
npm install

# 3. Start development
npm start

# 4. In another terminal, run on Android
npm run android
```

---

## 📦 Building for Production

### Generate Signed APK (for distribution)

**Step 1: Create Keystore (First time only)**
```bash
keytool -genkey -v -keystore favie-tracker.keystore \
  -keyalg RSA -keysize 2048 -validity 10000 -alias favie-key
```

You'll be asked for:
- Password (remember this!)
- Name: favie1963-lgtm
- Organization: Favie
- Location: Your city
- State/Province: Your state
- Country: Your country code (e.g., US)

**Step 2: Build Release APK**
```bash
cd app-native
npm run android-release
```

Output: `android/app/build/outputs/apk/release/app-release.apk`

**Step 3: Build App Bundle (for Google Play)**
```bash
npm run android-bundle
```

Output: `android/app/build/outputs/bundle/release/app-release.aab`

---

## 🚀 Publishing to Google Play Store

### Step 1: Create Google Play Developer Account
1. Go to [Google Play Console](https://play.google.com/console)
2. Pay $25 one-time registration fee
3. Complete account setup

### Step 2: Create New App
1. Click "Create app"
2. App name: "Favie X Tracker"
3. Default language: English
4. Category: Utilities
5. Content rating: Everyone

### Step 3: Upload App Bundle
1. Go to **Release** → **Production**
2. Click **Create new release**
3. Upload `app-release.aab` file
4. Add release notes:
```
v1.0.0 - Initial Release

✨ Features:
- Real-time screen tracking
- AI-powered object detection
- Cup tracking for games
- Advanced analytics & reports
- Live video feed
- Detailed metrics dashboard
```

### Step 4: Complete Store Listing
1. **App Details**
   - Title: Favie X Tracker
   - Short description: Real-time screen tracking with AI analysis
   - Full description:
   ```
   Favie X Tracker is a powerful mobile app for real-time screen monitoring and analysis.
   
   Features:
   🎥 Live screen capture and tracking
   🧠 AI-powered object detection
   🎯 Specialized cup/game tracking
   📊 Advanced analytics and reports
   🔒 All data stored locally - privacy first
   ⚡ High-performance tracking
   
   Perfect for:
   - Game analysis
   - Screen monitoring
   - Object tracking
   - Activity analysis
   ```

2. **Screenshots** (5-8 required)
   - Dashboard screenshot
   - Tracking screen
   - Analytics screen
   - Features overview

3. **App Icon** (512x512)
   - Add `icon.png` to `app-native/assets/`

4. **Feature Graphic** (1024x500)
   - Banner image showcasing app

### Step 5: Content Rating Questionnaire
1. Complete IARC form
2. Submit questionnaire

### Step 6: Privacy Policy
Add privacy policy URL (required)
```
https://github.com/favie1963-lgtm/Favie-X-Tracker-/blob/main/PRIVACY.md
```

### Step 7: Target Audience
- Set appropriate age rating
- Select target regions

### Step 8: Submit for Review
1. Review all information
2. Click "Submit app"
3. Google Play will review (usually 24-48 hours)
4. App goes live!

---

## 📥 Alternative: Direct APK Distribution

If you don't want to use Google Play Store:

### Upload to GitHub Releases
```bash
# Create release tag
git tag -a android-v1.0.0 -m "Android release"
git push origin android-v1.0.0

# Upload APK to GitHub Releases
gh release create android-v1.0.0 \
  app-native/android/app/build/outputs/apk/release/app-release.apk
```

### Upload to Other Platforms
- **AppBrain**: https://www.appbrain.com/
- **Aptoide**: https://www.aptoide.com/
- **F-Droid**: https://f-droid.org/ (for open-source)

---

## 🔐 Security Best Practices

### Signing Key Management
```bash
# Store keystore safely
mv favie-tracker.keystore ~/secure/favie-tracker.keystore
chmod 600 ~/secure/favie-tracker.keystore

# Never commit keystore to Git!
# Add to .gitignore:
echo "*.keystore" >> .gitignore
```

### Store Passwords Safely
```bash
# Create gradle.properties (add to .gitignore)
echo "
STORE_FILE=path/to/favie-tracker.keystore
STORE_PASSWORD=your_password
KEY_ALIAS=favie-key
KEY_PASSWORD=your_key_password
" >> android/gradle.properties
```

---

## 📊 Version Management

Update version in `app-native/package.json`:
```json
{
  "version": "1.0.0"
}
```

Also update in `android/app/build.gradle`:
```gradle
versionCode 1
versionName "1.0.0"
```

---

## ❓ Troubleshooting

### "Android SDK not found"
```bash
# Set ANDROID_HOME
export ANDROID_HOME=~/Library/Android/sdk  # macOS
export ANDROID_HOME=~/Android/Sdk           # Linux
set ANDROID_HOME=%USERPROFILE%\AppData\Local\Android\sdk  # Windows
```

### "Gradle build failed"
```bash
# Clean and rebuild
cd android
./gradlew clean
./gradlew assembleRelease
```

### "App won't connect to backend"
- Ensure Python backend is running on port 5000
- Check firewall settings
- Verify Android device can reach localhost

### "Camera permissions denied"
- Check `AndroidManifest.xml` permissions
- Request runtime permissions in app

---

## 🎯 Final Checklist

- [ ] App builds successfully
- [ ] All features working on test device
- [ ] Backend server running and accessible
- [ ] Privacy policy created
- [ ] App icon and screenshots ready
- [ ] Version number updated
- [ ] Signing key generated
- [ ] Release APK/AAB built
- [ ] Google Play account created
- [ ] Store listing completed
- [ ] Submitted for review

---

## 📈 After Launch

### Monitor Performance
- Check Google Play Console for crashes
- Read user reviews and feedback
- Monitor download numbers
- Track user engagement

### Update Strategy
```bash
# For each update:
# 1. Update version numbers
# 2. Add release notes
# 3. Build new release
# 4. Upload to Play Store
# 5. Monitor for issues
```

---

## 🔗 Useful Links

- [Google Play Console](https://play.google.com/console)
- [Android Documentation](https://developer.android.com/docs)
- [React Native Docs](https://reactnative.dev/)
- [Publishing Guide](https://developer.android.com/studio/publish)
- [App Signing Overview](https://developer.android.com/studio/publish/app-signing)

---

**Ready to launch? Start with Step 1 above!** 🚀
