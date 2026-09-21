# Advanced Object Tracking Guide

## 🎯 What This App Does

The enhanced Favie X Tracker can now:

✅ **Detect & Track Objects** (cups, balls, etc.)
✅ **Calculate Real-time Speed** (pixels/second)
✅ **Measure Velocity Vector** (x and y direction)
✅ **Predict Movement** (where object will be in 500ms)
✅ **Track Trajectory** (movement history)
✅ **Show on Floating Overlay** (draggable, always visible)
✅ **Works Across All Apps** (games, apps, websites)

---

## 🎮 How to Use

### Step 1: Install & Open App
1. Download APK and install
2. Grant all permissions
3. Open "Favie X Tracker"

### Step 2: Enable Overlay Service
1. Go to Settings > Apps > Favie X Tracker
2. Tap "Display over other apps" > ON
3. Go to Settings > Accessibility > Favie X Tracker > ON

### Step 3: Start Tracking
1. Open the app
2. Tap "Start Tracking"
3. A floating overlay appears

### Step 4: Select Object
1. Open any game/app with objects (cups, balls, etc.)
2. The overlay detects objects automatically
3. Tap an object to select it
4. The overlay now tracks ONLY that object

### Step 5: Monitor Tracking Data

The overlay shows:
```
Favie Tracker
⬆️ (Direction arrow)
Speed: 245.3 px/s
Pos: (340, 210)
Dir: UP-LEFT
```

**Meanings:**
- **⬆️** = Direction (UP, DOWN, LEFT, RIGHT, etc.)
- **Speed** = How fast the object is moving
- **Pos** = Current position on screen
- **Dir** = Combined direction

### Step 6: Stop Tracking
- Tap "Stop Tracking" button on overlay
- Or swipe the overlay away

---

## 📊 Tracked Data

### Real-time Measurements

| Metric | Meaning |
|--------|----------|
| **Speed (px/s)** | Pixels moved per second |
| **Velocity** | Speed + Direction |
| **Position** | X, Y coordinates on screen |
| **Direction** | UP, DOWN, LEFT, RIGHT, etc. |
| **Predicted Pos** | Where it will be in 500ms |
| **Trajectory** | Path of movement |
| **Is Moving** | Boolean (true if speed > 5px/s) |

### Example Data
```json
{
  "id": "cup_0_red",
  "currentX": 340.5,
  "currentY": 210.3,
  "speed": "245.32 px/s",
  "velocityX": "180.5",
  "velocityY": "-145.2",
  "direction": "UP-LEFT",
  "predictedX": 360.7,
  "predictedY": 157.2,
  "isMoving": true,
  "positionHistory": 45
}
```

---

## 🎯 Use Cases

### Game Analysis
- Track cup positions in Thimbles game
- Monitor ball movement speed
- Predict where objects will be
- Analyze shuffle patterns

### Screen Recording
- Track objects during gameplay
- Measure hand/mouse speed
- Monitor movement patterns
- Create analytics reports

### Educational
- Physics demonstrations (speed, velocity)
- Movement analysis
- Pattern recognition
- Behavioral tracking

---

## 🔧 Advanced Features

### Object Detection

The app detects:
- **Red cups** (HSV: 0-10°)
- **Blue cups** (HSV: 100-130°)
- **Green cups** (HSV: 40-80°)
- **Yellow/Orange cups** (HSV: 15-35°)
- **White ball** (Light HSV)

### Speed Calculation
```
Speed = Distance / Time

Distance = √[(x₂-x₁)² + (y₂-y₁)²]
Time = timestamp₂ - timestamp₁
Speed = Distance / Time (pixels/second)
```

### Direction Detection
```
Velocity = (Δx/Δt, Δy/Δt)

IF |Δx| > |Δy|:
  Direction = LEFT (if Δx < 0) or RIGHT (if Δx > 0)
ELSE IF |Δy| > |Δx|:
  Direction = UP (if Δy < 0) or DOWN (if Δy > 0)
ELSE:
  Direction = STATIONARY
```

### Trajectory Prediction
```
Predicted Position = Current Position + (Velocity × Prediction Time)
Predicted X = X + (Vx × 0.5s)
Predicted Y = Y + (Vy × 0.5s)
```

---

## 🎨 Customization

### Change Detection Colors
Edit `CupDetectionEngine.kt`:
```kotlin
val colorRanges = listOf(
    Triple(Scalar(0.0, 100.0, 100.0), Scalar(10.0, 255.0, 255.0), "red"),
    // Add more colors...
)
```

### Adjust Sensitivity
In `ObjectTracker.kt`:
```kotlin
if (distance(matched, detection) < 50f) {  // Change 50 to 100 for less sensitive
    // Match tracking
}
```

### Change Overlay Size/Position
In `OverlayService.kt`:
```kotlin
width = 500  // Change width
height = 400 // Change height
```

---

## 📱 Permissions Required

- **Camera** - Capture screen
- **Storage** - Save data
- **Accessibility Service** - Monitor apps
- **Display Over Other Apps** - Show overlay
- **Internet** (optional) - Send data

---

## ⚠️ Limitations

- Requires Android 5.0+
- Object must be visible on screen
- Works best with colored objects
- Battery drain when tracking continuously
- Overlay may not work on all apps

---

## 🚀 Tips for Best Results

1. **Good Lighting** - Better object detection
2. **Clear Colors** - Distinct cup/ball colors
3. **Stable Device** - Less shake = better tracking
4. **Close Objects** - Easier to detect
5. **Slow Movement** - More accurate speed

---

## 📞 Support

For issues or questions:
- GitHub Issues: https://github.com/favie1963-lgtm/Favie-X-Tracker-
- Email: favie1963@gmail.com

---

**Enjoy tracking!** 🎯✨
