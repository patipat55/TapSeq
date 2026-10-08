package com.quick.sasom

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class Step(val words: List<String>, val x: Int, val y: Int, val exact: Boolean)

class BuyService : AccessibilityService() {

    companion object {
        @Volatile var inst: BuyService? = null
        @Volatile var running = false
        @Volatile var steps: List<Step> = emptyList()
        @Volatile var email = ""
        @Volatile var stopBeforeLast = false
        @Volatile var lastError = ""
        var status: ((String) -> Unit)? = null
    }

    // งานหนักทั้งหมดทำบนเธรดแยก ไม่แตะเธรดหลัก (กันบริการค้าง/ANR แล้วระบบตีว่า "ทำงานผิดปกติ")
    private var thread: HandlerThread? = null
    private var h: Handler? = null
    private val pending = AtomicBoolean(false)

    @Volatile private var idx = 0
    private var startedAt = 0L
    private var stepAt = 0L
    private var lastMailAt = 0L
    private var lastSlowAt = 0L
    private var lastErrShown = ""

    private val tickRun = Runnable {
        pending.set(false)
        safeTick()
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            safeTick()
            h?.postDelayed(this, 40)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (thread == null) {
            val t = HandlerThread("sasom-work")
            t.start()
            thread = t
            h = Handler(t.looper)
        }
        inst = this
    }

    private fun cleanup() {
        running = false
        inst = null
        h?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        h = null
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (running && pending.compareAndSet(false, true)) h?.post(tickRun)
    }

    fun begin() {
        val hh = h ?: return
        hh.removeCallbacksAndMessages(null)
        pending.set(false)
        hh.post {
            idx = 0
            startedAt = SystemClock.uptimeMillis()
            stepAt = startedAt
            lastMailAt = 0L
            lastSlowAt = 0L
            lastError = ""
            lastErrShown = ""
            running = true
            say("เริ่มแล้ว รอปุ่มขั้นที่ 1")
            poll.run()
        }
    }

    fun finish(msg: String) {
        running = false
        h?.removeCallbacksAndMessages(null)
        pending.set(false)
        say(msg)
    }

    private fun say(s: String) { status?.invoke(s) }

    private fun secs(ms: Long) = String.format(Locale.US, "%.1f วิ", ms / 1000.0)

    private fun safeTick() {
        try {
            work()
        } catch (t: Throwable) {
            val msg = t.toString() + " @ " + (t.stackTrace.firstOrNull()?.toString() ?: "")
            lastError = msg
            if (msg != lastErrShown) {
                lastErrShown = msg
                say("ผิดพลาด: $msg")
            }
        }
    }

    private fun work() {
        if (!running) return
        val now = SystemClock.uptimeMillis()
        if (now - startedAt > 90_000) { finish("หมดเวลา 90 วิ หยุดแล้ว"); return }
        val root = rootInActiveWindow ?: return

        // กรอกอีเมลไม่ต้องสแกนทุกรอบ เช็คทุก 250ms พอ
        if (email.isNotEmpty() && now - lastMailAt > 250) {
            lastMailAt = now
            fillEmail(root)
        }
        if (idx >= steps.size) { finish("ครบทุกขั้นแล้ว รวม ${secs(now - startedAt)}"); return }

        val s = steps[idx]
        val node = if (s.words.isNotEmpty()) find(root, s, now) else null
        if (node != null) {
            if (stopBeforeLast && idx == steps.size - 1) {
                finish("หยุดก่อนกดขั้นสุดท้าย (เจอปุ่มแล้ว ใช้ ${secs(now - startedAt)})")
                return
            }
            if (clickNode(node)) {
                idx++
                say("ขั้น $idx/${steps.size} ผ่าน (ขั้นนี้ ${secs(now - stepAt)} / รวม ${secs(now - startedAt)})")
                stepAt = now
                if (idx >= steps.size) finish("ครบทุกขั้นแล้ว รวม ${secs(now - startedAt)}")
            }
        } else if (s.x > 0 && s.y > 0 && now - stepAt > 700) {
            tap(s.x, s.y)
            idx++
            say("แตะพิกัดขั้น $idx/${steps.size} (รวม ${secs(now - startedAt)})")
            stepAt = now
            if (idx >= steps.size) finish("ครบทุกขั้นแล้ว รวม ${secs(now - startedAt)}")
        }
    }

    /** ค้นแบบเร็วด้วยระบบก่อน (1 IPC) ถ้าไม่เจอค่อยเดินต้นไม้เองทุก ~300ms */
    private fun find(root: AccessibilityNodeInfo, s: Step, now: Long): AccessibilityNodeInfo? {
        var loose: AccessibilityNodeInfo? = null

        fun check(n: AccessibilityNodeInfo, word: String, strict: Boolean): AccessibilityNodeInfo? {
            if (!n.isVisibleToUser) return null
            for (raw in listOfNotNull(n.text?.toString(), n.contentDescription?.toString())) {
                val t = raw.trim()
                if (t.isEmpty()) continue
                if (matchExact(t, word)) return n
                if (!strict && loose == null && t.contains(word, true) && t.length <= word.length + 12) loose = n
            }
            return null
        }

        for (w in s.words) {
            val strict = s.exact || w.startsWith("=")
            val word = w.removePrefix("=")
            if (word.isEmpty()) continue
            val hits = root.findAccessibilityNodeInfosByText(word) ?: continue
            for (n in hits) check(n, word, strict)?.let { return it }
        }
        if (loose != null) return loose

        if (now - lastSlowAt < 300) return null
        lastSlowAt = now
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.addLast(root)
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            for (w in s.words) {
                val strict = s.exact || w.startsWith("=")
                check(n, w.removePrefix("="), strict)?.let { return it }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.addLast(it) }
        }
        return loose
    }

    private fun matchExact(t: String, w: String): Boolean =
        t.equals(w, true) || t.startsWith("$w ", true) || t.startsWith("$w\n", true)

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 6) {
            if (n.isClickable) {
                if (!n.isEnabled) return false
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            n = n.parent
            hops++
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        if (!r.isEmpty) { tap(r.centerX(), r.centerY()); return true }
        return false
    }

    private fun fillEmail(root: AccessibilityNodeInfo) {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.addLast(root)
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            if (n.isEditable && n.isVisibleToUser) {
                val hint = n.hintText?.toString()?.lowercase() ?: ""
                val id = n.viewIdResourceName?.lowercase() ?: ""
                val isEmail = (n.inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                        hint.contains("mail") || hint.contains("อีเมล") || id.contains("mail")
                val cur = n.text?.toString() ?: ""
                if (isEmail && cur != email && (cur.isBlank() || cur.lowercase() == hint)) {
                    val b = Bundle()
                    b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, email)
                    n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.addLast(it) }
        }
    }

    private fun tap(x: Int, y: Int) {
        if (x < 0 || y < 0) return
        val p = Path()
        p.moveTo(x.toFloat(), y.toFloat())
        p.lineTo(x.toFloat() + 1f, y.toFloat())
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 30))
            .build()
        dispatchGesture(g, null, null)
    }
}
