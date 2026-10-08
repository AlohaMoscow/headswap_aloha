package com.yegorkapomidorka.headswap.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.aa.MicMode
import com.yegorkapomidorka.headswap.autosrv.AutoServer

/** Шапка «назад + заголовок» и прокручиваемое тело. */
fun scaffold(ctx: Context, title: String, onBack: () -> Unit, body: View): LinearLayout = ctx.vbox().apply {
    val header = ctx.hbox(4).apply {
        pad(ctx, 4, 8, 8, 0)
        addView(ctx.iconButton(R.drawable.ic_back, ctx.clr(R.color.on), "Назад").apply { setOnClickListener { onBack() } })
        addView(ctx.text(title, 22f, 600))
    }
    addView(header, ctx.lpDp(MATCH, 64))
    addView(ScrollView(ctx).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(body, ViewGroup.LayoutParams(MATCH, WRAP))
    }, lp(MATCH, 0, 1f))
}

/** Настройки (Settings.dc.html) + автозапуск сервера ГУ, микрофон, чеклист и ссылки. */
class SettingsScreen(host: MainActivity) : Screen(host) {
    private val aa = AaPrefs(ctx)
    private val micRows = HashMap<MicMode, RadioRow>()
    private lateinit var transcodeRow: RadioRow
    private lateinit var directRow: RadioRow
    private lateinit var jvc: TextView
    private lateinit var hur: TextView
    private lateinit var svcStatus: TextView
    private lateinit var svcButton: TextView
    private lateinit var micNote: TextView
    private lateinit var body: LinearLayout
    override val view: View

    init {
        val c = ctx
        body = c.vbox(6).apply { pad(c, 16, 4, 16, 16) }

        body.addView(c.sectionTitle("Звук"))
        body.addView(ToggleRow(c, "Звук через USB", "В динамики машины", aa.usbAudio) { aa.usbAudio = it }.view)

        body.addView(c.sectionTitle("Видео"))
        transcodeRow = RadioRow(c, "Перекодирование", "Надёжнее · +60 мс") { setTranscode(true) }
        directRow = RadioRow(c, "Прямой проброс", "Быстрее · подходит не всем ГУ") { setTranscode(false) }
        body.addView(transcodeRow.view, lp(MATCH, WRAP))
        body.addView(directRow.view, lp(MATCH, WRAP))
        setTranscode(aa.transcode, save = false)

        body.addView(c.sectionTitle("Сертификат ГУ"))
        body.addView(certCard())

        body.addView(c.sectionTitle("Автозапуск сервера ГУ"))
        body.addView(autostartCard())

        body.addView(c.sectionTitle("Микрофон"))
        micNote = c.text("", 13f, 400, c.clr(R.color.warn))
        for ((m, title, sub) in listOf(
            Triple(MicMode.CAR, "Машина", "Микрофон магнитолы"),
            Triple(MicMode.PHONE, "Телефон", "Микрофон телефона, нужен доступ к записи звука"),
            Triple(MicMode.AUTO, "Авто", "Сначала машина, при молчании 2 с — телефон"),
        )) {
            val row = RadioRow(c, title, sub) { setMic(m) }
            micRows[m] = row
            body.addView(row.view, lp(MATCH, WRAP))
        }
        body.addView(micNote, lp(WRAP, WRAP).margins(c, 8, 2, 8, 0))
        refreshMic()

        val links = c.card().apply {
            addView(link(R.drawable.ic_report, "Сообщить о проблеме", "лог + отчёт", false) { LogShare.shareReport(c) })
            addView(c.divider())
            addView(link(R.drawable.ic_refresh_btn, "Пройти настройку заново", null, false) { host.showOnboarding(rerun = true) })
            addView(c.divider())
            addView(link(R.drawable.ic_check, "Проверить готовность", null, false) { host.showOnboarding(checklist = true) })
            addView(c.divider())
            addView(link(R.drawable.ic_terminal, "Для разработчиков", null, true) { host.showDev() })
        }
        body.addView(links, lp(MATCH, WRAP).margins(c, 0, 12, 0, 0))

        body.addView(View(c), lp(MATCH, c.dp(12), 1f))
        val ver = try {
            c.packageManager.getPackageInfo(c.packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        body.addView(c.text("Headswap $ver · мост для ГУ с CarLife", 13f, 400, c.clr(R.color.on2)).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP))

        view = scaffold(c, "Настройки", { host.back() }, body)
    }

    override fun onShow() {
        refreshService()
        refreshMic()
    }

    override fun tick() = refreshService()

    override fun onPermissionResult() = refreshMic()

    private fun link(icon: Int, title: String, note: String?, chevron: Boolean, onClick: () -> Unit): View {
        val c = ctx
        return c.hbox(14).apply {
            pad(c, 16, 0, 16, 0)
            background = c.rippleBg(null, 0)
            isClickable = true
            setOnClickListener { onClick() }
            addView(c.icon(icon, 22, c.clr(R.color.on2)))
            addView(c.text(title, 16f, 500), lp(0, WRAP, 1f))
            if (note != null) addView(c.text(note, 13f, 400, c.clr(R.color.on2)))
            if (chevron) addView(c.icon(R.drawable.ic_chevron, 20, c.clr(R.color.on2)))
            layoutParams = c.lpDp(MATCH, 56)
        }
    }

    private fun setTranscode(on: Boolean, save: Boolean = true) {
        if (save) aa.transcode = on
        transcodeRow.setSelected(on)
        directRow.setSelected(!on)
    }

    private fun certCard(): View {
        val c = ctx
        val card = c.vbox(8).apply {
            background = c.shape(c.clr(R.color.surf), 20)
            pad(c, 12, 12, 12, 12)
        }
        val seg = c.hbox().apply {
            background = c.shape(0, 24, c.clr(R.color.line), 1.5f)
            clipToOutline = true
        }
        fun segBtn(set: String): TextView = c.text(set.uppercase(), 16f, 600).apply {
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { aa.certSet = set; refreshCert() }
        }
        jvc = segBtn("jvc")
        hur = segBtn("hur")
        seg.addView(jvc, lp(0, c.dp(48), 1f))
        seg.addView(hur, lp(0, c.dp(48), 1f))
        refreshCert()
        card.addView(seg, lp(MATCH, WRAP))
        card.addView(c.text("Смените, если Android Auto не подключается", 14f, 400, c.clr(R.color.on2)).apply { pad(c, 6, 0, 6, 0) })
        return card
    }

    private fun refreshCert() {
        val c = ctx
        for ((tv, set) in listOf(jvc to "jvc", hur to "hur")) {
            val sel = aa.certSet == set
            tv.background = c.rippleBg(if (sel) c.shape(c.clr(R.color.pric), 0) else null, 0)
            tv.setTextColor(c.clr(if (sel) R.color.onpric else R.color.on))
            tv.text = if (sel) "✓ ${set.uppercase()}" else set.uppercase()
        }
    }

    private fun autostartCard(): View {
        val c = ctx
        val card = c.vbox().apply { background = c.shape(c.clr(R.color.surf), 20) }
        card.addView(
            ToggleRow(c, "Автозапуск сервера ГУ", "При подключении кабеля и после сессии AA", aa.autoServer, 72, false, 17f, 14f) { aa.autoServer = it }.view,
            lp(MATCH, WRAP),
        )
        card.addView(c.divider())
        val row = c.hbox(12).apply { pad(c, 16, 10, 12, 10); minimumHeight = c.dp(64) }
        svcStatus = c.text("", 14f, 400, c.clr(R.color.on2))
        svcButton = c.text("Включить", 15f, 600, c.clr(R.color.acc)).apply {
            gravity = Gravity.CENTER
            pad(c, 14, 0, 14, 0)
            background = c.rippleBg(null, 20)
            isClickable = true
            setOnClickListener { Actions.enableServiceDialog(c) }
        }
        row.addView(svcStatus, lp(0, WRAP, 1f))
        row.addView(svcButton, c.lpDp(WRAP, 44))
        card.addView(row, lp(MATCH, WRAP))
        refreshService()
        return card
    }

    private fun refreshService() {
        val c = ctx
        val connected = AutoServer.isConnected
        svcStatus.text = when {
            connected -> "Служба спецвозможностей подключена"
            AutoServer.isListed(c) -> "Служба включена, но не подключена"
            else -> "Служба спецвозможностей выключена"
        }
        svcStatus.setTextColor(c.clr(if (connected) R.color.ok else R.color.warn))
        svcButton.visibility = if (connected) View.GONE else View.VISIBLE
    }

    private fun setMic(m: MicMode) {
        aa.micMode = m
        if (m != MicMode.CAR && ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            host.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
        }
        refreshMic()
    }

    private fun refreshMic() {
        for ((m, row) in micRows) row.setSelected(aa.micMode == m)
        val missing = aa.micMode != MicMode.CAR && ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        micNote.text = if (missing) "Нет доступа к записи звука: микрофон телефона не заработает." else ""
        micNote.visibility = if (missing) View.VISIBLE else View.GONE
    }
}
