package com.example.flashlightshake

import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
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

    private var isFlashlightOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        setContentView(R.layout.activity_main)
        flashlightIcon = findViewById(R.id.flashlightIcon)
        statusText = findViewById(R.id.statusText)

        cameraManager = getSystemService(CameraManager::class.java)

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

        statusText.text = "Готов к работе"
        startFlashlightService()
    }

    private fun startFlashlightService() {
        val serviceIntent = Intent(this, FlashlightService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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
            } else {
                startFlashlightService()
            }
        }
    }
}