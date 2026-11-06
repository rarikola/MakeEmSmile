package com.makeemsmile.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * MakeEmSmile: the friend holding the phone has 1 minute to make the player smile.
 *
 * Screens: intro (rules, a joke, player's name) → baseline (player relaxes, 3 s) → round (60 s)
 * → cracked (1 s, sound) → results (time, frozen face, emoji timeline, scoreboard).
 */
class MainActivity : ComponentActivity() {

    companion object {
        const val ROUND_MS = 60_000L
        const val BASELINE_MS = 3000L
        const val CRACK_HOLD_MS = 400L
        const val FEEDBACK_URL = "https://makeemsmile.app/feedback/"
        const val AFTER_MS = 1000L
    }

    private enum class Screen { INTRO, BASELINE, PLAY, CRACKED, RESULTS }

    private data class Score(val name: String, val ms: Long, val cracked: Boolean)

    // Views
    private lateinit var previewView: PreviewView
    private lateinit var introGroup: View
    private lateinit var introJoke: TextView
    private lateinit var nameInput: EditText
    private lateinit var logo: View
    private lateinit var roundGroup: View
    private lateinit var clock: TextView
    private lateinit var playerLabel: TextView
    private lateinit var pill: TextView
    private lateinit var message: TextView
    private lateinit var smileBar: SmileBarView
    private lateinit var emojiBar: EmojiBarView
    private lateinit var resultsGroup: View
    private lateinit var resTitle: TextView
    private lateinit var resTime: TextView
    private lateinit var resHow: TextView
    private lateinit var resImage: ImageView
    private lateinit var resTimeline: TextView
    private lateinit var resBoard: LinearLayout

    // Camera and face
    private val detector = ReactionDetector()
    private var tracker: FaceTracker? = null
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var backCamera = true

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("makeemsmile", MODE_PRIVATE) }
    private var soundPool: SoundPool? = null
    private var crackSound = 0

    // Round state
    private var screen = Screen.INTRO
    private var playerName = ""
    private val baselineSamples = ArrayList<FloatArray>()
    private var elapsed = 0L
    private var lastTick = 0L
    private var crackSince = -1L
    private var crackAt = 0L
    private var peakLevel = ReactionDetector.BORING
    private var nearMisses = 0
    private var armed = true
    private var findingFace = false
    private val timeline = ArrayList<Reaction>()
    private var lastFrame: Bitmap? = null
    private var resultFrame: Bitmap? = null
    private var resultFrameBack = true
    private var lastResult: Score? = null
    private var lastTimeline = ""
    private val scores = ArrayList<Score>()

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else Toast.makeText(this, R.string.need_camera, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.preview)
        introGroup = findViewById(R.id.intro_group)
        introJoke = findViewById(R.id.intro_joke)
        nameInput = findViewById(R.id.name_input)
        logo = findViewById(R.id.logo)
        roundGroup = findViewById(R.id.round_group)
        clock = findViewById(R.id.clock)
        playerLabel = findViewById(R.id.player_label)
        pill = findViewById(R.id.pill)
        message = findViewById(R.id.message)
        smileBar = findViewById(R.id.smile_bar)
        emojiBar = findViewById(R.id.emoji_bar)
        resultsGroup = findViewById(R.id.results_group)
        resTitle = findViewById(R.id.res_title)
        resTime = findViewById(R.id.res_time)
        resHow = findViewById(R.id.res_how)
        resImage = findViewById(R.id.res_image)
        resTimeline = findViewById(R.id.res_timeline)
        resBoard = findViewById(R.id.res_board)

        // The app draws edge to edge (required on Android 15+); keep the controls clear of the
        // status bar, navigation bar and keyboard.
        val uiRoot = findViewById<View>(R.id.ui_root)
        ViewCompat.setOnApplyWindowInsetsListener(uiRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            insets
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        setUpSound()

        findViewById<Button>(R.id.start_button).setOnClickListener { startRound() }
        nameInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { hideKeyboard(); true } else false
        }
        // Hidden for testing: long-press the logo to switch between back and front camera.
        // Not saved, so the app always starts with the back camera.
        logo.setOnLongClickListener {
            backCamera = !backCamera
            tracker?.let { rebind(it) }
            Toast.makeText(this, if (backCamera) R.string.camera_back else R.string.camera_front, Toast.LENGTH_SHORT).show()
            true
        }

        // Browse jokes with the arrows or by swiping the joke left/right.
        findViewById<View>(R.id.joke_prev).setOnClickListener { introJoke.text = Jokes.previous(this, prefs) }
        findViewById<View>(R.id.joke_next).setOnClickListener { introJoke.text = Jokes.next(this, prefs) }
        val swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x)
                if (abs(dx) < dp(40) || abs(velocityX) < abs(velocityY)) return false
                introJoke.text = if (dx < 0) Jokes.next(this@MainActivity, prefs) else Jokes.previous(this@MainActivity, prefs)
                return true
            }
        })
        introJoke.setOnTouchListener { v, event ->
            val handled = swipe.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && !handled) v.performClick()
            handled
        }

        findViewById<View>(R.id.feedback_link).setOnClickListener { openFeedback() }
        findViewById<Button>(R.id.res_rematch).setOnClickListener { showIntro(clearName = false) }
        findViewById<Button>(R.id.res_next).setOnClickListener { showIntro(clearName = true) }
        findViewById<Button>(R.id.res_share).setOnClickListener { share() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (screen == Screen.INTRO) finish() else showIntro(clearName = false)
            }
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
        showIntro(clearName = true)
    }

    // ---------------------------------------------------------------- screens

    private fun showIntro(clearName: Boolean) {
        handler.removeCallbacksAndMessages(null)
        screen = Screen.INTRO
        introJoke.text = Jokes.next(this, prefs)
        if (clearName) nameInput.setText("")
        introGroup.visibility = View.VISIBLE
        roundGroup.visibility = View.GONE
        resultsGroup.visibility = View.GONE
    }

    private fun startRound() {
        hideKeyboard()
        val typed = nameInput.text.toString().trim()
        playerName = typed.ifEmpty { getString(R.string.default_player, scores.size + 1) }

        elapsed = 0L
        lastTick = 0L
        crackSince = -1L
        crackAt = 0L
        peakLevel = ReactionDetector.BORING
        nearMisses = 0
        armed = true
        findingFace = false
        timeline.clear()
        lastFrame = null
        resultFrame = null
        detector.reset()
        tracker?.resetLock()

        introGroup.visibility = View.GONE
        resultsGroup.visibility = View.GONE
        roundGroup.visibility = View.VISIBLE
        playerLabel.text = playerName
        clock.text = format(ROUND_MS, countUp = false)
        pill.visibility = View.GONE
        smileBar.reset()
        emojiBar.setReaction(Reaction.NOTHING)
        runBaseline()
    }

    private fun runBaseline() {
        screen = Screen.BASELINE
        baselineSamples.clear()
        message.visibility = View.VISIBLE
        val text = getString(R.string.baseline, playerName)
        for (i in 0..2) {
            handler.postDelayed({ message.text = "$text\n\n${3 - i}" }, i * 1000L)
        }
        handler.postDelayed({ finishBaseline() }, BASELINE_MS)
    }

    private fun finishBaseline() {
        if (baselineSamples.size < 5) {
            message.text = getString(R.string.baseline_no_face)
            handler.postDelayed({ runBaseline() }, 1500L)
            return
        }
        val avg = FloatArray(Shapes.N)
        for (v in baselineSamples) for (i in avg.indices) avg[i] += v[i]
        for (i in avg.indices) avg[i] = avg[i] / baselineSamples.size
        detector.setBaseline(avg)

        screen = Screen.PLAY
        lastTick = 0L
        message.text = getString(R.string.go)
        handler.postDelayed({ if (screen == Screen.PLAY && !findingFace) message.visibility = View.GONE }, 1200L)
    }

    // ---------------------------------------------------------------- every frame

    private fun onFace(v: FloatArray?, frame: Bitmap?) {
        when (screen) {
            Screen.INTRO, Screen.RESULTS -> return
            Screen.BASELINE -> {
                if (v != null) baselineSamples.add(v)
                return
            }
            Screen.PLAY, Screen.CRACKED -> Unit
        }

        val now = SystemClock.uptimeMillis()
        val dt = if (lastTick == 0L) 0L else (now - lastTick).coerceAtMost(200L)
        lastTick = now

        if (v == null) {
            if (screen == Screen.PLAY) {
                // The clock pauses until the face is back.
                message.text = getString(R.string.find_face)
                message.visibility = View.VISIBLE
                findingFace = true
                crackSince = -1L
                pill.visibility = View.GONE
            }
            return
        }
        if (findingFace) {
            findingFace = false
            message.visibility = View.GONE
        }
        if (frame != null) lastFrame = frame

        detector.update(v, now)
        val level = detector.level
        smileBar.setValue(detector.barPos, level)
        emojiBar.setReaction(detector.reaction)

        if (screen == Screen.CRACKED) {
            peakLevel = max(peakLevel, level)
            return
        }

        elapsed += dt
        val r = detector.reaction
        if (r != Reaction.NOTHING && (timeline.isEmpty() || timeline.last() != r)) timeline.add(r)

        // "almost!" while a slight smile shows; each new one (after going back to straight) is a near-miss.
        if (level >= ReactionDetector.SLIGHT && armed) {
            nearMisses++
            armed = false
        }
        if (level == ReactionDetector.BORING) armed = true
        if (level == ReactionDetector.SLIGHT) {
            pill.text = getString(R.string.almost)
            pill.setBackgroundResource(R.drawable.bg_almost)
            pill.setTextColor(0xFF5A5000.toInt())
            pill.visibility = View.VISIBLE
        } else if (level == ReactionDetector.BORING) {
            pill.visibility = View.GONE
        }

        // Cracked: a real smile (mouth AND eyes), held for a moment.
        if (level >= ReactionDetector.SMILE) {
            if (crackSince < 0) crackSince = now
            if (now - crackSince >= CRACK_HOLD_MS) {
                crack(frame)
                return
            }
        } else {
            crackSince = -1L
        }

        clock.text = format(ROUND_MS - elapsed, countUp = false)
        if (elapsed >= ROUND_MS) finishRound(cracked = false)
    }

    private fun crack(frame: Bitmap?) {
        screen = Screen.CRACKED
        crackAt = elapsed
        resultFrame = frame ?: lastFrame
        resultFrameBack = backCamera
        peakLevel = detector.level
        if (nearMisses > 0) nearMisses--   // the crack itself started as an "almost"
        soundPool?.play(crackSound, 1f, 1f, 1, 0, 1f)
        pill.text = getString(R.string.cracked_pill)
        pill.setBackgroundResource(R.drawable.bg_cracked)
        pill.setTextColor(0xFFFFFFFF.toInt())
        pill.visibility = View.VISIBLE
        // Keep watching for a second to see how hard they cracked.
        handler.postDelayed({ finishRound(cracked = true) }, AFTER_MS)
    }

    // ---------------------------------------------------------------- results

    private fun finishRound(cracked: Boolean) {
        handler.removeCallbacksAndMessages(null)
        screen = Screen.RESULTS
        if (!cracked) {
            resultFrame = lastFrame
            resultFrameBack = backCamera
        }
        val lasted = if (cracked) crackAt.coerceAtMost(ROUND_MS) else ROUND_MS
        val score = Score(playerName, lasted, cracked)
        scores.add(score)
        lastResult = score

        val best = prefs.getLong("best_ms", -1L)
        if (lasted > best) {
            prefs.edit().putLong("best_ms", lasted).putString("best_name", playerName).apply()
        }

        roundGroup.visibility = View.GONE
        resultsGroup.visibility = View.VISIBLE

        resTitle.text = getString(if (cracked) R.string.res_cracked else R.string.res_survived, playerName)
        resTime.text = format(lasted, countUp = true)
        val how = when {
            !cracked -> getString(R.string.how_survived)
            peakLevel >= ReactionDetector.LAUGH -> getString(R.string.how_laugh)
            else -> getString(R.string.how_smile)
        }
        val misses = if (nearMisses > 0) "\n" + resources.getQuantityString(R.plurals.near_misses, nearMisses, nearMisses) else ""
        resHow.text = how + misses

        resImage.setImageBitmap(resultFrame?.let { oriented(it, resultFrameBack) })

        val end = when {
            !cracked -> Emoji.TROPHY
            peakLevel >= ReactionDetector.LAUGH -> Emoji.LAUGH + Emoji.BANG
            else -> Emoji.SMILE + Emoji.BANG
        }
        lastTimeline = buildTimeline(end)
        resTimeline.text = lastTimeline

        fillBoard()
    }

    /** 😐 then each reaction as it changed (long rounds are shortened in the middle), then the ending. */
    private fun buildTimeline(end: String): String {
        val list = timeline.map { it.emoji }
        val shown = if (list.size <= 9) list else list.take(4) + listOf("…") + list.takeLast(4)
        return (listOf(Reaction.NOTHING.emoji) + shown + listOf(end)).joinToString(" ")
    }

    private fun fillBoard() {
        resBoard.removeAllViews()
        val sorted = scores.sortedByDescending { it.ms }.take(8)
        for ((i, s) in sorted.withIndex()) {
            val row = TextView(this)
            val mark = if (s.cracked) Emoji.SMILE else Emoji.TROPHY
            row.text = getString(R.string.board_row, i + 1, s.name, format(s.ms, countUp = true), mark)
            row.setTextColor(0xFFFFFFFF.toInt())
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            if (s === lastResult) row.setTypeface(row.typeface, Typeface.BOLD) else row.alpha = 0.8f
            row.setPadding(0, dp(4), 0, dp(4))
            resBoard.addView(row)
        }
        val bestMs = prefs.getLong("best_ms", -1L)
        val bestName = prefs.getString("best_name", null)
        if (bestMs > 0 && bestName != null) {
            val best = TextView(this)
            best.text = getString(R.string.best_ever, bestName, format(bestMs, countUp = true))
            best.setTextColor(0xFFAAAAAA.toInt())
            best.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            best.setPadding(0, dp(10), 0, 0)
            resBoard.addView(best)
        }
    }

    private fun share() {
        val s = lastResult ?: return
        val time = format(s.ms, countUp = true)
        ShareCard.share(
            this,
            big = time,
            line = getString(if (s.cracked) R.string.share_line_cracked else R.string.share_line_survived, s.name),
            timeline = lastTimeline,
            frame = resultFrame?.let { oriented(it, resultFrameBack) },
            text = getString(if (s.cracked) R.string.share_text_cracked else R.string.share_text_survived, s.name, time),
        )
    }

    /** Front-camera frames are mirrored so they look like a selfie; back-camera frames are not. */
    private fun oriented(b: Bitmap, fromBack: Boolean): Bitmap = if (fromBack) b else ShareCard.mirror(b)

    // ---------------------------------------------------------------- camera

    private fun openFeedback() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FEEDBACK_URL)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, FEEDBACK_URL, Toast.LENGTH_LONG).show()
        }
    }

    private fun startCamera() {
        cameraExecutor.execute {
            val t = try {
                FaceTracker(this) { v, frame -> runOnUiThread { onFace(v, frame) } }
            } catch (e: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.model_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
                return@execute
            }
            tracker = t
            runOnUiThread { bindCamera(t) }
        }
    }

    private fun bindCamera(t: FaceTracker) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            rebind(t)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun rebind(t: FaceTracker) {
        val provider = cameraProvider ?: return
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        analysis.setAnalyzer(cameraExecutor) { img -> t.process(img) }
        val selector = if (backCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        provider.unbindAll()
        try {
            provider.bindToLifecycle(this, selector, preview, analysis)
        } catch (e: Exception) {
            // This phone doesn't have that camera; use the other one.
            backCamera = !backCamera
            provider.unbindAll()
            val other = if (backCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            provider.bindToLifecycle(this, other, preview, analysis)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun setUpSound() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val pool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build()
        crackSound = pool.load(this, R.raw.crack, 1)
        soundPool = pool
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(nameInput.windowToken, 0)
        nameInput.clearFocus()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** m:ss. A countdown rounds up (shows 1:00 at the start); a result rounds down (47.8 s is 0:47). */
    private fun format(ms: Long, countUp: Boolean): String {
        val sec = (if (countUp) ms / 1000 else (ms + 999) / 1000).coerceAtLeast(0)
        return "%d:%02d".format(sec / 60, sec % 60)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        soundPool?.release()
        soundPool = null
        cameraExecutor.execute { tracker?.close() }
        cameraExecutor.shutdown()
    }
}
