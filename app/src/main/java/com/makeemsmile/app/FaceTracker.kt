package com.makeemsmile.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.abs

/** The 52 MediaPipe face blendshapes, matched by name so their order never matters. */
object Shapes {
    val NAMES = listOf(
        "_neutral", "browDownLeft", "browDownRight", "browInnerUp", "browOuterUpLeft", "browOuterUpRight",
        "cheekPuff", "cheekSquintLeft", "cheekSquintRight", "eyeBlinkLeft", "eyeBlinkRight",
        "eyeLookDownLeft", "eyeLookDownRight", "eyeLookInLeft", "eyeLookInRight", "eyeLookOutLeft",
        "eyeLookOutRight", "eyeLookUpLeft", "eyeLookUpRight", "eyeSquintLeft", "eyeSquintRight",
        "eyeWideLeft", "eyeWideRight", "jawForward", "jawLeft", "jawOpen", "jawRight", "mouthClose",
        "mouthDimpleLeft", "mouthDimpleRight", "mouthFrownLeft", "mouthFrownRight", "mouthFunnel",
        "mouthLeft", "mouthLowerDownLeft", "mouthLowerDownRight", "mouthPressLeft", "mouthPressRight",
        "mouthPucker", "mouthRight", "mouthRollLower", "mouthRollUpper", "mouthShrugLower",
        "mouthShrugUpper", "mouthSmileLeft", "mouthSmileRight", "mouthStretchLeft", "mouthStretchRight",
        "mouthUpperUpLeft", "mouthUpperUpRight", "noseSneerLeft", "noseSneerRight"
    )
    val N = NAMES.size
    val INDEX: Map<String, Int> = NAMES.withIndex().associate { it.value to it.index }
}

/**
 * Runs MediaPipe Face Landmarker on camera frames and reports the 52 blendshape scores of the
 * player's face (or null when no face is found), plus the upright camera frame.
 *
 * Up to 3 faces are detected; the player is the biggest one. If the face tracked last time is still
 * nearly as big as the biggest, it is kept, so a laughing bystander can't take over.
 */
class FaceTracker(context: Context, private val onResult: (FloatArray?, Bitmap?) -> Unit) {

    val usingGpu: Boolean
    private val landmarker: FaceLandmarker
    private var lastTs = 0L
    @Volatile private var lastFrame: Bitmap? = null

    private var lockedX = -1f
    private var lockedY = -1f

    init {
        var gpu = true
        val lm = try {
            build(context, Delegate.GPU)
        } catch (e: Throwable) {
            Log.w("MakeEmSmile", "GPU delegate failed, using CPU", e)
            gpu = false
            build(context, Delegate.CPU)
        }
        landmarker = lm
        usingGpu = gpu
    }

    private fun build(context: Context, delegate: Delegate): FaceLandmarker {
        val base = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .setDelegate(delegate)
            .build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(3)
            .setOutputFaceBlendshapes(true)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> handle(result) }
            .setErrorListener { e -> Log.e("MakeEmSmile", "Face landmarker error", e) }
            .build()
        return FaceLandmarker.createFromOptions(context, options)
    }

    /** Forget the locked face (new round, new player). */
    fun resetLock() {
        lockedX = -1f
        lockedY = -1f
    }

    /** Called on the camera executor. Always closes the frame. */
    fun process(image: ImageProxy) {
        val rotation = image.imageInfo.rotationDegrees
        val bitmap = try {
            image.toBitmap()
        } finally {
            image.close()
        }
        val upright = if (rotation == 0) bitmap else {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        }
        lastFrame = upright
        var ts = SystemClock.uptimeMillis()
        if (ts <= lastTs) ts = lastTs + 1
        lastTs = ts
        try {
            landmarker.detectAsync(BitmapImageBuilder(upright).build(), ts)
        } catch (e: Exception) {
            Log.e("MakeEmSmile", "detectAsync failed", e)
        }
    }

    private fun handle(result: FaceLandmarkerResult) {
        val frame = lastFrame
        val faces = result.faceLandmarks()
        val shapesOpt = result.faceBlendshapes()
        if (faces.isEmpty() || !shapesOpt.isPresent || shapesOpt.get().isEmpty()) {
            onResult(null, frame)
            return
        }
        val shapes = shapesOpt.get()
        val count = minOf(faces.size, shapes.size)

        // Size and centre of each face from its landmarks (normalised 0..1 coordinates).
        val area = FloatArray(count)
        val cx = FloatArray(count)
        val cy = FloatArray(count)
        for (f in 0 until count) {
            var minX = 1f; var maxX = 0f; var minY = 1f; var maxY = 0f
            for (p in faces[f]) {
                val x = p.x(); val y = p.y()
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            area[f] = (maxX - minX) * (maxY - minY)
            cx[f] = (minX + maxX) / 2f
            cy[f] = (minY + maxY) / 2f
        }

        var pick = 0
        for (f in 1 until count) if (area[f] > area[pick]) pick = f
        if (lockedX >= 0f && count > 1) {
            // Keep the previously tracked face if it is still at least 70% of the biggest.
            var nearest = -1
            var nearestDist = Float.MAX_VALUE
            for (f in 0 until count) {
                val dist = abs(cx[f] - lockedX) + abs(cy[f] - lockedY)
                if (dist < nearestDist) { nearestDist = dist; nearest = f }
            }
            if (nearest >= 0 && nearestDist < 0.25f && area[nearest] >= 0.7f * area[pick]) pick = nearest
        }
        lockedX = cx[pick]
        lockedY = cy[pick]

        val v = FloatArray(Shapes.N)
        for (c in shapes[pick]) {
            Shapes.INDEX[c.categoryName()]?.let { v[it] = c.score() }
        }
        onResult(v, frame)
    }

    fun close() = landmarker.close()
}
