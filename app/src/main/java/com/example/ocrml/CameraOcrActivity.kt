package com.example.ocrml

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.os.Bundle
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions

class CameraOcrActivity : AppCompatActivity(), ImageReader.OnImageAvailableListener {

    private lateinit var textureView: TextureView
    private lateinit var textView: TextView
    private lateinit var overlay: OverlayView
    private lateinit var rescanButton: Button

    private lateinit var cameraDevice: CameraDevice
    private lateinit var captureSession: CameraCaptureSession
    private lateinit var imageReader: ImageReader
    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    private lateinit var backgroundHandler: Handler

    // Flag to avoid queuing multiple recognition tasks which causes delays
    @Volatile
    private var isProcessing = false

    // Use ML Kit's Japanese text recognizer
    private val recognizer =
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    private var lastNameLine: String? = null
    private var stableCount = 0
    @Volatile
    private var scanningActive = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_ocr)
        textureView = findViewById(R.id.preview)
        textView = findViewById(R.id.result)
        overlay = findViewById<OverlayView>(R.id.ocr_area)
        rescanButton = findViewById(R.id.rescan_button)
        rescanButton.setOnClickListener {
            scanningActive = true
            stableCount = 0
            lastNameLine = null
            rescanButton.visibility = View.GONE
            textView.text = ""
        }

        val handlerThread = HandlerThread("CameraBackground")
        handlerThread.start()
        backgroundHandler = Handler(handlerThread.looper)

        if (allPermissionsGranted()) {
            textureView.post { openCamera() }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 0)
        }
    }

    private fun allPermissionsGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 0 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            textureView.post { openCamera() }
        } else {
            finish()
        }
    }

    private fun openCamera() {
        val manager = getSystemService(CameraManager::class.java)
        val cameraId = manager.cameraIdList.first()

        val characteristics = manager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val size = map!!.getOutputSizes(ImageFormat.YUV_420_888)[0]

        imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        imageReader.setOnImageAvailableListener(this, backgroundHandler)

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                createSession(size)
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
            }
        }, backgroundHandler)
    }

    private fun createSession(size: Size) {
        val surfaceTexture = textureView.surfaceTexture ?: return
        surfaceTexture.setDefaultBufferSize(size.width, size.height)
        val surface = Surface(surfaceTexture)

        previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        previewRequestBuilder.addTarget(surface)
        previewRequestBuilder.addTarget(imageReader.surface)

        cameraDevice.createCaptureSession(listOf(surface, imageReader.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) { }
            }, backgroundHandler)
    }

    override fun onImageAvailable(reader: ImageReader) {
        if (!scanningActive) return

        val image = reader.acquireLatestImage() ?: return
        image.close()

        // Skip this frame if a recognition task is already running
        if (isProcessing) return

        val bitmap = textureView.bitmap ?: return
        val box = overlay.getBoxRect()
        val cropped = Bitmap.createBitmap(bitmap, box.left, box.top, box.width(), box.height())
        val input = InputImage.fromBitmap(cropped, 0)

        isProcessing = true
        recognizer.process(input)
            .addOnSuccessListener { result ->
                val line = result.text.lines().firstOrNull { it.contains("氏名") }
                if (line != null) {
                    textView.text = line
                    if (line == lastNameLine) {
                        stableCount++
                    } else {
                        lastNameLine = line
                        stableCount = 1
                    }
                    if (stableCount >= 3) {
                        scanningActive = false
                        textView.text = "$line\n扫描成功"
                        rescanButton.visibility = View.VISIBLE
                    }
                } else {
                    textView.text = result.text
                    lastNameLine = null
                    stableCount = 0
                }
            }
            .addOnFailureListener { }
            .addOnCompleteListener { isProcessing = false }
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer.close()
        if (::cameraDevice.isInitialized) cameraDevice.close()
        if (::imageReader.isInitialized) imageReader.close()
    }
}
