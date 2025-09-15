package com.flashlightshake

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.flashlightshake.R

class MainActivity : AppCompatActivity() {

    private lateinit var cameraManager: CameraManager
    private lateinit var flashlightIcon: ImageView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var testButton: Button

    private var isServiceRunning = false
    private var isFlashlightOn = false

    companion object {
        var isServiceRunning = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        setContentView(R.layout.activity_main)
        flashlightIcon = findViewById(R.id.flashlightIcon)
        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        testButton = findViewById(R.id.testButton)

        cameraManager = getSystemService(CameraManager::class.java)

        // Check if device has flashlight
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            showToast("Device doesn't support flashlight")
            statusText.text = "No flashlight!"
            disableButtons()
            return
        }

        // Check permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.CAMERA), 1)
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

        testButton.setOnClickListener {
            if (checkCameraPermission()) {
                toggleTestFlashlight()
            }
        }
    }

    private fun toggleTestFlashlight() {
        try {
            val cameraId = getCameraId()
            if (cameraId != null) {
                cameraManager.setTorchMode(cameraId, !isFlashlightOn)
                isFlashlightOn = !isFlashlightOn

                if (isFlashlightOn) {
                    flashlightIcon.setImageResource(R.drawable.ic_flashlight_on)
                    showToast("Flashlight ON")
                } else {
                    flashlightIcon.setImageResource(R.drawable.ic_flashlight_off)
                    showToast("Flashlight OFF")
                }
            }
        } catch (e: Exception) {
            showToast("Camera access error")
            Log.e("MainActivity", "Flashlight test error", e)
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
        } catch (e: Exception) {
            Log.e("MainActivity", "Camera ID error", e)
        }
        return null
    }

    private fun startFlashlightService() {
        val serviceIntent = Intent(this, FlashlightService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        isServiceRunning = true
        FlashlightService.isServiceRunning = true
        showToast("Service started")
    }

    private fun stopFlashlightService() {
        val serviceIntent = Intent(this, FlashlightService::class.java)
        serviceIntent.action = FlashlightService.ACTION_STOP_SERVICE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        isServiceRunning = false
        FlashlightService.isServiceRunning = false
        showToast("Service stopped")
    }

    private fun checkServiceStatus() {
        updateUI(FlashlightService.isServiceRunning)
    }

    private fun updateUI(serviceRunning: Boolean) {
        if (serviceRunning) {
            statusText.text = "Service active"
            startButton.isEnabled = false
            stopButton.isEnabled = true
            testButton.isEnabled = false
        } else {
            statusText.text = "Service stopped"
            startButton.isEnabled = true
            stopButton.isEnabled = false
            testButton.isEnabled = true
        }
    }

    private fun disableButtons() {
        startButton.isEnabled = false
        stopButton.isEnabled = false
        testButton.isEnabled = false
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
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
                showToast("Camera permission required")
                statusText.text = "No permission!"
                disableButtons()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkServiceStatus()
    }
}