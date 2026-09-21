package com.favie.tracker

import android.graphics.PointF
import kotlin.math.sqrt
import kotlin.math.abs

data class DetectedObject(
    val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val confidence: Float,
    val color: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class TrackedObject(
    val id: String,
    val positions: MutableList<PointF> = mutableListOf(),
    val timestamps: MutableList<Long> = mutableListOf(),
    var lastUpdateTime: Long = System.currentTimeMillis(),
    var isActive: Boolean = true
) {
    fun addPosition(x: Float, y: Float, timestamp: Long) {
        positions.add(PointF(x, y))
        timestamps.add(timestamp)
        lastUpdateTime = timestamp
        
        // Keep only last 100 positions
        if (positions.size > 100) {
            positions.removeAt(0)
            timestamps.removeAt(0)
        }
    }
    
    fun getCurrentPosition(): PointF? = positions.lastOrNull()
    
    fun getSpeed(): Float {
        if (positions.size < 2) return 0f
        
        val lastPos = positions.last()
        val prevPos = positions[positions.size - 2]
        val lastTime = timestamps.last()
        val prevTime = timestamps[timestamps.size - 2]
        
        val timeDiff = (lastTime - prevTime) / 1000f // seconds
        if (timeDiff <= 0) return 0f
        
        val distance = sqrt((lastPos.x - prevPos.x) * (lastPos.x - prevPos.x) + 
                           (lastPos.y - prevPos.y) * (lastPos.y - prevPos.y))
        return distance / timeDiff // pixels per second
    }
    
    fun getVelocityVector(): PointF {
        if (positions.size < 2) return PointF(0f, 0f)
        
        val lastPos = positions.last()
        val prevPos = positions[positions.size - 2]
        val lastTime = timestamps.last()
        val prevTime = timestamps[timestamps.size - 2]
        
        val timeDiff = (lastTime - prevTime) / 1000f
        if (timeDiff <= 0) return PointF(0f, 0f)
        
        return PointF(
            (lastPos.x - prevPos.x) / timeDiff,
            (lastPos.y - prevPos.y) / timeDiff
        )
    }
    
    fun getDirection(): String {
        val velocity = getVelocityVector()
        return when {
            abs(velocity.x) > abs(velocity.y) -> if (velocity.x > 0) "RIGHT" else "LEFT"
            abs(velocity.y) > abs(velocity.x) -> if (velocity.y > 0) "DOWN" else "UP"
            else -> "STATIONARY"
        }
    }
    
    fun predictNextPosition(predictTimeMs: Long = 500): PointF? {
        val currentPos = getCurrentPosition() ?: return null
        val velocity = getVelocityVector()
        val predictTimeS = predictTimeMs / 1000f
        
        return PointF(
            currentPos.x + (velocity.x * predictTimeS),
            currentPos.y + (velocity.y * predictTimeS)
        )
    }
    
    fun getTrajectory(): List<PointF> = positions.toList()
}

class ObjectTracker {
    private val trackedObjects = mutableMapOf<String, TrackedObject>()
    private var selectedObjectId: String? = null
    
    fun updateDetections(detections: List<DetectedObject>) {
        // Match detections with existing tracked objects
        for (detection in detections) {
            val matched = findClosestTrackedObject(detection)
            
            if (matched != null && distance(matched, detection) < 50f) {
                // Update existing track
                matched.addPosition(detection.x, detection.y, detection.timestamp)
            } else {
                // Create new track
                val tracked = TrackedObject(
                    id = detection.id,
                    positions = mutableListOf(PointF(detection.x, detection.y)),
                    timestamps = mutableListOf(detection.timestamp)
                )
                trackedObjects[detection.id] = tracked
            }
        }
        
        // Mark objects as inactive if not updated
        trackedObjects.values.forEach { tracked ->
            if (System.currentTimeMillis() - tracked.lastUpdateTime > 2000) {
                tracked.isActive = false
            }
        }
    }
    
    fun selectObject(objectId: String) {
        selectedObjectId = objectId
    }
    
    fun deselectObject() {
        selectedObjectId = null
    }
    
    fun getSelectedObject(): TrackedObject? {
        return selectedObjectId?.let { trackedObjects[it] }
    }
    
    fun getActiveObjects(): List<TrackedObject> {
        return trackedObjects.values.filter { it.isActive }.toList()
    }
    
    fun getObjectData(objectId: String): Map<String, Any>? {
        val tracked = trackedObjects[objectId] ?: return null
        val currentPos = tracked.getCurrentPosition() ?: return null
        val speed = tracked.getSpeed()
        val velocity = tracked.getVelocityVector()
        val direction = tracked.getDirection()
        val predicted = tracked.predictNextPosition()
        
        return mapOf(
            "id" to objectId,
            "currentX" to currentPos.x,
            "currentY" to currentPos.y,
            "speed" to "%.2f".format(speed),
            "speedUnit" to "px/s",
            "velocityX" to "%.2f".format(velocity.x),
            "velocityY" to "%.2f".format(velocity.y),
            "direction" to direction,
            "predictedX" to (predicted?.x ?: 0f),
            "predictedY" to (predicted?.y ?: 0f),
            "isMoving" to (speed > 5f),
            "positionHistory" to tracked.positions.size,
            "direction" to getDirectionArrow(direction)
        )
    }
    
    private fun findClosestTrackedObject(detection: DetectedObject): TrackedObject? {
        var closest: TrackedObject? = null
        var minDistance = Float.MAX_VALUE
        
        for (tracked in trackedObjects.values) {
            if (tracked.isActive) {
                val currentPos = tracked.getCurrentPosition() ?: continue
                val dist = sqrt((detection.x - currentPos.x) * (detection.x - currentPos.x) + 
                               (detection.y - currentPos.y) * (detection.y - currentPos.y))
                if (dist < minDistance) {
                    minDistance = dist
                    closest = tracked
                }
            }
        }
        return closest
    }
    
    private fun distance(tracked: TrackedObject, detection: DetectedObject): Float {
        val pos = tracked.getCurrentPosition() ?: return Float.MAX_VALUE
        return sqrt((detection.x - pos.x) * (detection.x - pos.x) + 
                   (detection.y - pos.y) * (detection.y - pos.y))
    }
    
    private fun getDirectionArrow(direction: String): String = when (direction) {
        "UP" -> "⬆️"
        "DOWN" -> "⬇️"
        "LEFT" -> "⬅️"
        "RIGHT" -> "➡️"
        else -> "●"
    }
    
    fun clear() {
        trackedObjects.clear()
        selectedObjectId = null
    }
}
