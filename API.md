# Favie X Tracker - API Reference

## Base URL
```
http://localhost:5000
```

## Endpoints

### Health Check
```
GET /api/health
```
Response:
```json
{
  "status": "healthy",
  "app": "Favie-X-Tracker",
  "version": "1.0.0",
  "config": {...}
}
```

### Tracking Control

#### Start Tracking
```
POST /api/start
```
Response:
```json
{
  "status": "started",
  "message": "Screen tracking initialized"
}
```

#### Stop Tracking
```
POST /api/stop
```
Response:
```json
{
  "status": "stopped"
}
```

#### Get Status
```
GET /api/status
```
Response:
```json
{
  "running": true,
  "fps": 30.5,
  "frame_count": 1024,
  "tracking_data_count": 50,
  "config": {...}
}
```

### Frame Access

#### Get Latest Frame
```
GET /api/latest-frame
```
Returns: JPEG image

### Object Tracking

#### Get Tracked Objects
```
GET /api/tracked-objects
```
Response:
```json
{
  "objects": [
    {
      "id": "red_1",
      "color": "red",
      "bbox": {"x": 100, "y": 200, "width": 50, "height": 50},
      "centroid": {"x": 125, "y": 225},
      "area": 2500,
      "circularity": 0.85,
      "confidence": 85.0,
      "timestamp": "2026-09-14T15:00:00"
    }
  ],
  "timestamp": "2026-09-14T15:00:00"
}
```

### Analytics

#### Get Summary
```
GET /api/analytics/summary
```
Response:
```json
{
  "session_start": "2026-09-14T15:00:00",
  "session_duration_seconds": 300,
  "total_frames_analyzed": 1024,
  "total_objects_detected": 256,
  "avg_objects_per_frame": 0.25,
  "avg_confidence": 87.5,
  "fps": 30.5
}
```

#### Generate Report
```
GET /api/analytics/report
```
Response:
```json
{
  "summary": {...},
  "detection_trend": [0.1, 0.2, 0.15, ...],
  "average_motion": 45.2,
  "average_brightness": 65.3,
  "color_distribution": {"red": 50, "blue": 30, ...},
  "recent_activity": [...],
  "report_generated": "2026-09-14T15:00:00"
}
```

### Configuration

#### Get Configuration
```
GET /api/config
```
Response:
```json
{
  "capture_interval": 500,
  "resolution": "1920x1080",
  "output_format": "json",
  "enable_ai_analysis": true,
  "storage_path": "./data",
  "debug_mode": false
}
```

#### Update Configuration
```
POST /api/config
Content-Type: application/json

{
  "capture_interval": 1000,
  "enable_ai_analysis": false
}
```
Response:
```json
{
  "status": "updated",
  "config": {...}
}
```

## Error Responses

### 400 Bad Request
```json
{
  "status": "error",
  "message": "Invalid request parameters"
}
```

### 404 Not Found
```json
{
  "error": "Resource not found"
}
```

### 500 Internal Server Error
```json
{
  "status": "error",
  "message": "Internal server error description"
}
```

## Usage Examples

### JavaScript/React
```javascript
// Start tracking
const result = await window.api.startTracking();

// Get current status
const status = await window.api.getStatus();

// Get tracked objects
const objects = await window.api.getTrackedObjects();

// Generate report
const report = await window.api.getReport();
```

### Python
```python
import requests

base_url = 'http://localhost:5000'

# Start tracking
response = requests.post(f'{base_url}/api/start')
print(response.json())

# Get status
response = requests.get(f'{base_url}/api/status')
print(response.json())

# Get analytics
response = requests.get(f'{base_url}/api/analytics/summary')
print(response.json())
```

### cURL
```bash
# Start tracking
curl -X POST http://localhost:5000/api/start

# Get status
curl http://localhost:5000/api/status

# Get latest frame
curl http://localhost:5000/api/latest-frame --output frame.jpg
```
