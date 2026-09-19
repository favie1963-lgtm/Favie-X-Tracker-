package com.favie.tracker

import android.graphics.Bitmap
import android.graphics.Color
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.android.Utils

class CupDetectionEngine {
    
    fun detectCups(bitmap: Bitmap): List<DetectedObject> {
        val cupMat = Mat()
        Utils.bitmapToMat(bitmap, cupMat)
        
        // Convert to HSV for better color detection
        val hsvMat = Mat()
        Imgproc.cvtColor(cupMat, hsvMat, Imgproc.COLOR_RGB2HSV)
        
        val cups = mutableListOf<DetectedObject>()
        
        // Define cup colors (can be red, blue, green, etc.)
        val colorRanges = listOf(
            // Red cups
            Triple(Scalar(0.0, 100.0, 100.0), Scalar(10.0, 255.0, 255.0), "red"),
            // Blue cups
            Triple(Scalar(100.0, 100.0, 100.0), Scalar(130.0, 255.0, 255.0), "blue"),
            // Green cups
            Triple(Scalar(40.0, 100.0, 100.0), Scalar(80.0, 255.0, 255.0), "green"),
            // Yellow/Orange cups
            Triple(Scalar(15.0, 100.0, 100.0), Scalar(35.0, 255.0, 255.0), "yellow")
        )
        
        var cupId = 0
        
        for ((lower, upper, colorName) in colorRanges) {
            val mask = Mat()
            Core.inRange(hsvMat, lower, upper, mask)
            
            // Apply morphological operations to clean up mask
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)
            
            // Find contours (cups)
            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(mask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            
            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                
                // Filter by size (cups are typically 1000-50000 pixels in area)
                if (area < 500 || area > 100000) continue
                
                val rect = Imgproc.boundingRect(contour)
                val moment = Imgproc.moments(contour)
                
                val centerX = if (moment.m00 != 0.0) moment.m10 / moment.m00 else rect.x + rect.width / 2.0
                val centerY = if (moment.m00 != 0.0) moment.m01 / moment.m00 else rect.y + rect.height / 2.0
                
                // Calculate circularity (cups are roughly circular)
                val perimeter = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
                val circularity = if (perimeter > 0) {
                    (4 * Math.PI * area) / (perimeter * perimeter)
                } else 0.0
                
                // Cups should have high circularity (0.7+)
                if (circularity < 0.5) continue
                
                val confidence = (circularity * 100).toFloat().coerceIn(0f, 100f)
                
                cups.add(
                    DetectedObject(
                        id = "cup_${cupId++}_$colorName",
                        x = centerX.toFloat(),
                        y = centerY.toFloat(),
                        width = rect.width.toFloat(),
                        height = rect.height.toFloat(),
                        confidence = confidence,
                        color = colorName
                    )
                )
            }
            mask.release()
        }
        
        cupMat.release()
        hsvMat.release()
        
        return cups
    }
    
    fun detectBall(bitmap: Bitmap): DetectedObject? {
        val ballMat = Mat()
        Utils.bitmapToMat(bitmap, ballMat)
        
        val hsvMat = Mat()
        Imgproc.cvtColor(ballMat, hsvMat, Imgproc.COLOR_RGB2HSV)
        
        // Ball is typically white/light colored
        val lowerBall = Scalar(0.0, 0.0, 200.0)  // Low saturation, high brightness
        val upperBall = Scalar(180.0, 50.0, 255.0)
        
        val mask = Mat()
        Core.inRange(hsvMat, lowerBall, upperBall, mask)
        
        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(mask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        
        var bestBall: DetectedObject? = null
        var bestCircularity = 0.0
        
        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area < 50 || area > 5000) continue
            
            val rect = Imgproc.boundingRect(contour)
            val moment = Imgproc.moments(contour)
            
            val centerX = if (moment.m00 != 0.0) moment.m10 / moment.m00 else rect.x + rect.width / 2.0
            val centerY = if (moment.m00 != 0.0) moment.m01 / moment.m00 else rect.y + rect.height / 2.0
            
            val perimeter = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
            val circularity = if (perimeter > 0) {
                (4 * Math.PI * area) / (perimeter * perimeter)
            } else 0.0
            
            // Ball should be very circular
            if (circularity > bestCircularity && circularity > 0.8) {
                bestCircularity = circularity
                bestBall = DetectedObject(
                    id = "ball_main",
                    x = centerX.toFloat(),
                    y = centerY.toFloat(),
                    width = rect.width.toFloat(),
                    height = rect.height.toFloat(),
                    confidence = (circularity * 100).toFloat().coerceIn(0f, 100f),
                    color = "white"
                )
            }
        }
        
        mask.release()
        ballMat.release()
        hsvMat.release()
        
        return bestBall
    }
}
