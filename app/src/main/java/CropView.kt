package com.example.test103

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * In-app crop view. Displays a bitmap scaled to fit and lets the user
 * drag/resize a crop rectangle. No external crop app needed — works on
 * every device, unlike the removed "com.android.camera.action.CROP" intent.
 */
class CropView(context: Context, private val bitmap: Bitmap) : View(context) {

    private val imageRect = RectF()   // where the bitmap is drawn on screen
    private val cropRect = RectF()    // current crop selection (view coords)
    private val imageMatrix = Matrix()

    private val dimPaint = Paint().apply { color = Color.parseColor("#A6000000") }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF"); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private val handleRadius = dp(10f)
    private val touchSlop = dp(28f)      // how close a finger must be to grab an edge
    private val minCropSize = dp(64f)

    // What the current gesture is doing
    private enum class Mode { NONE, MOVE, LEFT, TOP, RIGHT, BOTTOM, TL, TR, BL, BR }
    private var mode = Mode.NONE
    private var lastX = 0f
    private var lastY = 0f

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return

        // Fit bitmap inside the view, centered
        val scale = min(w / bitmap.width.toFloat(), h / bitmap.height.toFloat())
        val dw = bitmap.width * scale
        val dh = bitmap.height * scale
        val left = (w - dw) / 2f
        val top = (h - dh) / 2f
        imageRect.set(left, top, left + dw, top + dh)

        imageMatrix.reset()
        imageMatrix.postScale(scale, scale)
        imageMatrix.postTranslate(left, top)

        // Start with a crop box covering the middle 70% of the image
        val inX = dw * 0.15f
        val inY = dh * 0.15f
        cropRect.set(left + inX, top + inY, left + dw - inX, top + dh - inY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bitmap, imageMatrix, null)

        // Dim everything outside the crop rect
        canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top, dimPaint)
        canvas.drawRect(0f, cropRect.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, dimPaint)
        canvas.drawRect(cropRect.right, cropRect.top, width.toFloat(), cropRect.bottom, dimPaint)

        // Border + rule-of-thirds grid
        canvas.drawRect(cropRect, borderPaint)
        val w3 = cropRect.width() / 3f
        val h3 = cropRect.height() / 3f
        canvas.drawLine(cropRect.left + w3, cropRect.top, cropRect.left + w3, cropRect.bottom, gridPaint)
        canvas.drawLine(cropRect.left + 2 * w3, cropRect.top, cropRect.left + 2 * w3, cropRect.bottom, gridPaint)
        canvas.drawLine(cropRect.left, cropRect.top + h3, cropRect.right, cropRect.top + h3, gridPaint)
        canvas.drawLine(cropRect.left, cropRect.top + 2 * h3, cropRect.right, cropRect.top + 2 * h3, gridPaint)

        // Corner handles
        canvas.drawCircle(cropRect.left, cropRect.top, handleRadius, handlePaint)
        canvas.drawCircle(cropRect.right, cropRect.top, handleRadius, handlePaint)
        canvas.drawCircle(cropRect.left, cropRect.bottom, handleRadius, handlePaint)
        canvas.drawCircle(cropRect.right, cropRect.bottom, handleRadius, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = hitTest(event.x, event.y)
                lastX = event.x
                lastY = event.y
                return mode != Mode.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.NONE) return false
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                applyDrag(dx, dy)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = Mode.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTest(x: Float, y: Float): Mode {
        val nearL = abs(x - cropRect.left) < touchSlop
        val nearR = abs(x - cropRect.right) < touchSlop
        val nearT = abs(y - cropRect.top) < touchSlop
        val nearB = abs(y - cropRect.bottom) < touchSlop
        val insideX = x > cropRect.left - touchSlop && x < cropRect.right + touchSlop
        val insideY = y > cropRect.top - touchSlop && y < cropRect.bottom + touchSlop

        return when {
            nearL && nearT -> Mode.TL
            nearR && nearT -> Mode.TR
            nearL && nearB -> Mode.BL
            nearR && nearB -> Mode.BR
            nearL && insideY -> Mode.LEFT
            nearR && insideY -> Mode.RIGHT
            nearT && insideX -> Mode.TOP
            nearB && insideX -> Mode.BOTTOM
            cropRect.contains(x, y) -> Mode.MOVE
            else -> Mode.NONE
        }
    }

    private fun applyDrag(dx: Float, dy: Float) {
        when (mode) {
            Mode.MOVE -> {
                var mx = dx
                var my = dy
                if (cropRect.left + mx < imageRect.left) mx = imageRect.left - cropRect.left
                if (cropRect.right + mx > imageRect.right) mx = imageRect.right - cropRect.right
                if (cropRect.top + my < imageRect.top) my = imageRect.top - cropRect.top
                if (cropRect.bottom + my > imageRect.bottom) my = imageRect.bottom - cropRect.bottom
                cropRect.offset(mx, my)
            }
            Mode.LEFT -> cropRect.left = clampX(cropRect.left + dx, max = cropRect.right - minCropSize)
            Mode.RIGHT -> cropRect.right = clampX(cropRect.right + dx, min = cropRect.left + minCropSize)
            Mode.TOP -> cropRect.top = clampY(cropRect.top + dy, max = cropRect.bottom - minCropSize)
            Mode.BOTTOM -> cropRect.bottom = clampY(cropRect.bottom + dy, min = cropRect.top + minCropSize)
            Mode.TL -> { cropRect.left = clampX(cropRect.left + dx, max = cropRect.right - minCropSize)
                         cropRect.top = clampY(cropRect.top + dy, max = cropRect.bottom - minCropSize) }
            Mode.TR -> { cropRect.right = clampX(cropRect.right + dx, min = cropRect.left + minCropSize)
                         cropRect.top = clampY(cropRect.top + dy, max = cropRect.bottom - minCropSize) }
            Mode.BL -> { cropRect.left = clampX(cropRect.left + dx, max = cropRect.right - minCropSize)
                         cropRect.bottom = clampY(cropRect.bottom + dy, min = cropRect.top + minCropSize) }
            Mode.BR -> { cropRect.right = clampX(cropRect.right + dx, min = cropRect.left + minCropSize)
                         cropRect.bottom = clampY(cropRect.bottom + dy, min = cropRect.top + minCropSize) }
            Mode.NONE -> {}
        }
    }

    private fun clampX(v: Float, min: Float = imageRect.left, max: Float = imageRect.right): Float =
        max(min, min(max, v))

    private fun clampY(v: Float, min: Float = imageRect.top, max: Float = imageRect.bottom): Float =
        max(min, min(max, v))

    /** Returns the cropped bitmap mapped back into original bitmap pixels. */
    fun getCroppedBitmap(): Bitmap {
        val scale = imageRect.width() / bitmap.width.toFloat()
        val left = ((cropRect.left - imageRect.left) / scale).toInt().coerceIn(0, bitmap.width - 1)
        val top = ((cropRect.top - imageRect.top) / scale).toInt().coerceIn(0, bitmap.height - 1)
        val w = (cropRect.width() / scale).toInt().coerceIn(1, bitmap.width - left)
        val h = (cropRect.height() / scale).toInt().coerceIn(1, bitmap.height - top)
        return Bitmap.createBitmap(bitmap, left, top, w, h)
    }
}
