package com.yegorkapomidorka.headswap.ui

import android.app.AlertDialog
import android.content.Intent
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.aa.AaState
import com.yegorkapomidorka.headswap.autosrv.AutoServer

/** «Для разработчиков» (Dev.dc.html): отладочные флажки, кнопки, статус и живой лог (последние ~200 строк). */
class DevScreen(host: MainActivity) : Screen(host) {
    private val aa = AaPrefs(ctx)
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var logMeta: TextView
    private lateinit var enableSvc: View
    private var lastLog = ""
    override val view: View

    init {
        val c = ctx
        val body = c.vbox(6).apply { pad(c, 16, 4, 16, 24) }

        body.addView(c.sectionTitle(c.getString(R.string.dev_section_mic)))
        body.addView(
            group(listOf(row(c.getString(R.string.dev_mic_vr_title), c.getString(R.string.dev_mic_vr_sub), aa.micVrStatus) { aa.micVrStatus = it })),
        )

        body.addView(c.sectionTitle(c.getString(R.string.dev_section_actions)))
        val actions = listOf(
            c.getString(R.string.dev_action_scan) to { Actions.scan(c, "кнопка") },
            c.getString(R.string.dev_action_stop_bridge) to { Actions.stopBridge(c) },
            c.getString(R.string.dev_action_restart_server) to { Actions.restartServer(c) },
            c.getString(R.string.dev_action_aa_settings) to { Actions.openAaSettings(c) },
            c.getString(R.string.dev_action_aa_selftest) to { Actions.aaSelftest(c) },
            c.getString(R.string.dev_action_mic_test) to { Actions.micTest(c) },
        )
        for (pair in actions.chunked(2)) {
            val r = c.hbox(8)
            for ((label, fn) in pair) {
                r.addView(c.tonalButton(label).apply { setOnClickListener { fn() } }, lp(0, c.dp(52), 1f))
            }
            if (pair.size == 1) r.addView(View(c), lp(0, c.dp(52), 1f))
            body.addView(r, lp(MATCH, WRAP))
        }
        enableSvc = c.tonalButton(c.getString(R.string.dev_action_enable_autostart)).apply { setOnClickListener { Actions.enableServiceDialog(c) } }
        body.addView(enableSvc, lp(MATCH, c.dp(52)))

        body.addView(c.sectionTitle(c.getString(R.string.dev_section_status)))
        statusView = c.text("", 12f, 400, c.clr(R.color.on), monospace = true).apply {
            lineHeight = c.dp(18)
            background = c.shape(c.clr(R.color.surf), 16)
            pad(c, 14, 12, 14, 12)
            setTextIsSelectable(true)
        }
        body.addView(statusView, lp(MATCH, WRAP))

        val logHead = c.hbox().apply { pad(c, 8, 12, 8, 4) }
        logHead.addView(c.text(c.getString(R.string.dev_section_log), 14f, 600, c.clr(R.color.acc)), lp(0, WRAP, 1f))
        logMeta = c.text("", 13f, 400, c.clr(R.color.on2))
        logHead.addView(logMeta)
        body.addView(logHead, lp(MATCH, WRAP))

        logView = c.text("", 11f, 400, c.clr(R.color.on2), monospace = true).apply { lineHeight = c.dp(17) }
        logScroll = ScrollView(c).apply {
            background = c.shape(c.clr(R.color.surf), 16)
            pad(c, 14, 12, 14, 12)
            clipToPadding = false
            addView(logView)
            setOnTouchListener { v, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        body.addView(logScroll, c.lpDp(MATCH, 330))

        body.addView(
            c.primaryButton(c.getString(R.string.dev_log_share), R.drawable.ic_share, 56, 17f).apply { setOnClickListener { LogShare.shareLog(c) } },
            lp(MATCH, c.dp(56)).margins(c, 0, 8),
        )
        val bottom = c.hbox(8)
        bottom.addView(c.outlineButton(c.getString(R.string.dev_log_save_zip), 0, 48, 15f).apply { setOnClickListener { LogShare.saveZip(c) } }, lp(0, c.dp(48), 1f))
        bottom.addView(c.outlineButton(c.getString(R.string.dev_log_clear), 0, 48, 15f).apply { setOnClickListener { confirmClear() } }, lp(0, c.dp(48), 1f))
        body.addView(bottom, lp(MATCH, WRAP))
        body.addView(
            c.text(c.getString(R.string.dev_log_archive_note), 13f, 400, c.clr(R.color.on2))
                .apply { lineHeight = c.dp(18) },
            lp(MATCH, WRAP).margins(c, 8, 4, 8, 0),
        )

        body.addView(c.sectionTitle(c.getString(R.string.dev_section_notes)))
        body.addView(
            c.vbox(10).apply {
                background = c.shape(c.clr(R.color.surf), 20)
                pad(c, 16, 14, 16, 14)
                addView(
                    c.text(
                        c.getString(R.string.dev_notes_adb),
                        14f, 400, c.clr(R.color.on2),
                    ).apply { lineHeight = c.dp(20) },
                )
                addView(c.tonalButton(c.getString(R.string.dev_open_dev_settings)).apply {
                    setOnClickListener {
                        try {
                            c.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: Exception) {
                            Toast.makeText(c, c.getString(R.string.dev_open_failed, e.javaClass.simpleName), Toast.LENGTH_SHORT).show()
                        }
                    }
                }, lp(MATCH, c.dp(52)))
            },
            lp(MATCH, WRAP),
        )

        view = scaffold(c, c.getString(R.string.dev_title), { host.back() }, body)
    }

    private fun row(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        ToggleRow(ctx, title, sub, checked, 64, false, 16f, 13f, onChange)

    /** Карточка из строк-переключателей с разделителями. */
    private fun group(rows: List<ToggleRow>): LinearLayout {
        val c = ctx
        val card = c.card()
        rows.forEachIndexed { i, r ->
            if (i > 0) card.addView(c.divider())
            card.addView(r.view, lp(MATCH, WRAP))
        }
        return card
    }

    private fun confirmClear() {
        AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.dev_clear_title)
            .setMessage(R.string.dev_clear_message)
            .setPositiveButton(R.string.dev_clear_confirm) { _, _ ->
                AppLog.clearRing()
                val n = LogShare.deleteOldLogs(ctx)
                lastLog = "\u0000"
                Toast.makeText(ctx, ctx.resources.getQuantityString(R.plurals.dev_deleted_old_files, n, n), Toast.LENGTH_SHORT).show()
                tick()
            }
            .setNegativeButton(R.string.dev_clear_cancel, null)
            .show()
    }

    override fun onShow() {
        lastLog = "\u0000"
        tick()
    }

    override fun tick() {
        val c = ctx
        statusView.text = CarLifeState.summary() + "\n" + AaState.summary() + "\n" + AutoServer.summary(c)
        enableSvc.visibility = if (AutoServer.isConnected) View.GONE else View.VISIBLE
        val lines = AppLog.lastLines()
        val joined = lines.joinToString("\n")
        if (joined == lastLog) return
        lastLog = joined
        logMeta.text = c.getString(
            R.string.dev_log_meta,
            c.resources.getQuantityString(R.plurals.dev_log_lines, lines.size, lines.size),
            LogShare.sizeText(c, AppLog.file),
        )
        val atBottom = logScroll.scrollY + logScroll.height + c.dp(40) >= logView.height
        logView.text = colorize(lines)
        if (atBottom) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** Время приглушено, ошибки/предупреждения/успех окрашены. */
    private fun colorize(lines: List<String>): CharSequence {
        val c = ctx
        val dim = (c.clr(R.color.on2) and 0x00FFFFFF) or 0xBF000000.toInt()
        val sb = SpannableStringBuilder()
        for ((i, l) in lines.withIndex()) {
            if (i > 0) sb.append('\n')
            val split = if (l.length > 13 && l[2] == ':' && l[12] == ' ') 12 else 0
            val start = sb.length
            sb.append(l)
            if (split > 0) sb.setSpan(ForegroundColorSpan(dim), start, start + split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val msg = l.substring(split)
            val color = when {
                ERR_RE.containsMatchIn(msg) -> c.clr(R.color.err)
                WARN_RE.containsMatchIn(msg) -> c.clr(R.color.warn)
                OK_RE.containsMatchIn(msg) -> c.clr(R.color.ok)
                else -> c.clr(R.color.on)
            }
            sb.setSpan(ForegroundColorSpan(color), start + split, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private companion object {
        val ERR_RE = Regex("ERROR|ОШИБКА|FAIL|упал|Exception")
        val WARN_RE = Regex("ОТКАТ|потерян|не пришёл|недоступен|не вышло|!!!|не удалось|отброшен")
        val OK_RE = Regex("\\bOK\\b|загружен|подключена|первый IDR|успеш")
    }
}
