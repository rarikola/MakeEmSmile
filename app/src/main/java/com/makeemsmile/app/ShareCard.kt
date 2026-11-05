package com.makeemsmile.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/** Builds a 1080×1350 result card and opens the share sheet. */
object ShareCard {

    fun mirror(b: Bitmap): Bitmap {
        val m = Matrix().apply { preScale(-1f, 1f) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    fun share(act: Activity, big: String, line: String, timeline: String, frame: Bitmap?, text: String) {
        val card = build(act, big, line, timeline, frame)
        val dir = File(act.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "makeemsmile.png")
        FileOutputStream(file).use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(act, act.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        act.startActivity(Intent.createChooser(intent, null))
    }

    private fun build(act: Activity, big: String, line: String, timeline: String, frame: Bitmap?): Bitmap {
        val w = 1080
        val h = 1350
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(0xFF161616.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

        p.color = 0xFFFFFF54.toInt()
        p.isFakeBoldText = true
        p.textSize = 56f
        c.drawText(act.getString(R.string.app_name), w / 2f, 100f, p)

        p.color = Color.WHITE
        p.textSize = 44f
        p.isFakeBoldText = false
        c.drawText(line, w / 2f, 175f, p)

        p.isFakeBoldText = true
        p.textSize = 160f
        c.drawText(big, w / 2f, 340f, p)
        p.isFakeBoldText = false

        val box = RectF(270f, 390f, 810f, 1030f)
        if (frame != null) {
            val scale = maxOf(box.width() / frame.width, box.height() / frame.height)
            val dw = frame.width * scale
            val dh = frame.height * scale
            val dst = RectF(box.centerX() - dw / 2, box.centerY() - dh / 2, box.centerX() + dw / 2, box.centerY() + dh / 2)
            c.save()
            c.clipPath(Path().apply { addRoundRect(box, 40f, 40f, Path.Direction.CW) })
            c.drawBitmap(frame, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            c.restore()
        } else {
            p.color = 0xFF2A2A2A.toInt()
            c.drawRoundRect(box, 40f, 40f, p)
        }

        // Emoji timeline, shrunk to fit the width if it is long.
        p.color = Color.WHITE
        p.textSize = 76f
        val tw = p.measureText(timeline)
        if (tw > w - 80) p.textSize = p.textSize * (w - 80) / tw
        c.drawText(timeline, w / 2f, 1150f, p)

        p.color = 0xFF777777.toInt()
        p.textSize = 38f
        c.drawText(act.getString(R.string.share_footer), w / 2f, 1280f, p)
        return b
    }
}
