package com.raywenderlich.android

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Rational
import android.util.Size
import android.view.Surface
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.android.synthetic.main.cameranew.*
import java.io.File


class CameraNewActivity : AppCompatActivity(), LifecycleOwner, ImageCapture.OnImageSavedListener {
    private val PERMISSIONS_REQUEST = 1
    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

    private var lensFacing = CameraX.LensFacing.BACK
    private val TAG = "MainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.cameranew)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermission()
        }
        // Every time the provided texture view changes, recompute layout
        texturenew.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateTransform() }
    }

    private fun startCamera() {
        texturenew.post {
            val metrics = DisplayMetrics().also { texturenew.display.getRealMetrics(it) }
            val screenSize = Size(metrics.widthPixels, metrics.heightPixels)
            val screenAspectRatio = Rational(metrics.widthPixels, metrics.heightPixels)

            val previewConfig = PreviewConfig.Builder().apply {
                setLensFacing(lensFacing)
                setTargetResolution(screenSize)
                setTargetAspectRatio(screenAspectRatio)
                setTargetRotation(windowManager.defaultDisplay.rotation)
                setTargetRotation(texturenew.display.rotation)
            }.build()

            val preview = Preview(previewConfig)
            preview.setOnPreviewOutputUpdateListener {
                texturenew.surfaceTexture = it.surfaceTexture
                updateTransform()
            }


            // Create configuration object for the image capture use case
            val imageCaptureConfig = ImageCaptureConfig.Builder()
                    .apply {
                        setLensFacing(lensFacing)
                        setTargetAspectRatio(screenAspectRatio)
                        setTargetRotation(texturenew.display.rotation)
                        setCaptureMode(ImageCapture.CaptureMode.MAX_QUALITY)
                    }.build()

            // Build the image capture use case and attach button click listener
            val imageCapture = ImageCapture(imageCaptureConfig)
            btn_take_picture.setOnClickListener {
                val file = File(externalMediaDirs.first(), "${System.currentTimeMillis()}.jpg")
                imageCapture.takePicture(file, this)
            }

            CameraX.bindToLifecycle(this, preview, imageCapture)
        }
    }

    override fun onImageSaved(file: File) {
        val msg = "Photo capture successfully: ${file.absolutePath}"
        Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()

    }

    override fun onError(useCaseError: ImageCapture.UseCaseError, message: String, cause: Throwable?) {
        val msg = "Photo capture failed: $message"
        Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
    }

    private fun updateTransform() {
        val matrix = Matrix()
        val centerX = texturenew.width / 2f
        val centerY = texturenew.height / 2f

        val rotationDegrees = when (texturenew.display.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> return
        }
        matrix.postRotate(-rotationDegrees.toFloat(), centerX, centerY)
        texturenew.setTransform(matrix)
    }


    override fun onRequestPermissionsResult(
            requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == PERMISSIONS_REQUEST) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                requestPermission()
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (shouldShowRequestPermissionRationale(REQUIRED_PERMISSIONS.toString())) {
                Toast.makeText(
                        this@CameraNewActivity,
                        "Camera permission is required for this demo",
                        Toast.LENGTH_LONG)
                        .show()
            }
            requestPermissions(REQUIRED_PERMISSIONS, PERMISSIONS_REQUEST)
        }
    }
}