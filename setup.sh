#!/bin/bash
# Favie X Tracker - Quick Start Setup

echo "🚀 Favie X Tracker - Setup Script"
echo "================================="

# Check Python
echo "✓ Checking Python..."
if ! command -v python3 &> /dev/null; then
    echo "❌ Python 3 not found. Please install Python 3.8+"
    exit 1
fi

# Check Node.js
echo "✓ Checking Node.js..."
if ! command -v node &> /dev/null; then
    echo "❌ Node.js not found. Please install Node.js 14+"
    exit 1
fi

# Create virtual environment
echo "✓ Creating Python virtual environment..."
python3 -m venv venv
source venv/bin/activate || . venv/Scripts/activate

# Install Python dependencies
echo "✓ Installing Python dependencies..."
pip install -r requirements.txt

# Install Node dependencies
echo "✓ Installing Node.js dependencies..."
npm install

# Create .env file
echo "✓ Creating .env file..."
if [ ! -f .env ]; then
    cp .env.example .env
    echo "  .env file created. Update with your settings if needed."
fi

# Create data directory
mkdir -p data tmp

echo ""
echo "✅ Setup complete!"
echo ""
echo "📖 Next steps:"
echo "  1. Review and update .env file if needed"
echo "  2. Run: npm run dev (for development)"
echo "  3. Or run: npm run build (to create production build)"
echo ""
echo "💡 Commands:"
echo "  npm run dev          - Start development mode"
echo "  npm run backend      - Start Python backend only"
echo "  npm run electron     - Start Electron app only"
echo "  npm run build        - Create production build"
echo ""
