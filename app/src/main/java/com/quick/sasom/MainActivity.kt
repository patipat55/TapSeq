package com.quick.sasom

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.WindowInsets
import android.widget.*

class MainActivity : Activity() {

    private lateinit var link: EditText
    private lateinit var pkg: EditText
    private lateinit var size: EditText
    private lateinit var mail: EditText
    private lateinit var stepsEt: EditText
    private lateinit var stopLast: CheckBox
    private lateinit var statusTv: TextView
    private val prefs by lazy { getSharedPreferences("q", Context.MODE_PRIVATE) }

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        val sv = ScrollView(this)
        sv.addView(root)
        sv.setOnApplyWindowInsetsListener { v, ins ->
            val bars = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            ins
        }
        setContentView(sv)

        fun label(t: String) {
            root.addView(TextView(this).apply { text = t; setPadding(0, dp(12), 0, 0) })
        }
        fun field(hint: String, key: String, def: String = "", lines: Int = 1): EditText {
            val e = EditText(this)
            e.hint = hint
            e.setText(prefs.getString(key, def))
            if (lines > 1) {
                e.minLines = lines
                e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            } else {
                e.setSingleLine()
            }
            root.addView(e)
            return e
        }
        fun btn(t: String, f: () -> Unit) {
            root.addView(Button(this).apply { text = t; setOnClickListener { f() } })
        }

        label("ลิงก์หน้าสินค้า (วาง / แชร์ลิงก์มาที่แอพนี้ / ใช้คลิปบอร์ด)")
        link = field("https://...", "link")
        btn("▶ เริ่มเลย") { go(link.text.toString().trim()) }
        btn("📋 วางจากคลิปบอร์ด + เริ่มทันที") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
            val u = extractUrl(t)
            link.setText(u)
            go(u)
        }
        btn("⏹ หยุด") { BuyService.inst?.finish("หยุดแล้ว") }
        statusTv = TextView(this).apply { text = "พร้อม" }
        root.addView(statusTv)
        BuyService.status = { s -> runOnUiThread { statusTv.text = s } }

        label("ไซส์ พิมพ์ให้ตรงกับในแอพ เช่น US 9")
        size = field("ไซส์", "size")
        label("อีเมล (กรอกให้เองถ้าเจอช่องอีเมล)")
        mail = field("email@example.com", "mail")
        label("แพ็กเกจแอพ Sasom (เว้นว่างได้ ถ้าใส่จะบังคับเปิดในแอพนั้น)")
        pkg = field("เช่น com.xxx.sasom", "pkg")
        btn("🔍 หาแพ็กเกจ Sasom ในเครื่อง") { findSasom() }
        label("ขั้นตอน 1 บรรทัด = 1 ขั้น (=คำ คือต้องตรงเป๊ะ, {size} คือขั้นเลือกไซส์, @x,y คือพิกัดสำรอง)")
        stepsEt = field(
            "", "steps2",
            "=ซื้อ\n{size}\nซื้อเลย\nดำเนินการชำระเงิน",
            6
        )
        stopLast = CheckBox(this).apply {
            text = "หยุดก่อนกดขั้นสุดท้าย (ไว้ให้กดยืนยันเอง)"
            isChecked = prefs.getBoolean("stopLast", false)
        }
        root.addView(stopLast)
        btn("เปิดตั้งค่าการช่วยเหลือพิเศษ") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        if (b == null) handleSend(intent)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        handleSend(i)
    }

    private fun handleSend(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val u = extractUrl(t)
            link.setText(u)
            go(u)
        }
    }

    private fun extractUrl(t: String): String =
        Regex("https?://\\S+").find(t)?.value ?: t.trim()

    private fun buildSteps(): List<Step> {
        val out = mutableListOf<Step>()
        val sz = size.text.toString().trim()
        val lines = stepsEt.text.toString().lines()
        if (sz.isNotEmpty() && lines.none { it.trim() == "{size}" }) out.add(Step(listOf(sz), 0, 0, true))
        for (line in lines) {
            if (line.isBlank()) continue
            if (line.trim() == "{size}") {
                if (sz.isNotEmpty()) out.add(Step(listOf(sz), 0, 0, true))
                continue
            }
            val parts = line.split("@")
            val words = parts[0].split(",").map { it.trim() }.filter { it.isNotEmpty() }
            var x = 0
            var y = 0
            if (parts.size > 1) {
                val c = parts[1].split(",")
                x = c.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
                y = c.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
            }
            if (words.isNotEmpty() || x > 0) out.add(Step(words, x, y, false))
        }
        return out
    }

    override fun onResume() {
        super.onResume()
        when {
            BuyService.lastError.isNotEmpty() -> statusTv.text = "ผิดพลาดล่าสุด: " + BuyService.lastError
            BuyService.inst == null -> statusTv.text = "บริการยังไม่ทำงาน — เปิด Sasom Quick ในการช่วยเหลือพิเศษก่อน"
        }
    }

    @Suppress("DEPRECATION")
    private fun findSasom() {
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val hits = packageManager.queryIntentActivities(launch, 0).filter {
            it.activityInfo.packageName.contains("sasom", true) ||
                    it.loadLabel(packageManager).toString().contains("sasom", true)
        }.map { it.activityInfo.packageName }.distinct()
        if (hits.isEmpty()) {
            statusTv.text = "ไม่เจอแอพที่ชื่อมีคำว่า sasom ในเครื่อง (เช็คว่าติดตั้งแอพ Sasom แล้ว)"
        } else {
            pkg.setText(hits[0])
            statusTv.text = "เจอ: " + hits.joinToString(", ")
        }
    }

    @Suppress("DEPRECATION")
    private fun isInstalled(p: String): Boolean =
        try { packageManager.getPackageInfo(p, 0); true } catch (e: Exception) { false }

    /** ถ้าไม่ได้กรอกแพ็กเกจ ลองหาแอพ Sasom ที่รับลิงก์นี้เอง จะได้ไม่ต้องรอเลือกแอพ/เบราว์เซอร์ */
    @Suppress("DEPRECATION")
    private fun autoPackage(i: Intent): String? =
        packageManager.queryIntentActivities(i, 0)
            .map { it.activityInfo.packageName }
            .firstOrNull { it.contains("sasom", true) }

    private fun go(url: String) {
        if (url.isEmpty()) { Toast.makeText(this, "ยังไม่มีลิงก์", Toast.LENGTH_SHORT).show(); return }
        prefs.edit()
            .putString("link", link.text.toString()).putString("pkg", pkg.text.toString())
            .putString("size", size.text.toString()).putString("mail", mail.text.toString())
            .putString("steps2", stepsEt.text.toString()).putBoolean("stopLast", stopLast.isChecked)
            .apply()
        val svc = BuyService.inst
        if (svc == null) {
            Toast.makeText(this, "เปิดบริการ Sasom Quick ในการช่วยเหลือพิเศษก่อน", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val typed = pkg.text.toString().trim()
        if (typed.isNotEmpty()) {
            if (!isInstalled(typed)) {
                statusTv.text = "ไม่พบแอพแพ็กเกจ \"$typed\" ในเครื่อง — กด 🔍 หาแพ็กเกจ หรือเว้นว่างไว้"
                return
            }
            i.setPackage(typed)
        } else {
            autoPackage(i)?.let { i.setPackage(it) }
        }

        BuyService.steps = buildSteps()
        BuyService.email = mail.text.toString().trim()
        BuyService.stopBeforeLast = stopLast.isChecked
        svc.begin()
        try {
            startActivity(i)
        } catch (e: Exception) {
            if (i.`package` != null) {
                // แอพนั้นไม่รับลิงก์นี้ ลองเปิดแบบให้ระบบเลือกแทน
                i.setPackage(null)
                try {
                    startActivity(i)
                    statusTv.text = "แพ็กเกจนี้เปิดลิงก์ไม่ได้ เลยให้ระบบเลือกแอพเปิดแทน"
                    return
                } catch (e2: Exception) { /* ตกไปแจ้ง error ด้านล่าง */ }
            }
            svc.finish("เปิดลิงก์ไม่ได้")
            Toast.makeText(this, "เปิดลิงก์ไม่ได้", Toast.LENGTH_SHORT).show()
        }
    }
}
