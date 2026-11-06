package com.makeemsmile.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Opening jokes for the friend holding the phone, read from assets/jokes.txt (one per line,
 * lines starting with # ignored). [next] and [previous] browse through them in order, wrapping
 * around; the position is remembered, so each new round starts on a joke not seen yet.
 */
object Jokes {
    private const val FALLBACK = "I'm not lazy. I'm in energy-saving mode, like my phone at 20%."
    private const val KEY = "joke_current"
    private var cache: List<String>? = null

    private fun all(ctx: Context): List<String> {
        cache?.let { return it }
        val list = try {
            ctx.assets.open("jokes.txt").bufferedReader().readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        } catch (e: Exception) {
            emptyList()
        }
        val result = list.ifEmpty { listOf(FALLBACK) }
        cache = result
        return result
    }

    private fun move(ctx: Context, prefs: SharedPreferences, step: Int): String {
        val jokes = all(ctx)
        val n = jokes.size
        // First time ever: start somewhere random.
        val current = prefs.getInt(KEY, jokes.indices.random() - step)
        val i = ((current + step) % n + n) % n
        prefs.edit().putInt(KEY, i).apply()
        return jokes[i]
    }

    fun next(ctx: Context, prefs: SharedPreferences) = move(ctx, prefs, +1)

    fun previous(ctx: Context, prefs: SharedPreferences) = move(ctx, prefs, -1)
}
