package com.yegorkapomidorka.headswap.autosrv

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.AppLog

/**
 * Служба спецвозможностей: только окна com.google.android.projection.gearhead (см. res/xml/autosrv_config.xml).
 * Сама ничего не делает; сценарий (ServerAutomation) гоняет AutoServer на своей нити через AccessibilityServerUi.
 */
class AutoServerService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        AppLog.init(this)
        AutoServer.service = this
        AutoServer.log("служба спецвозможностей подключена")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // события не нужны: подписка на окна AA только заставляет систему отдавать дерево узлов
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (AutoServer.service === this) AutoServer.service = null
        AutoServer.log("служба спецвозможностей отключена")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (AutoServer.service === this) AutoServer.service = null
        super.onDestroy()
    }
}

/** ServerUi поверх AccessibilityService; вызывается с рабочей нити AutoServer. */
class AccessibilityServerUi(private val svc: AutoServerService) : ServerUi {
    private fun roots(): List<AccessibilityNodeInfo> {
        val res = ArrayList<AccessibilityNodeInfo>()
        try {
            svc.rootInActiveWindow?.let { if (it.packageName == AA_PACKAGE) res.add(it) }
        } catch (_: Throwable) {
        }
        try {
            for (w in svc.windows) {
                val r = w.root ?: continue
                if (r.packageName == AA_PACKAGE && res.none { it == r }) res.add(r)
            }
        } catch (_: Throwable) {
        }
        return res
    }

    /** Обход в ширину; visit вернул true = стоп. Глубина и число узлов ограничены. */
    private fun walk(visit: (AccessibilityNodeInfo) -> Boolean) {
        var seen = 0
        for (root in roots()) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty() && seen < MAX_NODES) {
                val n = queue.removeFirst()
                seen++
                if (visit(n)) return
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            }
        }
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 5) {
            if (n.isClickable && n.isEnabled) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
            hops++
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    override fun prepareScreen(): Pair<Boolean, String> {
        val pm = svc.getSystemService(PowerManager::class.java)
        val km = svc.getSystemService(KeyguardManager::class.java)
        var note = "interactive=${pm.isInteractive} keyguard=${km.isKeyguardLocked}"
        if (!pm.isInteractive) {
            try {
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "headswap:autosrv")
                wl.acquire(10_000)
                Thread.sleep(800)
                note += " -> после включения экрана interactive=${pm.isInteractive} keyguard=${km.isKeyguardLocked}"
            } catch (e: Exception) {
                note += " (включить экран не вышло: ${e.javaClass.simpleName})"
            }
        }
        if (!pm.isInteractive) return Pair(false, "экран выключен ($note)")
        if (km.isKeyguardLocked) return Pair(false, "телефон заблокирован ($note)")
        return Pair(true, note)
    }

    override fun openAaSettings(): Boolean = try {
        svc.startActivity(
            Intent().setComponent(ComponentName(AA_PACKAGE, AA_SETTINGS_ACTIVITY))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        true
    } catch (e: Exception) {
        AutoServer.log("не открыть настройки AA: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    override fun aaVisible(): Boolean = try {
        svc.rootInActiveWindow?.packageName == AA_PACKAGE
    } catch (_: Throwable) {
        false
    }

    override fun clickMore(): Boolean {
        var target: AccessibilityNodeInfo? = null
        walk { n ->
            if (AutoServerText.isMore(n.contentDescription) && (n.isClickable || n.parent?.isClickable == true)) {
                target = n
                true
            } else false
        }
        return target?.let { click(it) } ?: false
    }

    override fun texts(): List<String> {
        val res = ArrayList<String>()
        walk { n ->
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { res.add(it) }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { res.add(it) }
            false
        }
        return res
    }

    override fun clickText(match: (CharSequence?) -> Boolean): Boolean {
        var target: AccessibilityNodeInfo? = null
        walk { n ->
            if (match(n.text) || match(n.contentDescription)) {
                target = n
                true
            } else false
        }
        return target?.let { click(it) } ?: false
    }

    override fun back() {
        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    override fun returnToApp() {
        try {
            svc.startActivity(
                Intent(svc, MainActivity::class.java).putExtra(MainActivity.EXTRA_CMD, "front")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
            AutoServer.log("возврат в приложение")
        } catch (e: Exception) {
            AutoServer.log("возврат в приложение не вышел (${e.javaClass.simpleName}), жму Home")
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }
    }

    override fun dump(): String {
        val sb = ArrayList<String>()
        walk { n ->
            val t = n.text?.toString()
            val d = n.contentDescription?.toString()
            if (!t.isNullOrBlank() || !d.isNullOrBlank() || n.isClickable) {
                sb.add("${n.className?.toString()?.substringAfterLast('.')}[${t ?: ""}|${d ?: ""}${if (n.isClickable) "|click" else ""}]")
            }
            sb.size >= 60
        }
        return sb.joinToString(" ")
    }

    override fun now() = System.currentTimeMillis()

    override fun sleep(ms: Long) {
        Thread.sleep(ms)
    }

    companion object {
        const val AA_PACKAGE = "com.google.android.projection.gearhead"
        const val AA_SETTINGS_ACTIVITY = "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"
        private const val MAX_NODES = 600
    }
}
