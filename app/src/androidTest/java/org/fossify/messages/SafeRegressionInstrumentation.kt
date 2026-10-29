package org.fossify.messages

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import org.fossify.messages.safe.SafeStore
import org.fossify.messages.safe.SafeActivity
import org.fossify.messages.extensions.conversationsDB

/** Runs against actual Android preferences and the real SafeStore on an emulator. */
class SafeRegressionInstrumentation : Instrumentation() {
    private fun verifyKeyboard(context: Context, id: Long, result: Bundle) {
        val serviceClass = org.fossify.messages.chatheads.FloatingChatHeadsService::class.java
        val launch = android.content.Intent(context, serviceClass).apply {
            action = org.fossify.messages.chatheads.FloatingChatHeadsService.ACTION_ADD
            putExtra("thread_id", id); putExtra("title", "Keyboard motion test")
            putExtra("address", "5550100"); putExtra("expand_on_tap", true)
        }
        runOnMainSync { context.startForegroundService(launch) }
        val serviceField = serviceClass.getDeclaredField("activeService").apply { isAccessible = true }
        val panelField = serviceClass.getDeclaredField("panel").apply { isAccessible = true }
        var panel: android.view.ViewGroup? = null
        repeat(60) {
            val service = (serviceField.get(null) as? java.lang.ref.WeakReference<*>)?.get()
            if (service != null) panel = panelField.get(service) as? android.view.ViewGroup
            if (panel == null) SystemClock.sleep(50)
        }
        val view = checkNotNull(panel) { "Floating panel did not start" }
        SystemClock.sleep(650)
        waitForIdleSync()
        val panelClass = view.javaClass
        fun field(name: String) = panelClass.getDeclaredField(name).apply { isAccessible = true }
        val input = field("input").get(view) as android.widget.EditText
        val card = field("motionCard").get(view) as android.view.View
        val currentInsets = field("currentImeInsets")
        val animating = field("imeAnimating")
        val samples = mutableListOf<Triple<Int, Int, Int>>()
        val density = context.resources.displayMetrics.density
        val observer = android.view.ViewTreeObserver.OnPreDrawListener {
            val insets = currentInsets.get(view) as? android.view.WindowInsets
            if (insets != null && animating.getBoolean(view)) {
                val ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                val nav = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
                val xy = IntArray(2); card.getLocationOnScreen(xy)
                val bottom = (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).currentWindowMetrics.bounds.bottom
                val rootPosition = IntArray(2); view.getLocationOnScreen(rootPosition)
                val alreadyExcluded = (bottom - rootPosition[1] - view.height).coerceAtLeast(0)
                samples.add(Triple(ime, xy[1], xy[1] + card.height -
                    (bottom - maxOf(ime, nav, alreadyExcluded) - (12 * density).toInt())))
            }
            true
        }
        runOnMainSync {
            view.viewTreeObserver.addOnPreDrawListener(observer)
            input.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        // Exercise the real touch path so Android establishes a served input
        // connection for the overlay before requesting its IME.
        val inputPosition = IntArray(2)
        runOnMainSync { input.getLocationOnScreen(inputPosition) }
        val down = SystemClock.uptimeMillis()
        val x = inputPosition[0] + input.width / 2f
        val y = inputPosition[1] + input.height / 2f
        uiAutomation.injectInputEvent(android.view.MotionEvent.obtain(down, down,
            android.view.MotionEvent.ACTION_DOWN, x, y, 0), true)
        uiAutomation.injectInputEvent(android.view.MotionEvent.obtain(down, down + 40,
            android.view.MotionEvent.ACTION_UP, x, y, 0), true)
        SystemClock.sleep(300)
        runOnMainSync { input.windowInsetsController?.show(android.view.WindowInsets.Type.ime()) }
        var keyboardShown = false
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (!keyboardShown && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100)
            runOnMainSync {
                val frame = currentInsets.get(view) as? android.view.WindowInsets
                keyboardShown = frame != null &&
                    frame.getInsets(android.view.WindowInsets.Type.ime()).bottom >
                    frame.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom + 100
            }
        }
        SystemClock.sleep(500)
        waitForIdleSync()
        result.putString("keyboard_open_state", "windowFocus=" + input.hasWindowFocus() +
            " viewFocus=" + input.hasFocus() + " ime=" +
            (currentInsets.get(view) as? android.view.WindowInsets)?.getInsets(android.view.WindowInsets.Type.ime())?.bottom +
            " rootIme=" + view.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom)
        val opening = samples.toList()
        samples.clear()
        runOnMainSync {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(input.windowToken, 0)
        }
        SystemClock.sleep(1200)
        waitForIdleSync()
        val closing = samples.toList()
        runOnMainSync {
            view.viewTreeObserver.removeOnPreDrawListener(observer)
            context.stopService(launch)
        }
        fun validate(label: String, frames: List<Triple<Int, Int, Int>>) {
            check(frames.map { it.first }.distinct().size >= 3) { "$label: missing animated IME frames: $frames" }
            check(frames.maxOf { kotlin.math.abs(it.third) } <= 3) { "$label: card detached from keyboard: $frames" }
            check(frames.maxOf { it.second } - frames.minOf { it.second } <= 2) { "$label: card top moved during resize: $frames" }
        }
        validate("opening", opening)
        validate("closing", closing)
        result.putString("keyboard_motion", "PASS: opening=" + opening.size + " closing=" + closing.size +
            " frames; card top fixed; bottom tracks actual animated IME within 3px")
    }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val context = targetContext
        val prefs = context.getSharedPreferences("messages_private_safe_v1", Context.MODE_PRIVATE)
        val snapshot = prefs.all.toMap()
        val result = Bundle()
        var code = Activity.RESULT_OK
        try {
            check(SafeStore.saveFrameworkLock(context, "test-only-hash", 0))
            val id = 9_876_543L
            SafeStore.lock()
            check(!SafeStore.move(context, id, true)) { "Locked insert must be rejected without crashing" }
            SafeStore.unlock()
            check(SafeStore.move(context, id, true))
            check(SafeStore.isSafe(context, id))
            // Reproduce a dialog confirmed after its session deadline, without
            // changing the system clock or waiting two minutes in the test.
            SafeStore::class.java.getDeclaredField("activeUntil").apply { isAccessible = true }
                .setLong(null, SystemClock.elapsedRealtime() - 1L)
            check(!SafeStore.move(context, id, false)) { "Expired removal must return false" }
            check(SafeStore.isSafe(context, id)) { "Expired removal must preserve Safe membership" }
            SafeStore.unlock()
            check(SafeStore.move(context, id, false))
            check(!SafeStore.isSafe(context, id))
            check(SafeStore.move(context, id, false)) { "Repeated removal must be idempotent" }
            check(!SafeStore.move(context, -1L, true))
            check(SafeStore.safeThreads(context) == snapshot["threads"]
                ?.let { values -> (values as Set<*>).mapNotNull { it.toString().toLongOrNull() }.toSet() }.orEmpty())
            val conversation = (context.conversationsDB.getNonArchived() +
                context.conversationsDB.getAllArchived()).firstOrNull()
            val uiId = conversation?.threadId ?: id
            SafeStore.unlock()
            check(SafeStore.move(context, uiId, true))
            val activity = startActivitySync(android.content.Intent(context, SafeActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as SafeActivity
            waitForIdleSync()
            val render = SafeActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }
            runOnMainSync { SafeStore.unlock(); render.invoke(activity) }
            SystemClock.sleep(400)
            waitForIdleSync()
            runOnMainSync {
                val view = activity.window.decorView
                val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                java.io.File(context.getExternalFilesDir(null), "qa-safe-list.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
                SafeActivity::class.java.getDeclaredMethod("confirmMoveOut", java.lang.Long.TYPE)
                    .apply { isAccessible = true }.invoke(activity, uiId)
            }
            waitForIdleSync()
            runOnMainSync {
                SafeStore::class.java.getDeclaredField("activeUntil").apply { isAccessible = true }
                    .setLong(null, SystemClock.elapsedRealtime() - 1L)
                val move = android.view.inspector.WindowInspector.getGlobalWindowViews()
                    .mapNotNull { it.findViewById<android.widget.Button>(android.R.id.button1) }
                    .first { it.text.toString() == "Move" }
                move.performClick()
            }
            waitForIdleSync()
            check(SafeStore.isSafe(context, uiId)) { "Expired dialog must keep conversation in Safe" }
            check(!SafeStore.unlocked())
            runOnMainSync {
                val dialogs = android.view.inspector.WindowInspector.getGlobalWindowViews()
                    .filter { it !== activity.window.decorView && it.isAttachedToWindow }
                check(dialogs.isNotEmpty()) { "Expired dialog should open authentication" }
                check(dialogs.all {
                    ((it.layoutParams as android.view.WindowManager.LayoutParams).flags and
                        android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0
                }) { "Authentication dialog must block screenshots" }
                activity.finish()
            }
            waitForIdleSync()
            result.putString("safe_ui", "PASS: expired confirmation reauthenticates; membership preserved; credential window secure")
            SafeStore.unlock()
            check(SafeStore.move(context, uiId, false))
            verifyKeyboard(context, uiId, result)
            result.putString("safe_regression", "PASS: locked, expired, retry, idempotent, invalid ID, existing memberships preserved")
        } catch (error: Throwable) {
            result.putString("safe_regression", "FAIL: " + error.stackTraceToString())
            code = Activity.RESULT_CANCELED
        } finally {
            SafeStore.lock()
            val editor = prefs.edit().clear()
            snapshot.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            check(editor.commit())
        }
        finish(code, result)
    }
}
