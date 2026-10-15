package com.yegorkapomidorka.headswap.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.yegorkapomidorka.headswap.AppLocale
import com.yegorkapomidorka.headswap.AppTheme
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.AaDensity
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.aa.FpsSetting
import com.yegorkapomidorka.headswap.aa.MicMode
import com.yegorkapomidorka.headswap.aa.VideoMode
import com.yegorkapomidorka.headswap.autosrv.AutoServer
import com.yegorkapomidorka.headswap.update.UpdatePrefs
import com.yegorkapomidorka.headswap.update.Updater

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

/** Разделы настроек: на главном экране по строке на каждый, внутри — свой экран (SettingsSectionScreen). */
enum class SettingsSection(val key: String, val titleRes: Int, val iconRes: Int) {
    APPEARANCE("appearance", R.string.set_sec_appearance, R.drawable.ic_palette),
    VIDEO("video", R.string.set_video, R.drawable.ic_video),
    SOUND("sound", R.string.set_sec_sound, R.drawable.ic_mic),
    HEADUNIT("headunit", R.string.set_sec_headunit, R.drawable.ic_car),
    UPDATES("updates", R.string.upd_title, R.drawable.ic_download);

    companion object {
        fun byKey(key: String?): SettingsSection? = values().firstOrNull { it.key == key }
    }
}

private val LANGS = listOf(
    AppLocale.SYSTEM to R.string.lang_system,
    "en" to R.string.lang_en,
    "ru" to R.string.lang_ru,
)

private val THEMES = listOf(
    AppTheme.SYSTEM to R.string.theme_system,
    AppTheme.LIGHT to R.string.theme_light,
    AppTheme.MEDIUM to R.string.theme_medium,
    AppTheme.DARK to R.string.theme_dark,
)

private val MICS = listOf(
    Triple(MicMode.CAR, R.string.mic_car_title, R.string.mic_car_sub),
    Triple(MicMode.PHONE, R.string.mic_phone_title, R.string.mic_phone_sub),
    Triple(MicMode.AUTO, R.string.mic_auto_title, R.string.mic_auto_sub),
)

private fun Context.micMissing(aa: AaPrefs): Boolean =
    aa.micMode != MicMode.CAR && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED

/** Строка состояния обновлений и её цвет: одна и та же на главном экране и в разделе «Обновления». */
private fun Context.updateStatus(): Pair<String, Int> {
    val rel = Updater.available(this)
    val err = Updater.error
    return when {
        Updater.step == Updater.Step.CHECKING -> getString(R.string.upd_status_checking) to R.color.on2
        Updater.step == Updater.Step.DOWNLOADING -> getString(R.string.upd_status_downloading, rel?.version ?: "", Updater.progress) to R.color.on2
        Updater.step == Updater.Step.INSTALLING -> getString(R.string.upd_status_installing) to R.color.on2
        err != null -> err.get(this) to R.color.warn
        rel != null -> getString(R.string.upd_status_available, rel.version) to R.color.acc
        UpdatePrefs(this).lastCheck > 0 -> getString(R.string.upd_status_latest) to R.color.ok
        else -> getString(R.string.upd_status_never) to R.color.on2
    }
}

/** Главный экран настроек: разделы с текущими значениями, помощь, версия. */
class SettingsScreen(host: MainActivity) : Screen(host) {
    private val aa = AaPrefs(ctx)
    private val summaries = HashMap<SettingsSection, TextView>()
    override val view: View

    init {
        val c = ctx
        val body = c.vbox(12).apply { pad(c, 16, 4, 16, 16) }

        val sections = c.card()
        for ((i, s) in SettingsSection.values().withIndex()) {
            if (i > 0) sections.addView(c.divider())
            sections.addView(sectionRow(s))
        }
        body.addView(sections, lp(MATCH, WRAP))

        body.addView(c.sectionTitle(c.getString(R.string.set_help)))
        val links = c.card().apply {
            addView(link(R.drawable.ic_report, c.getString(R.string.set_report_title), c.getString(R.string.set_report_note), false) { LogShare.shareReport(c) })
            addView(c.divider())
            addView(link(R.drawable.ic_refresh_btn, c.getString(R.string.set_rerun_setup), null, false) { host.showOnboarding(rerun = true) })
            addView(c.divider())
            addView(link(R.drawable.ic_check, c.getString(R.string.set_check_ready), null, false) { host.showOnboarding(checklist = true) })
            addView(c.divider())
            addView(link(R.drawable.ic_terminal, c.getString(R.string.set_for_devs), null, true) { host.showDev() })
        }
        body.addView(links, lp(MATCH, WRAP))

        body.addView(View(c), lp(MATCH, c.dp(12), 1f))
        val ver = try {
            c.packageManager.getPackageInfo(c.packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        body.addView(c.text(c.getString(R.string.set_footer, ver), 13f, 400, c.clr(R.color.on2)).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP))

        refreshSummaries()
        view = scaffold(c, c.getString(R.string.set_title), { host.back() }, body)
    }

    override fun onShow() = refreshSummaries()

    override fun tick() = refreshSummaries()

    override fun onPermissionResult() = refreshSummaries()

    private fun sectionRow(s: SettingsSection): View {
        val c = ctx
        val sum = c.text("", 14f, 400, c.clr(R.color.on2)).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        summaries[s] = sum
        return c.hbox(14).apply {
            pad(c, 14, 12, 12, 12)
            minimumHeight = c.dp(76)
            background = c.rippleBg(null, 0)
            isClickable = true
            setOnClickListener { host.showSettingsSection(s) }
            val badge = FrameLayout(c).apply {
                background = c.shape(c.clr(R.color.pric), 14)
                addView(c.icon(s.iconRes, 22, c.clr(R.color.acc)), FrameLayout.LayoutParams(c.dp(22), c.dp(22), Gravity.CENTER))
            }
            addView(badge, c.lpDp(c.dp(44), 44))
            val col = c.vbox(2).apply {
                addView(c.text(c.getString(s.titleRes), 17f, 500))
                addView(sum)
            }
            addView(col, lp(0, WRAP, 1f))
            addView(c.icon(R.drawable.ic_chevron, 20, c.clr(R.color.on2)))
        }
    }

    /** Подписи под разделами: текущие значения, а при проблеме — её описание цветом предупреждения. */
    private fun refreshSummaries() {
        val c = ctx
        fun set(s: SettingsSection, text: String, color: Int = R.color.on2) {
            val tv = summaries[s] ?: return
            if (tv.text.toString() != text) tv.text = text
            tv.setTextColor(c.clr(color))
        }
        val lang = c.getString(LANGS.firstOrNull { it.first == AppLocale.get(c) }?.second ?: R.string.lang_system)
        val theme = c.getString(THEMES.firstOrNull { it.first == AppTheme.get(c) }?.second ?: R.string.theme_system)
        set(SettingsSection.APPEARANCE, c.getString(R.string.set_sum_appearance, lang, theme))

        val mode = c.getString(if (aa.transcode) R.string.set_transcode_title else R.string.set_direct_title)
        val res = c.getString(aa.videoMode.effective(aa.transcode).titleRes)
        val dpi = aa.aaDensity.takeIf { it != AaDensity.AUTO }?.let { c.getString(R.string.set_sum_density, it) }
        set(SettingsSection.VIDEO, listOfNotNull(mode, res, dpi, c.getString(aa.fpsSetting.titleRes)).joinToString(" · "))

        if (c.micMissing(aa)) {
            set(SettingsSection.SOUND, c.getString(R.string.mic_no_permission), R.color.warn)
        } else {
            val mic = c.getString(R.string.set_sum_mic, c.getString(MICS.first { it.first == aa.micMode }.second))
            set(SettingsSection.SOUND, listOfNotNull(if (aa.usbAudio) c.getString(R.string.set_usb_audio_title) else null, mic).joinToString(" · "))
        }

        if (aa.autoServer && !AutoServer.isConnected) {
            set(SettingsSection.HEADUNIT, c.getString(if (AutoServer.isListed(c)) R.string.set_svc_not_connected else R.string.set_svc_off), R.color.warn)
        } else {
            val auto = c.getString(if (aa.autoServer) R.string.set_sum_on else R.string.set_sum_off)
            set(SettingsSection.HEADUNIT, c.getString(R.string.set_sum_headunit, aa.certSet.uppercase(), auto))
        }

        val (upd, color) = c.updateStatus()
        set(SettingsSection.UPDATES, upd, color)
    }

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
}

/** Переключатель из нескольких кнопок в рамке; выбранная залита pric и помечена «✓». */
private class Segmented(val ctx: Context, private val labels: List<String>, sp: Float, val onPick: (Int) -> Unit) {
    private val buttons = ArrayList<TextView>()
    val view: LinearLayout = ctx.hbox().apply {
        background = ctx.shape(0, 24, ctx.clr(R.color.line), 1.5f)
        clipToOutline = true
    }

    init {
        for ((i, label) in labels.withIndex()) {
            val b = ctx.text(label, sp, 600).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                pad(ctx, 6, 0, 6, 0)
                isClickable = true
                setOnClickListener { onPick(i) }
            }
            buttons.add(b)
            view.addView(b, lp(0, ctx.dp(48), 1f))
        }
    }

    fun setSelected(sel: Int) {
        for ((i, b) in buttons.withIndex()) {
            val on = i == sel
            b.background = ctx.rippleBg(if (on) ctx.shape(ctx.clr(R.color.pric), 0) else null, 0)
            b.setTextColor(ctx.clr(if (on) R.color.onpric else R.color.on))
            b.text = if (on) "✓ ${labels[i]}" else labels[i]
        }
    }
}

/** Один раздел настроек (язык и тема, видео, звук, ГУ, обновления). */
class SettingsSectionScreen(host: MainActivity, val section: SettingsSection) : Screen(host) {
    private val aa = AaPrefs(ctx)
    private val langRows = LinkedHashMap<String, RadioRow>()
    private val themeRows = LinkedHashMap<String, RadioRow>()
    private val micRows = HashMap<MicMode, RadioRow>()
    private val modeRows = LinkedHashMap<VideoMode, RadioRow>()
    private var transcodeSeg: Segmented? = null
    private var transcodeNote: TextView? = null
    private var fpsSeg: Segmented? = null
    private var densityValue: TextView? = null
    private var densitySub: TextView? = null
    private var densityMinus: View? = null
    private var densityPlus: View? = null
    private var densityAuto: TextView? = null
    private var fpsNote: TextView? = null
    private var certSeg: Segmented? = null
    private var micNote: TextView? = null
    private var svcStatus: TextView? = null
    private var svcButton: TextView? = null
    private var updStatus: TextView? = null
    private var updButton: TextView? = null
    private val body: LinearLayout = ctx.vbox(6).apply { pad(ctx, 16, 4, 16, 16) }
    override val view: View

    init {
        when (section) {
            SettingsSection.APPEARANCE -> buildAppearance()
            SettingsSection.VIDEO -> buildVideo()
            SettingsSection.SOUND -> buildSound()
            SettingsSection.HEADUNIT -> buildHeadunit()
            SettingsSection.UPDATES -> body.addView(updateCard(), lp(MATCH, WRAP).margins(ctx, 0, 8, 0, 0))
        }
        view = scaffold(ctx, ctx.getString(section.titleRes), { host.back() }, body)
    }

    override fun onShow() {
        refreshService()
        refreshMic()
        refreshUpdate()
    }

    override fun tick() {
        refreshService()
        refreshUpdate()
    }

    override fun onPermissionResult() = refreshMic()

    private fun note(text: String, color: Int = R.color.on2): TextView =
        ctx.text(text, 13f, 400, ctx.clr(color)).also { body.addView(it, lp(WRAP, WRAP).margins(ctx, 8, 2, 8, 0)) }

    // --- язык и тема ---

    private fun buildAppearance() {
        val c = ctx
        body.addView(c.sectionTitle(c.getString(R.string.lang_title)))
        for ((tag, titleRes) in LANGS) {
            val row = RadioRow(c, c.getString(titleRes), "") { setLang(tag) }
            langRows[tag] = row
            body.addView(row.view, lp(MATCH, WRAP))
        }
        refreshLang()

        body.addView(c.sectionTitle(c.getString(R.string.theme_title)))
        for ((mode, titleRes) in THEMES) {
            val row = RadioRow(c, c.getString(titleRes), "") { setTheme(mode) }
            themeRows[mode] = row
            body.addView(row.view, lp(MATCH, WRAP))
        }
        refreshTheme()
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

    private fun setTheme(mode: String) {
        if (AppTheme.get(ctx) == mode) return
        AppTheme.set(ctx, mode)
        refreshTheme()
        host.recreate()      // тема и цвета читаются при создании activity
    }

    private fun refreshTheme() {
        val cur = AppTheme.get(ctx)
        for ((mode, row) in themeRows) row.setSelected(cur == mode)
    }

    // --- видео ---

    private fun buildVideo() {
        val c = ctx
        body.addView(c.sectionTitle(c.getString(R.string.set_mode)))
        val seg = Segmented(c, listOf(c.getString(R.string.set_transcode_title), c.getString(R.string.set_direct_title)), 15f) { setTranscode(it == 0) }
        transcodeSeg = seg
        body.addView(seg.view, lp(MATCH, WRAP))
        transcodeNote = note("")

        body.addView(c.sectionTitle(c.getString(R.string.set_resolution)))
        for (m in VideoMode.values()) {
            val row = RadioRow(c, c.getString(m.titleRes), c.getString(m.subRes)) { setVideoMode(m) }
            modeRows[m] = row
            body.addView(row.view, lp(MATCH, WRAP))
        }
        note(c.getString(R.string.set_apply_next))

        body.addView(c.sectionTitle(c.getString(R.string.set_aa_density)))
        body.addView(densityCard(), lp(MATCH, WRAP))
        note(c.getString(R.string.set_aa_density_note))

        body.addView(c.sectionTitle(c.getString(R.string.set_fps)))
        val fps = FpsSetting.values()
        val fSeg = Segmented(c, fps.map { c.getString(it.titleRes) }, 15f) { setFps(fps[it]) }
        fpsSeg = fSeg
        body.addView(fSeg.view, lp(MATCH, WRAP))
        fpsNote = note("")
        note(c.getString(R.string.set_fps_note))

        setTranscode(aa.transcode, save = false)
        refreshFps()
    }

    private fun setTranscode(on: Boolean, save: Boolean = true) {
        if (save) aa.transcode = on
        transcodeSeg?.setSelected(if (on) 0 else 1)
        transcodeNote?.text = ctx.getString(if (on) R.string.set_transcode_sub else R.string.set_direct_sub)
        refreshVideoMode()
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
        refreshDensity()      // «авто» зависит от режима
    }

    /** «−  значение  +  Авто»: 11 вариантов (авто и 100..280 через 20) не влезают в Segmented, поэтому степпер. */
    private fun densityCard(): View {
        val c = ctx
        fun stepButton(label: String, desc: String, up: Boolean) = c.text(label, 24f, 500, c.clr(R.color.acc)).apply {
            gravity = Gravity.CENTER
            contentDescription = desc
            background = c.rippleBg(c.shape(c.clr(R.color.pric), 24), 24)
            isClickable = true
            setOnClickListener { setDensity(AaDensity.step(aa.aaDensity, autoDensity(), up)) }
        }
        val minus = stepButton("−", c.getString(R.string.set_aa_density_smaller), false)
        val plus = stepButton("+", c.getString(R.string.set_aa_density_larger), true)
        val value = c.text("", 17f, 600).apply { gravity = Gravity.CENTER }
        val sub = c.text("", 13f, 400, c.clr(R.color.on2)).apply { gravity = Gravity.CENTER }
        val auto = c.text(c.getString(R.string.set_aa_density_reset), 15f, 600).apply {
            gravity = Gravity.CENTER
            pad(c, 14, 0, 14, 0)
            isClickable = true
            setOnClickListener { setDensity(AaDensity.AUTO) }
        }
        densityMinus = minus
        densityPlus = plus
        densityValue = value
        densitySub = sub
        densityAuto = auto
        val row = c.hbox(10).apply {
            background = c.shape(c.clr(R.color.surf), 20)
            pad(c, 12, 10, 12, 10)
            addView(minus, c.lpDp(c.dp(48), 48))
            addView(c.vbox(2).apply {
                addView(value, lp(MATCH, WRAP))
                addView(sub, lp(MATCH, WRAP))
            }, lp(0, WRAP, 1f))
            addView(plus, c.lpDp(c.dp(48), 48))
            addView(auto, c.lpDp(WRAP, 44))
        }
        refreshDensity()
        return row
    }

    /** density режима, который реально применится (с учётом passthrough). */
    private fun autoDensity(): Int = aa.videoMode.effective(aa.transcode).density

    private fun setDensity(v: Int) {
        aa.aaDensity = v
        refreshDensity()
    }

    private fun refreshDensity() {
        val value = densityValue ?: return
        val c = ctx
        val cur = aa.aaDensity
        val auto = autoDensity()
        val isAuto = cur == AaDensity.AUTO
        value.text = if (isAuto) c.getString(R.string.set_aa_density_auto, auto) else c.getString(R.string.set_aa_density_manual, cur)
        densitySub?.text = if (isAuto) c.getString(R.string.set_aa_density_auto_sub) else c.getString(R.string.set_aa_density_manual_sub, auto)
        for ((b, up) in listOf(densityMinus to false, densityPlus to true)) {
            val can = AaDensity.step(cur, auto, up) != cur
            b?.isEnabled = can
            b?.alpha = if (can) 1f else 0.35f
        }
        densityAuto?.apply {
            background = c.rippleBg(if (isAuto) c.shape(c.clr(R.color.pric), 22) else c.shape(0, 22, c.clr(R.color.line), 1.5f), 22)
            setTextColor(c.clr(if (isAuto) R.color.onpric else R.color.on))
            text = if (isAuto) "✓ ${c.getString(R.string.set_aa_density_reset)}" else c.getString(R.string.set_aa_density_reset)
        }
    }

    private fun setFps(f: FpsSetting) {
        aa.fpsSetting = f
        refreshFps()
    }

    private fun refreshFps() {
        fpsSeg?.setSelected(FpsSetting.values().indexOf(aa.fpsSetting))
        fpsNote?.text = ctx.getString(aa.fpsSetting.subRes)
    }

    // --- звук и микрофон ---

    private fun buildSound() {
        val c = ctx
        body.addView(c.sectionTitle(c.getString(R.string.set_sound)))
        body.addView(ToggleRow(c, c.getString(R.string.set_usb_audio_title), c.getString(R.string.set_usb_audio_sub), aa.usbAudio) { aa.usbAudio = it }.view)

        body.addView(c.sectionTitle(c.getString(R.string.set_mic)))
        for ((m, titleRes, subRes) in MICS) {
            val row = RadioRow(c, c.getString(titleRes), c.getString(subRes)) { setMic(m) }
            micRows[m] = row
            body.addView(row.view, lp(MATCH, WRAP))
        }
        micNote = note("", R.color.warn)
        refreshMic()
    }

    private fun setMic(m: MicMode) {
        aa.micMode = m
        if (m != MicMode.CAR && ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            host.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
        }
        refreshMic()
    }

    private fun refreshMic() {
        val note = micNote ?: return
        for ((m, row) in micRows) row.setSelected(aa.micMode == m)
        val missing = ctx.micMissing(aa)
        note.text = if (missing) ctx.getString(R.string.mic_no_permission) else ""
        note.visibility = if (missing) View.VISIBLE else View.GONE
    }

    // --- головное устройство ---

    private fun buildHeadunit() {
        val c = ctx
        body.addView(c.sectionTitle(c.getString(R.string.set_cert)))
        body.addView(certCard())
        body.addView(c.sectionTitle(c.getString(R.string.set_autostart)))
        body.addView(autostartCard())
    }

    private fun certCard(): View {
        val c = ctx
        val card = c.vbox(8).apply {
            background = c.shape(c.clr(R.color.surf), 20)
            pad(c, 12, 12, 12, 12)
        }
        val sets = listOf("jvc", "hur")
        val seg = Segmented(c, sets.map { it.uppercase() }, 16f) {
            aa.certSet = sets[it]
            certSeg?.setSelected(it)
        }
        certSeg = seg
        seg.setSelected(sets.indexOf(aa.certSet))
        card.addView(seg.view, lp(MATCH, WRAP))
        card.addView(c.text(c.getString(R.string.set_cert_hint), 14f, 400, c.clr(R.color.on2)).apply { pad(c, 6, 0, 6, 0) })
        return card
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
        val status = c.text("", 14f, 400, c.clr(R.color.on2))
        val button = c.text(c.getString(R.string.set_autostart_enable), 15f, 600, c.clr(R.color.acc)).apply {
            gravity = Gravity.CENTER
            pad(c, 14, 0, 14, 0)
            background = c.rippleBg(null, 20)
            isClickable = true
            setOnClickListener { Actions.enableServiceDialog(c) }
        }
        svcStatus = status
        svcButton = button
        row.addView(status, lp(0, WRAP, 1f))
        row.addView(button, c.lpDp(WRAP, 44))
        card.addView(row, lp(MATCH, WRAP))
        refreshService()
        return card
    }

    private fun refreshService() {
        val status = svcStatus ?: return
        val c = ctx
        val connected = AutoServer.isConnected
        status.text = c.getString(
            when {
                connected -> R.string.set_svc_connected
                AutoServer.isListed(c) -> R.string.set_svc_not_connected
                else -> R.string.set_svc_off
            },
        )
        status.setTextColor(c.clr(if (connected) R.color.ok else R.color.warn))
        svcButton?.visibility = if (connected) View.GONE else View.VISIBLE
    }

    // --- обновления ---

    /** Переключатель проверки + строка состояния с кнопкой «Проверить» / «Обновить» (как карточка автозапуска). */
    private fun updateCard(): View {
        val c = ctx
        val prefs = UpdatePrefs(c)
        val card = c.vbox().apply { background = c.shape(c.clr(R.color.surf), 20) }
        card.addView(
            ToggleRow(c, c.getString(R.string.upd_check_title), c.getString(R.string.upd_check_sub), prefs.enabled, 72, false, 17f, 14f) {
                prefs.enabled = it
                if (it) Updater.maybeCheck(c)
            }.view,
            lp(MATCH, WRAP),
        )
        card.addView(c.divider())
        val row = c.hbox(12).apply { pad(c, 16, 10, 12, 10); minimumHeight = c.dp(64) }
        val status = c.text("", 14f, 400, c.clr(R.color.on2))
        val button = c.text("", 15f, 600, c.clr(R.color.acc)).apply {
            gravity = Gravity.CENTER
            pad(c, 14, 0, 14, 0)
            background = c.rippleBg(null, 20)
            isClickable = true
            setOnClickListener { if (Updater.available(c) != null) Updater.start(host) else Updater.check(c, manual = true) }
        }
        updStatus = status
        updButton = button
        row.addView(status, lp(0, WRAP, 1f))
        row.addView(button, c.lpDp(WRAP, 44))
        card.addView(row, lp(MATCH, WRAP))
        refreshUpdate()
        return card
    }

    private fun refreshUpdate() {
        val status = updStatus ?: return
        val button = updButton ?: return
        val c = ctx
        val (text, color) = c.updateStatus()
        if (status.text.toString() != text) status.text = text
        status.setTextColor(c.clr(color))
        val label = c.getString(if (Updater.available(c) != null) R.string.upd_btn_update else R.string.upd_btn_check)
        if (button.text.toString() != label) button.text = label
        button.visibility = if (Updater.step != Updater.Step.IDLE) View.GONE else View.VISIBLE
    }
}
