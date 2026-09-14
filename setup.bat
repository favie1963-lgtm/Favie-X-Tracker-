@echo off
REM Favie X Tracker - Quick Start Setup for Windows

echo 🚀 Favie X Tracker - Setup Script (Windows)
echo =========================================

REM Check Python
echo ✓ Checking Python...
python --version >nul 2>&1
if errorlevel 1 (
    echo ❌ Python not found. Please install Python 3.8+
    exit /b 1
)

REM Check Node.js
echo ✓ Checking Node.js...
node --version >nul 2>&1
if errorlevel 1 (
    echo ❌ Node.js not found. Please install Node.js 14+
    exit /b 1
)

REM Create virtual environment
echo ✓ Creating Python virtual environment...
python -m venv venv
call venv\Scripts\activate.bat

REM Install Python dependencies
echo ✓ Installing Python dependencies...
pip install -r requirements.txt

REM Install Node dependencies
echo ✓ Installing Node.js dependencies...
npm install

REM Create .env file
echo ✓ Creating .env file...
if not exist .env (
    copy .env.example .env
    echo   .env file created. Update with your settings if needed.
)

REM Create data directory
if not exist data mkdir data
if not exist tmp mkdir tmp

echo.
echo ✅ Setup complete!
echo.
echo 📖 Next steps:
echo   1. Review and update .env file if needed
echo   2. Run: npm run dev (for development)
echo   3. Or run: npm run build (to create production build)
echo.
echo 💡 Commands:
echo   npm run dev          - Start development mode
echo   npm run backend      - Start Python backend only
echo   npm run electron     - Start Electron app only
echo   npm run build        - Create production build
echo.
pause
