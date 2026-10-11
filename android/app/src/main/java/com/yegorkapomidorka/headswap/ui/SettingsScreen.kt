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
import com.yegorkapomidorka.headswap.AppLocale
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.aa.FpsSetting
import com.yegorkapomidorka.headswap.aa.MicMode
import com.yegorkapomidorka.headswap.aa.VideoMode
import com.yegorkapomidorka.headswap.autosrv.AutoServer

/** Шапка «назад + заголовок» и прокручиваемое тело. */
fun scaffold(ctx: Context, title: String, onBack: () -> Unit, body: View): LinearLayout = ctx.vbox().apply {
    val header = ctx.hbox(4).apply {
        pad(ctx, 4, 8, 8, 0)
        addView(ctx.iconButton(R.drawable.ic_back, ctx.clr(R.color.on), ctx.getString(R.string.set_back)).apply { setOnClickListener { onBack() } })
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
    private val langRows = LinkedHashMap<String, RadioRow>()
    private val micRows = HashMap<MicMode, RadioRow>()
    private val modeRows = LinkedHashMap<VideoMode, RadioRow>()
    private val fpsRows = LinkedHashMap<FpsSetting, RadioRow>()
    private lateinit var modeNote: TextView
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

        body.addView(c.sectionTitle(c.getString(R.string.lang_title)))
        for ((tag, titleRes) in listOf(
            AppLocale.SYSTEM to R.string.lang_system,
            "en" to R.string.lang_en,
            "ru" to R.string.lang_ru,
        )) {
            val row = RadioRow(c, c.getString(titleRes), "") { setLang(tag) }
            langRows[tag] = row
            body.addView(row.view, lp(MATCH, WRAP))
        }
        refreshLang()

        body.addView(c.sectionTitle(c.getString(R.string.set_sound)))
        body.addView(ToggleRow(c, c.getString(R.string.set_usb_audio_title), c.getString(R.string.set_usb_audio_sub), aa.usbAudio) { aa.usbAudio = it }.view)

        body.addView(c.sectionTitle(c.getString(R.string.set_video)))
        transcodeRow = RadioRow(c, c.getString(R.string.set_transcode_title), c.getString(R.string.set_transcode_sub)) { setTranscode(true) }
        directRow = RadioRow(c, c.getString(R.string.set_direct_title), c.getString(R.string.set_direct_sub)) { setTranscode(false) }
        body.addView(transcodeRow.view, lp(MATCH, WRAP))
        body.addView(directRow.view, lp(MATCH, WRAP))
        setTranscode(aa.transcode, save = false)

        body.addView(c.sectionTitle(c.getString(R.string.set_resolution)))
        for (m in VideoMode.values()) {
            val row = RadioRow(c, c.getString(m.titleRes), c.getString(m.subRes)) { setVideoMode(m) }
            modeRows[m] = row
            body.addView(row.view, lp(MATCH, WRAP))
        }
        modeNote = c.text(c.getString(R.string.set_apply_next), 13f, 400, c.clr(R.color.on2))
        body.addView(modeNote, lp(WRAP, WRAP).margins(c, 8, 2, 8, 0))
        refreshVideoMode()

        body.addView(c.sectionTitle(c.getString(R.string.set_fps)))
        for (f in FpsSetting.values()) {
            val row = RadioRow(c, c.getString(f.titleRes), c.getString(f.subRes)) { setFps(f) }
            fpsRows[f] = row
            body.addView(row.view, lp(MATCH, WRAP))
        }
        body.addView(
            c.text(c.getString(R.string.set_fps_note), 13f, 400, c.clr(R.color.on2)),
            lp(WRAP, WRAP).margins(c, 8, 2, 8, 0),
        )
        refreshFps()

        body.addView(c.sectionTitle(c.getString(R.string.set_cert)))
        body.addView(certCard())

        body.addView(c.sectionTitle(c.getString(R.string.set_autostart)))
        body.addView(autostartCard())

        body.addView(c.sectionTitle(c.getString(R.string.set_mic)))
        micNote = c.text("", 13f, 400, c.clr(R.color.warn))
        for ((m, titleRes, subRes) in listOf(
            Triple(MicMode.CAR, R.string.mic_car_title, R.string.mic_car_sub),
            Triple(MicMode.PHONE, R.string.mic_phone_title, R.string.mic_phone_sub),
            Triple(MicMode.AUTO, R.string.mic_auto_title, R.string.mic_auto_sub),
        )) {
            val row = RadioRow(c, c.getString(titleRes), c.getString(subRes)) { setMic(m) }
            micRows[m] = row
            body.addView(row.view, lp(MATCH, WRAP))
        }
        body.addView(micNote, lp(WRAP, WRAP).margins(c, 8, 2, 8, 0))
        refreshMic()

        val links = c.card().apply {
            addView(link(R.drawable.ic_report, c.getString(R.string.set_report_title), c.getString(R.string.set_report_note), false) { LogShare.shareReport(c) })
            addView(c.divider())
            addView(link(R.drawable.ic_refresh_btn, c.getString(R.string.set_rerun_setup), null, false) { host.showOnboarding(rerun = true) })
            addView(c.divider())
            addView(link(R.drawable.ic_check, c.getString(R.string.set_check_ready), null, false) { host.showOnboarding(checklist = true) })
            addView(c.divider())
            addView(link(R.drawable.ic_terminal, c.getString(R.string.set_for_devs), null, true) { host.showDev() })
        }
        body.addView(links, lp(MATCH, WRAP).margins(c, 0, 12, 0, 0))

        body.addView(View(c), lp(MATCH, c.dp(12), 1f))
        val ver = try {
            c.packageManager.getPackageInfo(c.packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        body.addView(c.text(c.getString(R.string.set_footer, ver), 13f, 400, c.clr(R.color.on2)).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP))

        view = scaffold(c, c.getString(R.string.set_title), { host.back() }, body)
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

    private fun setLang(tag: String) {
        if (AppLocale.get(ctx) == tag) return
        AppLocale.set(ctx, tag)
        refreshLang()
        if (AppLocale.needsRecreate) host.recreate()      // на 33+ activity пересоздаёт система
    }

    private fun refreshLang() {
        val cur = AppLocale.get(ctx)
        for ((tag, row) in langRows) row.setSelected(cur == tag)
    }

    private fun setTranscode(on: Boolean, save: Boolean = true) {
        if (save) aa.transcode = on
        transcodeRow.setSelected(on)
        directRow.setSelected(!on)
        if (::modeNote.isInitialized) refreshVideoMode()
    }

    private fun setVideoMode(m: VideoMode) {
        if (m.needsTranscode && !aa.transcode) return      // кроп возможен только при перекодировании
        aa.videoMode = m
        refreshVideoMode()
    }

    /** Без перекодирования «широкий» недоступен: радио затемнено, фактический режим 1280×720 (в prefs выбор остаётся). */
    private fun refreshVideoMode() {
        val shown = aa.videoMode.effective(aa.transcode)
        for ((m, row) in modeRows) {
            val blocked = m.needsTranscode && !aa.transcode
            row.setSelected(m == shown)
            row.setSub(if (blocked) ctx.getString(R.string.set_needs_transcode) else ctx.getString(m.subRes))
            row.view.alpha = if (blocked) 0.45f else 1f
        }
    }

    private fun setFps(f: FpsSetting) {
        aa.fpsSetting = f
        refreshFps()
    }

    private fun refreshFps() {
        for ((f, row) in fpsRows) row.setSelected(aa.fpsSetting == f)
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
        card.addView(c.text(c.getString(R.string.set_cert_hint), 14f, 400, c.clr(R.color.on2)).apply { pad(c, 6, 0, 6, 0) })
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
            ToggleRow(c, c.getString(R.string.set_autostart), c.getString(R.string.set_autostart_sub), aa.autoServer, 72, false, 17f, 14f) { aa.autoServer = it }.view,
            lp(MATCH, WRAP),
        )
        card.addView(c.divider())
        val row = c.hbox(12).apply { pad(c, 16, 10, 12, 10); minimumHeight = c.dp(64) }
        svcStatus = c.text("", 14f, 400, c.clr(R.color.on2))
        svcButton = c.text(c.getString(R.string.set_autostart_enable), 15f, 600, c.clr(R.color.acc)).apply {
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
        svcStatus.text = c.getString(
            when {
                connected -> R.string.set_svc_connected
                AutoServer.isListed(c) -> R.string.set_svc_not_connected
                else -> R.string.set_svc_off
            },
        )
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
        micNote.text = if (missing) ctx.getString(R.string.mic_no_permission) else ""
        micNote.visibility = if (missing) View.VISIBLE else View.GONE
    }
}
