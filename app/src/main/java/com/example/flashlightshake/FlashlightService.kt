package com.example.flashlightshake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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

class FlashlightService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var accelerometer: Sensor
    private lateinit var cameraManager: CameraManager
    private lateinit var handler: Handler

    private var isFlashlightOn = false
    private var canToggle = true

    private val SHAKE_THRESHOLD = 10000f
    private val DOUBLE_SHAKE_TIMEOUT = 250L
    private val MIN_SHAKE_INTERVAL = 100L
    private val TOGGLE_COOLDOWN = 500L

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
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        handler = Handler(Looper.getMainLooper())

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: run {
            stopSelf()
            return
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")

        // Создаем канал уведомлений
        createNotificationChannel()

        // Запускаем в foreground
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Регистрируем слушатель сенсора
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)

        if (isFlashlightOn) {
            toggleFlashlight()
        }
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
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access error")
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
            Log.e(TAG, "Camera ID error")
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
                description = "Фоновая служба для включения фонарика встряхиванием"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Shake2Light")
            .setContentText("Работает в фоне")
            .setSmallIcon(R.drawable.ic_flashlight_on)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}