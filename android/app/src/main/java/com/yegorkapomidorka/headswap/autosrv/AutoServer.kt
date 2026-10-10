package com.yegorkapomidorka.headswap.autosrv

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.yegorkapomidorka.headswap.AppLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Точка входа автозапуска сервера ГУ Android Auto: одна автоматизация за раз (повторный вызов
 * присоединяется к идущей), работает на своей нити, ждать результат можно с таймаутом.
 * Все строки лога с префиксом AUTOSRV.
 */
object AutoServer {
    class Run(val trigger: Trigger) {
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        @Volatile var outcome: AutoOutcome? = null
    }

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "autosrv").also { it.isDaemon = true } }
    private var current: Run? = null

    /** Подключённая служба спецвозможностей (null = выключена пользователем или убита системой). */
    @Volatile var service: AutoServerService? = null

    @Volatile var last: AutoOutcome? = null
        private set
    @Volatile var lastTrigger: Trigger? = null
        private set

    val isConnected: Boolean get() = service != null
    val lastLocked: Boolean get() = last?.result == AutoResult.LOCKED

    fun log(line: String) = AppLog.i("AUTOSRV $line", true)

    /** Ждёт подключения службы не дольше waitMs; true = подключена. */
    fun awaitService(waitMs: Long): Boolean {
        val end = System.nanoTime() + waitMs * 1_000_000
        while (service == null && System.nanoTime() < end) Thread.sleep(50)
        return service != null
    }

    /** Служба включена в системных настройках (может быть ещё не подключена). */
    fun isListed(ctx: Context): Boolean {
        val cls = AutoServerService::class.java
        val enabled = try {
            Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        } catch (_: Exception) {
            false
        }
        val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled && AutoServerPolicy.isListed(list, ctx.packageName, cls.name)
    }

    fun component(ctx: Context) = ComponentName(ctx, AutoServerService::class.java)

    @Synchronized
    fun start(trigger: Trigger): Run {
        current?.let {
            if (it.done.count > 0) {
                log("уже идёт (${it.trigger.label}), присоединяюсь (${trigger.label})")
                return it
            }
        }
        val run = Run(trigger)
        current = run
        val svc = service
        if (svc == null) {
            finish(run, AutoOutcome(AutoResult.SERVICE_OFF, "служба спецвозможностей не подключена", 0))
            return run
        }
        log("запуск: ${trigger.label}")
        exec.execute {
            val outcome = try {
                ServerAutomation(AccessibilityServerUi(svc), { log(it) }, onStarted = { run.started.countDown() }).restart()
            } catch (e: Throwable) {
                AppLog.e("AUTOSRV сбой автоматизации", e)
                AutoOutcome(AutoResult.ERROR, "${e.javaClass.simpleName}: ${e.message}", 0)
            }
            finish(run, outcome)
        }
        return run
    }

    private fun finish(run: Run, outcome: AutoOutcome) {
        run.outcome = outcome
        last = outcome
        lastTrigger = run.trigger
        run.started.countDown()
        run.done.countDown()
    }

    /**
     * Ждёт до waitMs. Возвращает итог, если автоматизация закончилась; если сервер уже запущен, а проверка и
     * возврат в приложение ещё идут - OK «сервер запущен»; по таймауту null.
     */
    fun await(run: Run, waitMs: Long): AutoOutcome? {
        val end = System.nanoTime() + waitMs * 1_000_000
        while (true) {
            run.outcome?.let { return it }
            if (run.started.count == 0L) return AutoOutcome(AutoResult.OK, "сервер запущен, проверка продолжается", 0)
            val left = (end - System.nanoTime()) / 1_000_000
            if (left <= 0) return null
            run.done.await(minOf(left, 50), TimeUnit.MILLISECONDS)
        }
    }

    fun summary(ctx: Context): String {
        val svc = if (isConnected) "служба подключена" else if (isListed(ctx)) "служба включена, но не подключена" else "служба спецвозможностей выключена"
        val l = last
        return "AUTOSRV: $svc" + if (l != null) "; последний: ${lastTrigger?.label} -> $l" else ""
    }
}
