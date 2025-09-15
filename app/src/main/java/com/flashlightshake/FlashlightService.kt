package com.flashlightshake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.flashlightshake.R

class FlashlightService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var accelerometer: Sensor
    private lateinit var cameraManager: CameraManager
    private lateinit var handler: Handler
    private lateinit var prefs: SharedPreferences

    private var isFlashlightOn = false
    private var canToggle = true

    private val SHAKE_THRESHOLD = 10000f
    private val DOUBLE_SHAKE_TIMEOUT = 250L
    private val MIN_SHAKE_INTERVAL = 100L
    private val TOGGLE_COOLDOWN = 500L
    private val RESTART_DELAY = 1000L

    private var lastShakeTime: Long = 0
    private var firstShakeTime: Long = 0
    private var shakeCount = 0
    private var lastShakeStrength = 0f

    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var lastUpdateTime: Long = 0

    companion object {
        private const val TAG = "FlashlightService"
        private const val CHANNEL_ID = "Shake2LightChannel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP_SERVICE = "STOP_SERVICE"
        const val ACTION_START_SERVICE = "START_SERVICE"
        var isServiceRunning = false
        var wasStoppedByApp = false
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        isServiceRunning = true
        wasStoppedByApp = false

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        handler = Handler(Looper.getMainLooper())
        prefs = getSharedPreferences("service_prefs", MODE_PRIVATE)

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: run {
            stopSelf()
            return
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started with action: ${intent?.action}")

        if (intent?.action == ACTION_STOP_SERVICE) {
            wasStoppedByApp = true
            prefs.edit().putBoolean("stop_intentional", true).apply()
            stopSelf()
            return START_NOT_STICKY
        }

        // Reset stop flag on normal start
        wasStoppedByApp = false
        prefs.edit().putBoolean("stop_intentional", false).apply()

        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        isServiceRunning = false

        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)

        if (isFlashlightOn) {
            toggleFlashlight()
        }

        if (!isStopIntentReceived()) {
            handler.postDelayed({
                Log.d(TAG, "Auto-restarting service...")
                val restartIntent = Intent(this, FlashlightService::class.java)
                restartIntent.action = ACTION_START_SERVICE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(restartIntent)
                } else {
                    startService(restartIntent)
                }
            }, RESTART_DELAY)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed from recent list, service keeps running")
    }

    private fun isStopIntentReceived(): Boolean {
        return wasStoppedByApp || prefs.getBoolean("stop_intentional", false)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                detectDoubleShake(it)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun detectDoubleShake(event: SensorEvent) {
        val currentTime = System.currentTimeMillis()
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val deltaTime = currentTime - lastUpdateTime
        if (deltaTime < 10) return

        val deltaX = x - lastX
        val deltaY = y - lastY
        val deltaZ = z - lastZ

        val currentShakeStrength = (Math.abs(deltaX) + Math.abs(deltaY) + Math.abs(deltaZ)) / deltaTime * 10000

        if (!canToggle) return

        if (currentShakeStrength > SHAKE_THRESHOLD && currentTime - lastShakeTime > MIN_SHAKE_INTERVAL) {
            if (shakeCount == 0 || currentTime - firstShakeTime > DOUBLE_SHAKE_TIMEOUT) {
                shakeCount = 1
                firstShakeTime = currentTime
                lastShakeStrength = currentShakeStrength
            } else {
                val strengthDifference = Math.abs(currentShakeStrength - lastShakeStrength) / lastShakeStrength
                if (strengthDifference < 0.5f) {
                    shakeCount++
                    if (shakeCount >= 2) {
                        canToggle = false
                        toggleFlashlight()
                        handler.postDelayed({ canToggle = true }, TOGGLE_COOLDOWN)
                        shakeCount = 0
                    }
                } else {
                    shakeCount = 0
                }
            }
            lastShakeTime = currentTime
        }

        if (shakeCount > 0 && currentTime - firstShakeTime > DOUBLE_SHAKE_TIMEOUT) {
            shakeCount = 0
        }

        lastX = x
        lastY = y
        lastZ = z
        lastUpdateTime = currentTime
    }

    private fun toggleFlashlight() {
        try {
            val cameraId = getCameraId()
            if (cameraId != null) {
                cameraManager.setTorchMode(cameraId, !isFlashlightOn)
                isFlashlightOn = !isFlashlightOn
                Log.d(TAG, "Flashlight toggled: $isFlashlightOn")
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access error", e)
        }
    }

    private fun getCameraId(): String? {
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (hasFlash != null && hasFlash && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    return cameraId
                }
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera ID error", e)
        }
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Shake2Light Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background service for flashlight shake control"
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Shake2Light")
            .setContentText("Running in background - shake to toggle")
            .setSmallIcon(R.drawable.ic_flashlight_on)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .build()
    }
}