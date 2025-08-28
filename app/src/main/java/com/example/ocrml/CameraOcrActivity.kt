package com.example.ocrml

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
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
import java.io.ByteArrayOutputStream

class CameraOcrActivity : AppCompatActivity(), ImageReader.OnImageAvailableListener {

    private lateinit var textureView: TextureView
    private lateinit var textView: TextView
    private lateinit var overlay: OverlayView

    private lateinit var cameraDevice: CameraDevice
    private lateinit var captureSession: CameraCaptureSession
    private lateinit var imageReader: ImageReader
    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    private lateinit var backgroundHandler: Handler

    private val orientations = SparseIntArray().apply {
        append(Surface.ROTATION_0, 0)
        append(Surface.ROTATION_90, 90)
        append(Surface.ROTATION_180, 180)
        append(Surface.ROTATION_270, 270)
    }

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
        val image = reader.acquireLatestImage() ?: return
        val rotation = orientations[windowManager.defaultDisplay.rotation]
        val bitmap = image.toBitmap()
        val viewWidth = textureView.width
        val viewHeight = textureView.height
        val scaleX = bitmap.width.toFloat() / viewWidth
        val scaleY = bitmap.height.toFloat() / viewHeight
        val box = overlay.getBoxRect()
        val rect = Rect(
            (box.left * scaleX).toInt(),
            (box.top * scaleY).toInt(),
            (box.right * scaleX).toInt(),
            (box.bottom * scaleY).toInt()
        )
        val cropped = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
        val input = InputImage.fromBitmap(cropped, rotation)
        recognizer.process(input)
            .addOnSuccessListener { textView.text = it.text }
            .addOnFailureListener { }
        image.close()
    }

    private fun Image.toBitmap(): Bitmap {
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
        val bytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer.close()
        if (::cameraDevice.isInitialized) cameraDevice.close()
        if (::imageReader.isInitialized) imageReader.close()
    }
}
