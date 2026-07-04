package com.example.test103

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min as fmin

/**
 * In-app crop view, rewritten with explicit clamping.
 * - The image is drawn INSET from the view edges (easier to grab handles).
 * - The selection can NEVER leave the image: every gesture result passes
 *   through one clamp function with plain, explicit bounds math.
 * - Multi-touch safe: only the finger that started the gesture is tracked.
 */
class CropView(context: Context, private val bitmap: Bitmap) : View(context) {

    private val imageRect = RectF()
    private val cropRect = RectF()
    private val imageMatrix = Matrix()

    private val dimPaint = Paint().apply { color = Color.parseColor("#A6000000") }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF"); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private val handleRadius = dp(10f)
    private val touchSlop = dp(28f)
    private val minCrop = dp(64f)

    // ✅ image drawn smaller than the view so edges/corners are easy to grab
    private val padSide get() = dp(28f)
    private val padTop get() = dp(72f)
    private val padBottom get() = dp(170f)   // clears the Detect/Cancel bar

    private enum class Mode { NONE, MOVE, LEFT, TOP, RIGHT, BOTTOM, TL, TR, BL, BR }
    private var mode = Mode.NONE
    private var lastX = 0f
    private var lastY = 0f
    private var activePointerId = -1

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return

        val availW = w - 2 * padSide
        val availH = h - padTop - padBottom
        val scale = fmin(availW / bitmap.width.toFloat(), availH / bitmap.height.toFloat())
        val dw = bitmap.width * scale
        val dh = bitmap.height * scale
        val left = (w - dw) / 2f
        val top = padTop + (availH - dh) / 2f
        imageRect.set(left, top, left + dw, top + dh)

        imageMatrix.reset()
        imageMatrix.postScale(scale, scale)
        imageMatrix.postTranslate(left, top)

        val inX = dw * 0.12f
        val inY = dh * 0.12f
        cropRect.set(left + inX, top + inY, left + dw - inX, top + dh - inY)
        clampCrop()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bitmap, imageMatrix, null)

        canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top, dimPaint)
        canvas.drawRect(0f, cropRect.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, dimPaint)
        canvas.drawRect(cropRect.right, cropRect.top, width.toFloat(), cropRect.bottom, dimPaint)

        canvas.drawRect(cropRect, borderPaint)
        val w3 = cropRect.width() / 3f
        val h3 = cropRect.height() / 3f
        canvas.drawLine(cropRect.left + w3, cropRect.top, cropRect.left + w3, cropRect.bottom, gridPaint)
        canvas.drawLine(cropRect.left + 2 * w3, cropRect.top, cropRect.left + 2 * w3, cropRect.bottom, gridPaint)
        canvas.drawLine(cropRect.left, cropRect.top + h3, cropRect.right, cropRect.top + h3, gridPaint)
        canvas.drawLine(cropRect.left, cropRect.top + 2 * h3, cropRect.right, cropRect.top + 2 * h3, gridPaint)

        canvas.drawCircle(cropRect.left, cropRect.top, handleRadius, handlePaint)
        canvas.drawCircle(cropRect.right, cropRect.top, handleRadius, handlePaint)
        canvas.drawCircle(cropRect.left, cropRect.bottom, handleRadius, handlePaint)
        canvas.drawCircle(cropRect.right, cropRect.bottom, handleRadius, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                mode = hitTest(event.x, event.y)
                lastX = event.x
                lastY = event.y
                return mode != Mode.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.NONE) return false
                val idx = event.findPointerIndex(activePointerId)
                if (idx < 0) return true
                val x = event.getX(idx)
                val y = event.getY(idx)
                applyDrag(x - lastX, y - lastY)
                lastX = x
                lastY = y
                clampCrop()   // ✅ single authority: the crop can never leave the image
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    mode = Mode.NONE
                    activePointerId = -1
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = Mode.NONE
                activePointerId = -1
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
            Mode.MOVE -> cropRect.offset(dx, dy)
            Mode.LEFT -> cropRect.left += dx
            Mode.RIGHT -> cropRect.right += dx
            Mode.TOP -> cropRect.top += dy
            Mode.BOTTOM -> cropRect.bottom += dy
            Mode.TL -> { cropRect.left += dx; cropRect.top += dy }
            Mode.TR -> { cropRect.right += dx; cropRect.top += dy }
            Mode.BL -> { cropRect.left += dx; cropRect.bottom += dy }
            Mode.BR -> { cropRect.right += dx; cropRect.bottom += dy }
            Mode.NONE -> {}
        }
    }

    /** One clamp to rule them all — explicit, no defaults, no shadowed names. */
    private fun clampCrop() {
        val il = imageRect.left
        val it = imageRect.top
        val ir = imageRect.right
        val ib = imageRect.bottom

        if (mode == Mode.MOVE) {
            // Moving: preserve size, slide the whole rect back inside
            var ox = 0f
            var oy = 0f
            if (cropRect.left < il) ox = il - cropRect.left
            if (cropRect.right > ir) ox = ir - cropRect.right
            if (cropRect.top < it) oy = it - cropRect.top
            if (cropRect.bottom > ib) oy = ib - cropRect.bottom
            cropRect.offset(ox, oy)
        } else {
            // Resizing: clamp each edge to the image, then enforce min size
            if (cropRect.left < il) cropRect.left = il
            if (cropRect.top < it) cropRect.top = it
            if (cropRect.right > ir) cropRect.right = ir
            if (cropRect.bottom > ib) cropRect.bottom = ib

            if (cropRect.width() < minCrop) {
                when (mode) {
                    Mode.LEFT, Mode.TL, Mode.BL -> cropRect.left = cropRect.right - minCrop
                    else -> cropRect.right = cropRect.left + minCrop
                }
                if (cropRect.left < il) { cropRect.left = il; cropRect.right = il + minCrop }
                if (cropRect.right > ir) { cropRect.right = ir; cropRect.left = ir - minCrop }
            }
            if (cropRect.height() < minCrop) {
                when (mode) {
                    Mode.TOP, Mode.TL, Mode.TR -> cropRect.top = cropRect.bottom - minCrop
                    else -> cropRect.bottom = cropRect.top + minCrop
                }
                if (cropRect.top < it) { cropRect.top = it; cropRect.bottom = it + minCrop }
                if (cropRect.bottom > ib) { cropRect.bottom = ib; cropRect.top = ib - minCrop }
            }
        }
    }

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