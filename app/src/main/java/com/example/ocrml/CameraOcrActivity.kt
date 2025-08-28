package com.example.ocrml

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.util.Size
import android.view.Surface
import com.example.ocrml.AutoFitTextureView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions

class CameraOcrActivity : AppCompatActivity(), ImageReader.OnImageAvailableListener {

    private lateinit var textureView: AutoFitTextureView
    private lateinit var textView: TextView
    private lateinit var overlay: OverlayView

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_ocr)
        textureView = findViewById(R.id.preview)
        textView = findViewById(R.id.result)
        overlay = findViewById<OverlayView>(R.id.ocr_area)

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
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

        val viewWidth = textureView.width
        val viewHeight = textureView.height
        val size = chooseOptimalSize(map, viewWidth, viewHeight)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        imageReader.setOnImageAvailableListener(this, backgroundHandler)

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                createSession(size, sensorOrientation)
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
            }
        }, backgroundHandler)
    }

    private fun createSession(size: Size, sensorOrientation: Int) {
        val surfaceTexture = textureView.surfaceTexture ?: return
        surfaceTexture.setDefaultBufferSize(size.width, size.height)
        configureTransform(size, sensorOrientation)
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

    private fun chooseOptimalSize(map: StreamConfigurationMap, viewWidth: Int, viewHeight: Int): Size {
        val targetRatio = viewWidth.toFloat() / viewHeight
        return map.getOutputSizes(ImageFormat.YUV_420_888).minByOrNull {
            kotlin.math.abs(it.width.toFloat() / it.height - targetRatio)
        } ?: map.getOutputSizes(ImageFormat.YUV_420_888)[0]
    }

    private fun configureTransform(previewSize: Size, sensorOrientation: Int) {
        if (textureView.width == 0 || textureView.height == 0) return

        val rotation = windowManager.defaultDisplay.rotation
        val rotationDegrees = when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val diff = (sensorOrientation - rotationDegrees + 360) % 360

        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, textureView.width.toFloat(), textureView.height.toFloat())
        val bufferRect = if (diff == 90 || diff == 270) {
            RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        } else {
            RectF(0f, 0f, previewSize.width.toFloat(), previewSize.height.toFloat())
        }
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        matrix.setRectToRect(bufferRect, viewRect, Matrix.ScaleToFit.FILL)
        val scale = kotlin.math.max(
            viewRect.height() / bufferRect.height(),
            viewRect.width() / bufferRect.width()
        )
        matrix.postScale(scale, scale, centerX, centerY)
        matrix.postRotate(diff.toFloat(), centerX, centerY)
        textureView.setTransform(matrix)
    }

    override fun onImageAvailable(reader: ImageReader) {
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
            .addOnSuccessListener { textView.text = it.text }
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
