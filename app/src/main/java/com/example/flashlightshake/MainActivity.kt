package com.example.flashlightshake

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.*
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var accelerometer: Sensor
    private lateinit var cameraManager: CameraManager
    private lateinit var vibrator: Vibrator
    private lateinit var handler: Handler
    private lateinit var wakeLock: PowerManager.WakeLock

    private lateinit var flashlightIcon: ImageView
    private lateinit var statusText: TextView

    private var isFlashlightOn = false
    private var canToggle = true

    // Параметры для детектирования двойного встряхивания
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
        private const val TAG = "FlashlightApp"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Убираем ActionBar
        supportActionBar?.hide()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }

        setContentView(R.layout.activity_main)

        flashlightIcon = findViewById(R.id.flashlightIcon)
        statusText = findViewById(R.id.statusText)

        // Инициализация менеджеров
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        handler = Handler(Looper.getMainLooper())

        // WakeLock для работы в фоне
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "FlashlightShake::WakeLock"
        )

        // Проверка наличия фонарика
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            showToast("Устройство не поддерживает фонарик")
            statusText.text = "Нет фонарика!"
            finish()
            return
        }

        // Проверка разрешений
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(android.Manifest.permission.CAMERA), 1)
        }

        // Получение акселерометра
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: run {
            showToast("Акселерометр не доступен")
            statusText.text = "Нет акселерометра!"
            finish()
            return
        }

        statusText.text = "Готов к работе"
        lastUpdateTime = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        // Захватываем WakeLock для работы в фоне
        if (!wakeLock.isHeld) {
            wakeLock.acquire(10 * 60 * 1000L /*10 minutes*/)
        }
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
    }

    override fun onPause() {
        super.onPause()
        // Не останавливаем слушатель полностью, чтобы работало в фоне
        // Освобождаем WakeLock только если приложение полностью закрыто
        if (isFinishing) {
            sensorManager.unregisterListener(this)
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
            if (isFlashlightOn) {
                toggleFlashlight()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
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

        if (!canToggle) {
            return
        }

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
                        vibrate()

                        handler.postDelayed({
                            canToggle = true
                        }, TOGGLE_COOLDOWN)

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
                updateUI()
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Ошибка доступа к камере")
        }
    }

    private fun getCameraId(): String? {
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)

                if (hasFlash != null && hasFlash &&
                    lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    return cameraId
                }
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Ошибка получения ID камеры")
        }
        return null
    }

    private fun updateUI() {
        runOnUiThread {
            if (isFlashlightOn) {
                flashlightIcon.setImageResource(R.drawable.ic_flashlight_on)
                statusText.text = "ВКЛЮЧЕНО"
            } else {
                flashlightIcon.setImageResource(R.drawable.ic_flashlight_off)
                statusText.text = "ВЫКЛЮЧЕНО"
            }
        }
    }

    private fun vibrate() {
        if (vibrator.hasVibrator()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    vibrator.vibrate(100)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка вибрации")
            }
        }
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                showToast("Разрешение на камеру необходимо")
                statusText.text = "Нет разрешения!"
                finish()
            }
        }
    }
}