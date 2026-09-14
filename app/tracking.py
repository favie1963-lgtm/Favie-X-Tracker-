"""
Object Tracking Module
Multi-object detection and tracking with specialized cup detection
"""

import cv2
import numpy as np
from datetime import datetime
import logging

logger = logging.getLogger(__name__)


class ObjectTracker:
    """Tracks objects in video frames using contour detection and color filtering"""
    
    def __init__(self):
        """Initialize object tracker"""
        self.tracked_objects = {}
        self.object_id_counter = 0
        self.last_update = None
        self.frame_history = []
        
        # Color ranges for detection (HSV)
        self.color_ranges = {
            'red': {
                'lower': np.array([0, 100, 100]),
                'upper': np.array([10, 255, 255])
            },
            'blue': {
                'lower': np.array([100, 100, 100]),
                'upper': np.array([130, 255, 255])
            },
            'yellow': {
                'lower': np.array([20, 100, 100]),
                'upper': np.array([30, 255, 255])
            },
            'green': {
                'lower': np.array([40, 100, 100]),
                'upper': np.array([80, 255, 255])
            }
        }
        
        logger.info('ObjectTracker initialized')
    
    def detect_objects(self, frame):
        """
        Detect objects in frame
        
        Args:
            frame: BGR frame from OpenCV
            
        Returns:
            list: List of detected objects with positions and properties
        """
        if frame is None or frame.size == 0:
            return []
        
        detections = []
        
        try:
            # Convert to HSV for color-based detection
            hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
            
            # Detect colored objects (cups, balls, etc.)
            for color_name, color_range in self.color_ranges.items():
                mask = cv2.inRange(hsv, color_range['lower'], color_range['upper'])
                
                # Apply morphological operations
                kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))
                mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, kernel)
                mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, kernel)
                
                # Find contours
                contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
                
                for contour in contours:
                    area = cv2.contourArea(contour)
                    if area < 100:  # Ignore small objects
                        continue
                    
                    # Get bounding box
                    x, y, w, h = cv2.boundingRect(contour)
                    
                    # Get centroid
                    M = cv2.moments(contour)
                    if M['m00'] != 0:
                        cx = int(M['m10'] / M['m00'])
                        cy = int(M['m01'] / M['m00'])
                    else:
                        cx, cy = x + w // 2, y + h // 2
                    
                    # Get circularity (for cup detection - more circular = more likely a cup)
                    perimeter = cv2.arcLength(contour, True)
                    if perimeter == 0:
                        circularity = 0
                    else:
                        circularity = 4 * np.pi * area / (perimeter * perimeter)
                    
                    detection = {
                        'id': f'{color_name}_{self.object_id_counter}',
                        'color': color_name,
                        'bbox': {'x': x, 'y': y, 'width': w, 'height': h},
                        'centroid': {'x': cx, 'y': cy},
                        'area': float(area),
                        'circularity': float(circularity),
                        'confidence': float(min(circularity * 100, 100)),
                        'timestamp': datetime.now().isoformat()
                    }
                    
                    detections.append(detection)
                    self.object_id_counter += 1
            
            # Edge detection for additional object finding
            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
            edges = cv2.Canny(gray, 50, 150)
            
            # Find edge-based contours
            contours, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            
            for contour in contours[:5]:  # Limit to top 5 edge contours
                area = cv2.contourArea(contour)
                if 500 < area < 50000:  # Size range for cups
                    x, y, w, h = cv2.boundingRect(contour)
                    
                    # Estimate if this is a cup (roughly square/circular)
                    aspect_ratio = float(w) / h if h > 0 else 0
                    if 0.7 < aspect_ratio < 1.3:
                        M = cv2.moments(contour)
                        if M['m00'] != 0:
                            cx = int(M['m10'] / M['m00'])
                            cy = int(M['m01'] / M['m00'])
                        else:
                            cx, cy = x + w // 2, y + h // 2
                        
                        detection = {
                            'id': f'cup_{self.object_id_counter}',
                            'color': 'unknown',
                            'bbox': {'x': x, 'y': y, 'width': w, 'height': h},
                            'centroid': {'x': cx, 'y': cy},
                            'area': float(area),
                            'confidence': float(min(80, 100)),
                            'timestamp': datetime.now().isoformat()
                        }
                        detections.append(detection)
                        self.object_id_counter += 1
            
            # Update tracking
            self.tracked_objects = {d['id']: d for d in detections}
            self.last_update = datetime.now().isoformat()
            self.frame_history.append(detections)
            if len(self.frame_history) > 30:  # Keep last 30 frames
                self.frame_history.pop(0)
            
        except Exception as e:
            logger.error(f'Error detecting objects: {e}')
        
        return detections
    
    def track_cups(self, frame):
        """
        Specialized tracking for cups in games (like Thimbles)
        
        Args:
            frame: BGR frame from OpenCV
            
        Returns:
            dict: Cup tracking data with positions and predictions
        """
        detections = self.detect_objects(frame)
        
        # Filter for cup-like objects (high circularity or known cup colors)
        cups = [
            d for d in detections 
            if d['color'] in ['red', 'blue', 'yellow'] or 
               (d.get('circularity', 0) > 0.6) or
               d['id'].startswith('cup_')
        ]
        
        # Sort by confidence
        cups = sorted(cups, key=lambda x: x.get('confidence', 0), reverse=True)
        
        # Predict movements based on history
        predictions = self._predict_movements(cups)
        
        return {
            'cups': cups[:3],  # Top 3 cups
            'predictions': predictions,
            'total_detected': len(cups),
            'timestamp': datetime.now().isoformat()
        }
    
    def _predict_movements(self, objects):
        """Predict object movements based on history"""
        predictions = []
        
        if len(self.frame_history) < 3:
            return predictions
        
        # Simple prediction based on last 3 frames
        for obj in objects:
            obj_id = obj['id']
            
            # Find this object in history
            positions = []
            for frame_detections in self.frame_history[-3:]:
                for detection in frame_detections:
                    if detection['id'] == obj_id:
                        positions.append(detection['centroid'])
            
            if len(positions) >= 2:
                # Calculate velocity
                last_pos = positions[-1]
                prev_pos = positions[-2]
                vx = last_pos['x'] - prev_pos['x']
                vy = last_pos['y'] - prev_pos['y']
                
                # Predict next position
                pred_x = last_pos['x'] + vx
                pred_y = last_pos['y'] + vy
                
                predictions.append({
                    'object_id': obj_id,
                    'current_pos': last_pos,
                    'predicted_pos': {'x': pred_x, 'y': pred_y},
                    'velocity': {'x': float(vx), 'y': float(vy)}
                })
        
        return predictions
    
    def get_all_objects(self):
        """Get all currently tracked objects"""
        return list(self.tracked_objects.values())
    
    def get_object_by_id(self, obj_id):
        """Get specific object by ID"""
        return self.tracked_objects.get(obj_id)
    
    def reset(self):
        """Reset tracker"""
        self.tracked_objects = {}
        self.frame_history = []
        self.last_update = None
        logger.info('ObjectTracker reset')
