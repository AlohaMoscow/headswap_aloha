package com.yegorkapomidorka.headswap.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.R

/**
 * Онбординг (Onboarding.dc.html) и чеклист готовности. Шаги из Readiness.STEPS; при первом запуске уже выполненные
 * (определяются автоматически или отмечены вручную) пропускаются, при повторном прохождении показываются все.
 * В конце - чеклист со статусом каждого пункта; тот же чеклист открывается из Настроек.
 */
class OnboardingScreen(host: MainActivity, private val showAll: Boolean, startAtChecklist: Boolean, startAt: StepId? = null) : Screen(host) {
    private val root = ctx.vbox()
    override val view: View get() = root
    private val ui = UiPrefs(ctx)
    private val queue: List<StepInfo>
    private var pos = 0
    private var checklist = false

    /** Шаг открыт из чеклиста: после него возвращаемся в чеклист. */
    private var single: StepInfo? = null
    private var shownDone = false

    init {
        queue = if (showAll) Readiness.STEPS else Readiness.STEPS.filter { !Readiness.isDone(ctx, it.id) }
        checklist = startAtChecklist || queue.isEmpty()
        if (startAt != null) pos = queue.indexOfFirst { it.id == startAt }.coerceAtLeast(0)
        render()
    }

    override fun onShow() {
        // вернулись из системных настроек: статус мог измениться
        if (checklist) render() else if (current() != null && Readiness.isDone(ctx, current()!!.id) != shownDone) render()
    }

    override fun onPermissionResult() {
        val s = current() ?: return
        if (!checklist && Readiness.isDone(ctx, s.id) && (s.id == StepId.NOTIF || s.id == StepId.MIC)) next() else render()
    }

    override fun onBack(): Boolean {
        when {
            checklist -> { ui.onboardingDone = true; return false }
            single != null -> { single = null; checklist = true; render() }
            pos > 0 -> { pos--; render() }
            else -> { ui.onboardingDone = true; return false }      // вышли из онбординга сами: больше не навязываем
        }
        return true
    }

    private fun current(): StepInfo? = if (checklist) null else (single ?: queue.getOrNull(pos))

    private fun next() {
        if (single != null) {
            single = null
            checklist = true
        } else if (pos + 1 < queue.size) {
            pos++
        } else {
            checklist = true
        }
        render()
    }

    private fun render() {
        root.removeAllViews()
        if (checklist) renderChecklist() else renderStep(current() ?: return)
    }

    private fun renderStep(s: StepInfo) {
        val c = ctx
        val done = Readiness.isDone(c, s.id)
        shownDone = done
        val all = Readiness.STEPS
        val idx = all.indexOf(s)
        val total = if (single != null || showAll) all.size else queue.size
        val n = if (single != null || showAll) idx + 1 else pos + 1

        root.pad(c, 20, 20, 20, 24)
        val top = c.hbox().apply {
            addView(c.text("Шаг $n из $total", 14f, 600, c.clr(R.color.on2)), lp(0, WRAP, 1f))
            val dots = c.hbox(6)
            for (i in 1..total) {
                dots.addView(View(c).apply { background = c.shape(c.clr(if (i <= n) R.color.pri else R.color.line), 3) }, lp(c.dp(if (total > 6) 18 else 28), c.dp(6)))
            }
            addView(dots)
        }
        root.addView(top, c.lpDp(MATCH, 48))

        val body = c.vbox().apply {
            addView(
                FrameLayout(c).apply {
                    background = c.shape(c.clr(R.color.surf2), 28)
                    minimumHeight = c.dp(if (s.hint != null) 0 else 190)     // с подсказкой макет компактнее: текст целиком виден без прокрутки
                    pad(c, 20, 20, 20, 20)
                    addView(Mocks.build(c, s.id), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_VERTICAL))
                },
                lp(MATCH, WRAP).margins(c, 0, 4, 0, 0),
            )
            val text = c.vbox(10).apply { pad(c, 0, 18, 0, 36) }     // нижний отступ больше затемнения края: конец текста не тонет в fade
            if (s.id == StepId.BACKGROUND && Readiness.isXiaomi()) {
                text.addView(chipLabel("Только Xiaomi / HyperOS"), lp(WRAP, WRAP))
            }
            if (done) text.addView(doneChip(), lp(WRAP, WRAP))
            text.addView(c.text(s.title, 28f, 600).apply { letterSpacing = -0.01f; lineHeight = c.dp(34) })
            text.addView(c.text(s.body, 17f, 400, c.clr(R.color.on2)).apply { lineHeight = c.dp(24) })
            s.hint?.let {
                text.addView(
                    c.text(it, 14f, 400, c.clr(R.color.on2)).apply {
                        lineHeight = c.dp(20)
                        background = c.shape(c.clr(R.color.surf), 16)
                        pad(c, 14, 12, 14, 12)
                    },
                    lp(MATCH, WRAP),
                )
            }
            addView(text, lp(MATCH, WRAP))
        }
        root.addView(fadingScroll(body), lp(MATCH, 0, 1f))

        val buttons = c.vbox(4)
        val primaryLabel = if (done && s.id != StepId.FIRST) "Дальше" else s.primary
        buttons.addView(c.primaryButton(primaryLabel).apply { setOnClickListener { onPrimary(s, done) } })
        val showExtra = s.extra != null && !done && (s.id != StepId.BACKGROUND || Readiness.isXiaomi())
        if (showExtra) {
            buttons.addView(c.outlineButton(s.extra!!, 0, 48, 15f).apply { setOnClickListener { onExtra(s) } })
        }
        val skipLabel = if (done && s.id != StepId.FIRST) "Назад" else s.skip
        buttons.addView(c.linkButton(skipLabel, 48).apply { setOnClickListener { onSkip(s, done) } })
        root.addView(buttons, lp(MATCH, WRAP))
    }

    /** Прокрутка с затемнением края: видно, что текст продолжается. */
    private fun fadingScroll(content: View) = ScrollView(ctx).apply {
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(ctx.dp(24))
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
        addView(content)
    }

    private fun chipLabel(s: String) = ctx.text(s, 13f, 600, ctx.clr(R.color.onpric)).apply {
        background = ctx.shape(ctx.clr(R.color.pric), 8)
        pad(ctx, 10, 4, 10, 4)
    }

    private fun doneChip(): View {
        val c = ctx
        return c.hbox(6).apply {
            background = c.shape(c.clr(R.color.okc), 10)
            pad(c, 10, 4, 12, 4)
            addView(c.icon(R.drawable.ic_check, 14, c.clr(R.color.ok)))
            addView(c.text("Уже выполнено", 13f, 600, c.clr(R.color.ok)))
        }
    }

    private fun onPrimary(s: StepInfo, done: Boolean) {
        val c = ctx
        if (done && s.id != StepId.FIRST) { next(); return }
        when (s.id) {
            StepId.AA_DEV -> Actions.openAaSettings(c)
            StepId.AUTOSTART -> Actions.openAccessibilitySettings(c)
            StepId.NOTIF -> requestNotifications()
            StepId.BACKGROUND -> if (!Readiness.batteryOk(c)) Readiness.requestIgnoreBattery(c) else Readiness.openXiaomiAutostart(c)
            StepId.MIC -> requestMic()
            StepId.BLUETOOTH -> Readiness.openBluetoothSettings(c)
            StepId.LOCATION -> Readiness.openLocationSettings(c)
            StepId.FIRST -> { ui.setManualDone(s.id.key, true); next() }
        }
    }

    private fun onExtra(s: StepInfo) {
        try {
            when (s.id) {
                StepId.AUTOSTART -> Readiness.appDetails(ctx)
                StepId.BACKGROUND -> Readiness.openXiaomiAutostart(ctx)
                else -> {}
            }
        } catch (e: Exception) {
            AppLog.e("шаг ${s.id}: не открыть системный экран", e)
        }
    }

    private fun onSkip(s: StepInfo, done: Boolean) {
        if (done && s.id != StepId.FIRST) { stepBack(); return }
        when (s.id) {
            StepId.FIRST -> stepBack()
            StepId.AA_DEV, StepId.BACKGROUND, StepId.BLUETOOTH, StepId.LOCATION -> { ui.setManualDone(s.id.key, true); next() }
            else -> next()
        }
    }

    private fun stepBack() {
        if (!onBack()) host.back()
    }

    private fun requestNotifications() {
        val c = ctx
        val p = Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 33 && firstAsk(p)) {
            markAsked(p)
            host.requestPermissions(arrayOf(p), 1)
            return
        }
        // системный диалог уже был (или SDK < 33): включаем в настройках уведомлений приложения
        c.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun requestMic() {
        val c = ctx
        if (!firstAsk(Manifest.permission.RECORD_AUDIO) && !host.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            Readiness.appDetails(c)     // отказано насовсем: разрешить можно только в карточке приложения
            return
        }
        markAsked(Manifest.permission.RECORD_AUDIO)
        host.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
    }

    private fun firstAsk(p: String) = !ui.isManualDone("asked_$p")

    private fun markAsked(p: String) = ui.setManualDone("asked_$p", true)

    // --- чеклист ---

    private fun renderChecklist() {
        val c = ctx
        root.pad(c, 20, 20, 20, 24)
        val top = c.hbox().apply {
            addView(c.text("Проверка готовности", 14f, 600, c.clr(R.color.on2)), lp(0, WRAP, 1f))
        }
        root.addView(top, c.lpDp(MATCH, 48))

        val list = c.vbox(12).apply { pad(c, 0, 8, 0, 8) }
        val doneCount = Readiness.STEPS.count { Readiness.isDone(c, it.id) }
        list.addView(c.text(if (doneCount == Readiness.STEPS.size) "Всё готово к поездке" else "Готово $doneCount из ${Readiness.STEPS.size}", 28f, 600).apply {
            letterSpacing = -0.01f
            lineHeight = c.dp(34)
        })
        list.addView(c.text("Нажмите на пункт, чтобы открыть его шаг.", 17f, 400, c.clr(R.color.on2)).apply { lineHeight = c.dp(24) })
        val card = c.card(24).apply { pad(c, 0, 6, 0, 6) }
        for (s in Readiness.STEPS) card.addView(checkRow(s), c.lpDp(MATCH, 60))
        list.addView(card, lp(MATCH, WRAP).margins(c, 0, 8, 0, 0))
        root.addView(fadingScroll(list), lp(MATCH, 0, 1f))

        root.addView(c.primaryButton("Готово").apply {
            setOnClickListener {
                ui.onboardingDone = true
                host.back()
            }
        })
    }

    private fun checkRow(s: StepInfo): View {
        val c = ctx
        val done = Readiness.isDone(c, s.id)
        val auto = Readiness.auto(c, s.id)
        val statusText: String
        val statusColor: Int
        when {
            done -> { statusText = if (auto == null) "отмечено" else "готово"; statusColor = R.color.ok }
            auto == null -> { statusText = "не отмечено"; statusColor = R.color.on2 }
            else -> { statusText = "не включено"; statusColor = R.color.warn }
        }
        return c.hbox(14).apply {
            pad(c, 20, 0, 16, 0)
            background = c.rippleBg(null, 0)
            isClickable = true
            setOnClickListener {
                single = s
                checklist = false
                render()
            }
            val mark = FrameLayout(c)
            when {
                done -> {
                    mark.background = c.oval(c.clr(R.color.ok))
                    mark.addView(c.icon(R.drawable.ic_check, 16, c.clr(R.color.onok)), FrameLayout.LayoutParams(c.dp(16), c.dp(16), Gravity.CENTER))
                }
                auto == null -> mark.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0)
                    setStroke(c.dp(2), c.clr(R.color.line))
                }
                else -> {
                    mark.background = c.oval(c.clr(R.color.warn))
                    mark.addView(c.text("!", 16f, 700, c.clr(R.color.warnc)).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(MATCH, MATCH))
                }
            }
            addView(mark, lp(c.dp(28), c.dp(28)))
            addView(c.vbox(1).apply {
                addView(c.text(s.short, 16f, 500))
                addView(c.text(statusText, 13f, 500, c.clr(statusColor)))
            }, lp(0, WRAP, 1f))
            addView(c.icon(R.drawable.ic_chevron, 20, c.clr(R.color.on2)))
        }
    }
}
