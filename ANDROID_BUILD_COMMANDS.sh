#!/bin/bash
# Favie X Tracker - Android Build Commands

echo "🚀 Favie X Tracker - Android Build Script"
echo "=========================================="

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Check if in correct directory
if [ ! -d "app-native" ]; then
    echo "❌ Error: app-native directory not found"
    echo "Run this script from the root directory"
    exit 1
fi

cd app-native

echo -e "${BLUE}Step 1: Installing dependencies...${NC}"
npm install

echo -e "${BLUE}Step 2: Checking Java installation...${NC}"
java -version

echo -e "${BLUE}Step 3: Checking Android SDK...${NC}"
echo "ANDROID_HOME: $ANDROID_HOME"

echo -e "${GREEN}✅ Setup complete!${NC}"
echo ""
echo "Next steps:"
echo "  1. Start Metro bundler: npm start"
echo "  2. In another terminal: npm run android"
echo ""
echo "To build release APK:"
echo "  npm run android-release"
echo ""
echo "To build for Google Play:"
echo "  npm run android-bundle"
