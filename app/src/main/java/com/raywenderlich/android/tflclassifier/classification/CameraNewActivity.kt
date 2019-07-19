package com.raywenderlich.android.tflclassifier.classification

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.*
import android.text.Html
import android.util.DisplayMetrics
import android.util.Rational
import android.util.Size
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.UiThread
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.raywenderlich.android.tflclassifier.R
import com.raywenderlich.android.tflclassifier.classification.env.Logger
import com.raywenderlich.android.tflclassifier.classification.tflite.Classifier
import com.squareup.picasso.Picasso
import com.yalantis.ucrop.UCrop
import kotlinx.android.synthetic.main.cameranew.*
import kotlinx.android.synthetic.main.fragment_bottom_sheet_dialog.view.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException


class CameraNewActivity : AppCompatActivity(), LifecycleOwner, ImageCapture.OnImageSavedListener {
    private var classifier: Classifier? = null
    private val PERMISSIONS_REQUEST = 1
    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    private var lensFacing = CameraX.LensFacing.BACK
    private val TAG = "MainActivity"

    private val LOGGER = Logger()
    // Add this after onCreate
    private var handler: Handler? = null
    private var handlerThread: HandlerThread? = null


    var lastProcessingTimeMs: Long = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.cameranew)
        window.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermission()
        }
        // Every time the provided texture view changes, recompute layout
        viewFinder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateTransform() }


        if (Build.VERSION.SDK_INT >= 24) {
            try {
                val m = StrictMode::class.java.getMethod("disableDeathOnFileUriExposure")
                m.invoke(null)
            } catch (e: Exception) {
                e.printStackTrace()
            }

        }


        handlerThread = HandlerThread("inference")
        handlerThread?.let {
            it.start()
            handler = Handler(it.getLooper())
        }
    }


    @Synchronized
    public override fun onDestroy() {
        super.onDestroy()
        LOGGER.d("onPause $this")

        handlerThread?.let { it.quitSafely() }
        try {
            handlerThread?.let { it.join() }
            handlerThread = null
            handler = null
        } catch (e: InterruptedException) {
            LOGGER.e(e, "Exception!")
        }

    }


    private fun startCamera() {

        recreateClassifier(Classifier.Model.FLOAT, Classifier.Device.CPU, Classifier.availableProcessors())

        if (classifier == null) {
            LOGGER.e("No classifier on preview!")
            return
        }
        viewFinder.post {
            val metrics = DisplayMetrics().also { viewFinder.display.getRealMetrics(it) }.also {
                // just to remove height of bottom lcontainer
                it.heightPixels = it.heightPixels - resources.getDimensionPixelSize(R.dimen.bottom_button_container_height)
            }


            val screenSize = Size(metrics.widthPixels, metrics.heightPixels)
            val screenAspectRatio = Rational(metrics.widthPixels, metrics.heightPixels)

            val previewConfig = PreviewConfig.Builder().apply {
                setLensFacing(lensFacing)
                setTargetResolution(screenSize)
                setTargetAspectRatio(screenAspectRatio)
                setTargetRotation(viewFinder.display.rotation)
            }.build()

            val preview = Preview(previewConfig)

            preview.setOnPreviewOutputUpdateListener {
                // To update the SurfaceTexture, we have to remove it and re-add it
                val parent = viewFinder.parent as ViewGroup
                parent.removeView(viewFinder)
                parent.addView(viewFinder, 0)
                viewFinder.surfaceTexture = it.surfaceTexture
                updateTransform()
            }


            // Create configuration object for the image capture use case
            val imageCaptureConfig = ImageCaptureConfig.Builder()
                    .apply {
                        setLensFacing(lensFacing)
                        setTargetAspectRatio(screenAspectRatio)
                        setTargetRotation(viewFinder.display.rotation)
                        setTargetResolution(screenSize)
                        setCaptureMode(ImageCapture.CaptureMode.MAX_QUALITY)
                    }.build()

            // Build the image capture use case and attach button click listener
            val imageCapture = ImageCapture(imageCaptureConfig)
            btn_take_picture.setOnClickListener {
                showProgress()
                val file = File(externalMediaDirs.first(), "${System.currentTimeMillis()}.jpg")
                imageCapture.takePicture(file, this)
            }

            val imageAnalysisConfig = ImageAnalysisConfig.Builder()
                    .apply {
                        setTargetResolution(classifier!!.size)
//                        setImageReaderMode(ImageAnalysis.ImageReaderMode.ACQUIRE_LATEST_IMAGE)
                    }
                    .build()

            val analysis = ImageAnalysis(imageAnalysisConfig).apply {
                analyzer = MyAnalyser(this@CameraNewActivity) { bitmap ->

                    runInBackground {
                        if (classifier != null) {
//                            val cropped = Bitmap.createBitmap(bitmap, 0, 0, classifier!!.imageSizeX, classifier!!.imageSizeY)

                            val startTime = SystemClock.uptimeMillis()
                            val results = classifier?.recognizeImage(bitmap)
                            lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime
                            LOGGER.v("Detect: %s", results)
                            runOnUiThread { showResultsInBottomSheet(results) }
                        }
                    }
                }

            }

            CameraX.bindToLifecycle(this, preview, imageCapture, analysis)
        }
    }

    @UiThread
    private fun showResultsInBottomSheet(results: MutableList<Classifier.Recognition>?) {
        if (results != null && results.size >= 3) {
            var stringBuilder = StringBuilder()
            for (recognition in results) {
                if (recognition != null) {
                    if (recognition.title != null)
                        stringBuilder.append(recognition.title)
                    if (recognition.confidence != null) {
                        stringBuilder.append(String.format(": %.2f", 100 * recognition.confidence!!) + "%")
                        stringBuilder.append("\n")
                    }
                }
            }

            recognized.setText(stringBuilder.toString())
        }
    }

    //    https://gist.github.com/Mariovc/051391e92654de7b81d7
    override fun onImageSaved(file: File) {
        hideProgress()
        showDialog(file)
    }

    fun showDialog(file: File) {
        val view = layoutInflater.inflate(R.layout.fragment_bottom_sheet_dialog, null)
        Picasso.with(this).load(file).transform(CircleTransform()).into(view.dialogPhotoPreview)

        view.dialogPhotoShare.setOnClickListener {
            val shareIntent: Intent = Intent().apply {
                action = Intent.ACTION_SEND
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file))
                putExtra(Intent.EXTRA_TEXT, "https://www.voxtours.com/vox-pop-guide/ #popguide")
                putExtra(Intent.EXTRA_HTML_TEXT, Html.fromHtml("<p><a href=\"https://www.voxtours.com/vox-pop-guide/\">#popguide</a></p>\n"))
            }
            startActivity(Intent.createChooser(shareIntent, "Share"))
        }
        view.dialogPhotoEdit.setOnClickListener {
            UCrop.of(Uri.fromFile(file), Uri.fromFile(File(file.path + "_cropped")))
                    .withOptions(UCrop.Options().apply {
                        setHideBottomControls(true)
                        setFreeStyleCropEnabled(true)
                    })
                    .start(this)
        }

        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view)
        dialog.show()
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && requestCode == UCrop.REQUEST_CROP) {
            data?.let { UCrop.getOutput(it)?.apply { showDialog(File(path)) } }
        }
    }

    override fun onError(useCaseError: ImageCapture.UseCaseError, message: String, cause: Throwable?) {
        val msg = "Photo capture failed: $message"
        Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
        hideProgress()
    }

    private fun updateTransform() {
        val matrix = Matrix()
        val centerX = viewFinder.width / 2f
        val centerY = viewFinder.height / 2f

        val rotationDegrees = when (viewFinder.display.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> return
        }
        matrix.postRotate(-rotationDegrees.toFloat(), centerX, centerY)
        viewFinder.setTransform(matrix)
    }

    private fun recreateClassifier(model: Classifier.Model, device: Classifier.Device, numThreads: Int) {
        if (classifier != null) {
            LOGGER.d("Closing classifier.")
            classifier?.close()
            classifier = null
        }
        try {
            LOGGER.d("Creating classifier (model=%s, device=%s, numThreads=%d)", model, device, numThreads)
            classifier = Classifier.create(this, model, device, numThreads)
        } catch (e: IOException) {
            LOGGER.e(e, "Failed to create classifier.")
        }

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

    fun showProgress() {
        progress_circular.visibility = View.VISIBLE
    }

    fun hideProgress() {
        progress_circular.visibility = View.GONE
    }

    @Synchronized
    protected fun runInBackground(r: () -> Unit) {
        handler?.let { it.post(r) }
    }
}

typealias AnalyzerListener = (anal: Bitmap?) -> Unit

class MyAnalyser(context: CameraNewActivity, listener: AnalyzerListener? = null) : ImageAnalysis.Analyzer {
    private val listeners = ArrayList<AnalyzerListener>().apply { listener?.let { add(it) } }
    override fun analyze(image: ImageProxy?, rotationDegrees: Int) {
        if (listeners.isEmpty()) return

        val croppedBitmat = image?.toBitmap()
        listeners.forEach { it(croppedBitmat) }
    }
}

private fun ImageProxy.toBitmap(): Bitmap? {
    val yBuffer = planes[0].buffer // Y
    val uBuffer = planes[1].buffer // U
    val vBuffer = planes[2].buffer // V

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)

    //U and V are swapped
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 90, out)
    val imageBytes = out.toByteArray()
    return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}




