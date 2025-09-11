package com.example.flashlightshake

import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var cameraManager: CameraManager
    private lateinit var flashlightIcon: ImageView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private var isServiceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        setContentView(R.layout.activity_main)
        flashlightIcon = findViewById(R.id.flashlightIcon)
        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        cameraManager = getSystemService(CameraManager::class.java)

        // Проверка наличия фонарика
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            showToast("Устройство не поддерживает фонарик")
            statusText.text = "Нет фонарика!"
            disableButtons()
            return
        }

        // Проверка разрешений
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(android.Manifest.permission.CAMERA), 1)
        } else {
            checkServiceStatus()
        }

        setupButtonListeners()
    }

    private fun setupButtonListeners() {
        startButton.setOnClickListener {
            if (checkCameraPermission()) {
                startFlashlightService()
                updateUI(true)
            }
        }

        stopButton.setOnClickListener {
            stopFlashlightService()
            updateUI(false)
        }
    }

    private fun startFlashlightService() {
        val serviceIntent = Intent(this, FlashlightService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        isServiceRunning = true
        showToast("Сервис запущен")
    }

    private fun stopFlashlightService() {
        val serviceIntent = Intent(this, FlashlightService::class.java)
        stopService(serviceIntent)
        isServiceRunning = false
        showToast("Сервис остановлен")
    }

    private fun checkServiceStatus() {
        // Простая проверка статуса сервиса
        // В реальном приложении можно использовать BroadcastReceiver для точного определения статуса
        updateUI(isServiceRunning)
    }

    private fun updateUI(serviceRunning: Boolean) {
        if (serviceRunning) {
            statusText.text = "Сервис активен"
            startButton.isEnabled = false
            stopButton.isEnabled = true
        } else {
            statusText.text = "Сервис остановлен"
            startButton.isEnabled = true
            stopButton.isEnabled = false
        }
    }

    private fun disableButtons() {
        startButton.isEnabled = false
        stopButton.isEnabled = false
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkServiceStatus()
            } else {
                showToast("Разрешение на камеру необходимо")
                statusText.text = "Нет разрешения!"
                disableButtons()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkServiceStatus()
    }
}