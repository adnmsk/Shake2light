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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.View
import android.widget.Button
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

    private lateinit var flashlightIcon: ImageView
    private lateinit var statusText: TextView
    private lateinit var debugText: TextView
    private lateinit var testButton: Button

    private var isFlashlightOn = false
    private var canToggle = true // Флаг, разрешающий переключение

    // Параметры для детектирования двойного встряхивания
    private val SHAKE_THRESHOLD = 10000f // Минимальная сила одного встряхивания
    private val DOUBLE_SHAKE_TIMEOUT = 250L // Максимальное время между двумя встряхиваниями (мс)
    private val MIN_SHAKE_INTERVAL = 100L // Минимальное время между встряхиваниями (мс)
    private val TOGGLE_COOLDOWN = 500L // Задержка между переключениями (мс)

    private var lastShakeTime: Long = 0
    private var firstShakeTime: Long = 0
    private var shakeCount = 0
    private var lastShakeStrength = 0f

    // Координаты для акселерометра
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
        debugText = findViewById(R.id.debugText)
        testButton = findViewById(R.id.testButton)

        // Инициализация менеджеров
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        handler = Handler(Looper.getMainLooper())

        // Проверка наличия фонарика
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            Toast.makeText(this, "Устройство не поддерживает фонарик", Toast.LENGTH_LONG).show()
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
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)!!
        if (accelerometer == null) {
            Toast.makeText(this, "Акселерометр не доступен", Toast.LENGTH_LONG).show()
            statusText.text = "Нет акселерометра!"
            finish()
            return
        }

        statusText.text = "Сделайте два быстрых встряхивания!"
        lastUpdateTime = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        Log.d(TAG, "Слушатель акселерометра запущен")
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        if (isFlashlightOn) {
            toggleFlashlight()
        }
        // Удаляем все pending callbacks при паузе
        handler.removeCallbacksAndMessages(null)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                detectDoubleShake(it)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Не используется
    }

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

        // Расчет силы встряхивания
        val currentShakeStrength = (Math.abs(deltaX) + Math.abs(deltaY) + Math.abs(deltaZ)) / deltaTime * 10000

        // Отладочная информация
        runOnUiThread {
            val cooldownText = if (!canToggle) " (ждем ${TOGGLE_COOLDOWN}мс)" else ""
            debugText.text = String.format("Сила: %.1f\nВстряхивания: %d/2%s", currentShakeStrength, shakeCount, cooldownText)
        }

        // Проверяем, можно ли сейчас переключать
        if (!canToggle) {
            return
        }

        // Проверка на достаточную силу встряхивания
        if (currentShakeStrength > SHAKE_THRESHOLD && currentTime - lastShakeTime > MIN_SHAKE_INTERVAL) {

            // Проверяем, не слишком ли давно было первое встряхивание
            if (shakeCount == 0 || currentTime - firstShakeTime > DOUBLE_SHAKE_TIMEOUT) {
                // Начинаем новую серию
                shakeCount = 1
                firstShakeTime = currentTime
                lastShakeStrength = currentShakeStrength
                Log.d(TAG, "Первое встряхивание: сила $currentShakeStrength")

                runOnUiThread {
                    statusText.text = "Еще одно встряхивание!"
                }

            } else {
                // Второе встряхивание в пределах времени
                val strengthDifference = Math.abs(currentShakeStrength - lastShakeStrength) / lastShakeStrength

                // Проверяем, что второе встряхивание похоже по силе (в пределах 50%)
                if (strengthDifference < 0.5f) {
                    shakeCount++
                    Log.d(TAG, "Второе встряхивание: сила $currentShakeStrength, разница: ${String.format("%.1f", strengthDifference * 100)}%")

                    if (shakeCount >= 2) {
                        // Двойное встряхивание обнаружено!
                        Log.d(TAG, "ДВОЙНОЕ ВСТРЯХИВАНИЕ ОБНАРУЖЕНО!")

                        // Запрещаем дальнейшие переключения на время cooldown
                        canToggle = false

                        runOnUiThread {
                            toggleFlashlight()
                            vibrate()
                            statusText.text = if (isFlashlightOn) "ВКЛЮЧЕНО! ✓" else "ВЫКЛЮЧЕНО! ✗"
                        }

                        // Включаем таймер для разрешения следующего переключения
                        handler.postDelayed({
                            canToggle = true
                            Log.d(TAG, "Cooldown завершен, можно переключать снова")
                            runOnUiThread {
                                statusText.text = if (isFlashlightOn) "ВКЛЮЧЕНО" else "ВЫКЛЮЧЕНО"
                            }
                        }, TOGGLE_COOLDOWN)

                        // Сбрасываем счетчик встряхиваний
                        shakeCount = 0
                    }
                } else {
                    Log.d(TAG, "Второе встряхивание слишком отличается: ${String.format("%.1f", strengthDifference * 100)}%")
                    // Сбрасываем, так как встряхивания не похожи
                    shakeCount = 0
                }
            }

            lastShakeTime = currentTime
        }

        // Сбрасываем счетчик, если прошло слишком много времени
        if (shakeCount > 0 && currentTime - firstShakeTime > DOUBLE_SHAKE_TIMEOUT) {
            Log.d(TAG, "Таймаут двойного встряхивания")
            shakeCount = 0
            runOnUiThread {
                statusText.text = "Слишком медленно! Попробуйте быстрее"
            }
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
            Log.e(TAG, "Ошибка доступа к камере: ${e.message}")
            runOnUiThread {
                statusText.text = "Ошибка доступа!"
            }
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
            Log.e(TAG, "Ошибка получения ID камеры: ${e.message}")
        }
        return null
    }

    private fun updateUI() {
        runOnUiThread {
            if (isFlashlightOn) {
                flashlightIcon.setImageResource(R.drawable.ic_flashlight_on)
            } else {
                flashlightIcon.setImageResource(R.drawable.ic_flashlight_off)
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
                Log.e(TAG, "Ошибка вибрации: ${e.message}")
            }
        }
    }

    // Функция для тестовой кнопки
    fun onTestButtonClick(view: View) {
        if (!canToggle) {
            Toast.makeText(this, "Подождите ${TOGGLE_COOLDOWN}мс", Toast.LENGTH_SHORT).show()
            return
        }

        canToggle = false
        toggleFlashlight()
        vibrate()
        runOnUiThread {
            statusText.text = if (isFlashlightOn) "ТЕСТ: ВКЛЮЧЕНО" else "ТЕСТ: ВЫКЛЮЧЕНО"
        }

        // Включаем таймер для разрешения следующего переключения
        handler.postDelayed({
            canToggle = true
            Log.d(TAG, "Cooldown завершен, можно переключать снова")
        }, TOGGLE_COOLDOWN)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Разрешение на камеру необходимо", Toast.LENGTH_LONG).show()
                statusText.text = "Нет разрешения!"
                finish()
            }
        }
    }
}