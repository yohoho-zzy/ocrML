package com.example.ocrml

import android.content.Context
import android.util.AttributeSet
import android.view.TextureView

/**
 * A simple [TextureView] used for displaying the camera preview. The
 * preview's aspect ratio and rotation are handled with a transformation
 * matrix inside [CameraOcrActivity], so this view intentionally performs
 * no additional measurement or scaling on its own.
 */
class AutoFitTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : TextureView(context, attrs, defStyle)
