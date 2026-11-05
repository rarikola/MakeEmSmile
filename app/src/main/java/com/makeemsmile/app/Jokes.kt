package com.makeemsmile.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Opening jokes for the friend holding the phone, read from assets/jokes.txt (one per line,
 * lines starting with # ignored). Goes through all of them before repeating.
 */
object Jokes {
    private const val FALLBACK = "I'm not lazy. I'm in energy-saving mode, like my phone at 20%."
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

    fun next(ctx: Context, prefs: SharedPreferences): String {
        val jokes = all(ctx)
        val i = prefs.getInt("joke_index", jokes.indices.random()) % jokes.size
        prefs.edit().putInt("joke_index", (i + 1) % jokes.size).apply()
        return jokes[i]
    }
}
