package com.yegorkapomidorka.headswap.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.autosrv.AutoServer

/** Всё, что надо включить перед поездкой: порядок, тексты, автоопределение «уже сделано». Общее для онбординга и чеклиста. */
enum class StepId(val key: String) {
    AA_DEV("aa_dev"),
    AUTOSTART("autostart"),
    NOTIF("notif"),
    BACKGROUND("background"),
    MIC("mic"),
    BLUETOOTH("bluetooth"),
    LOCATION("location"),
    FIRST("first"),
}

class StepInfo(
    val id: StepId,
    val title: String,
    val body: String,
    val hint: String?,
    val primary: String,
    val extra: String?,
    val skip: String,
    /** Короткое название для чеклиста. */
    val short: String,
)

object Readiness {
    val STEPS: List<StepInfo> = listOf(
        StepInfo(
            StepId.AA_DEV, "Режим разработчика Android Auto",
            "Откройте настройки Android Auto, пролистайте вниз и 10 раз нажмите «Сведения о версиях и разрешениях», подтвердите. Без этого не будет пункта «Сервер головного устройства».",
            "В HyperOS этих настроек нет в обычных Настройках — открывайте их кнопкой ниже.",
            "Открыть Android Auto", null, "Уже сделано", "Режим разработчика AA",
        ),
        StepInfo(
            StepId.AUTOSTART, "Автозапуск сервера ГУ",
            "Включите службу спецвозможностей «Headswap: автозапуск сервера ГУ»: она сама запускает сервер ГУ при подключении кабеля. Служба видит только окна Android Auto.",
            "Если пункт серый: Настройки → Приложения → Headswap → ⋮ → «Разрешить ограниченные настройки». Без службы сервер придётся запускать вручную перед каждой поездкой: Android Auto → ⋮ → «Вкл. сервер головного устр-ва».",
            "Открыть спецвозможности", "Разрешить ограниченные настройки", "Пропустить", "Автозапуск сервера ГУ",
        ),
        StepInfo(
            StepId.NOTIF, "Уведомления",
            "Покажу статус в шторке, и система не выгрузит мост, пока экран погашен.",
            null, "Разрешить", null, "Позже", "Уведомления",
        ),
        StepInfo(
            StepId.BACKGROUND, "Работа в фоне",
            "HyperOS убивает фоновые приложения. Снимите ограничение батареи, иначе мост может отключиться при погашенном экране.",
            "Xiaomi: Настройки → Приложения → Headswap → «Автозапуск» включить, «Контроль активности» → «Нет ограничений».",
            "Снять ограничение батареи", "Автозапуск и контроль активности", "Уже сделано", "Работа в фоне",
        ),
        StepInfo(
            StepId.MIC, "Микрофон",
            "Нужен для «Окей, Google» и голосового ввода, если микрофон машины недоступен: голос возьму с микрофона телефона.",
            null, "Разрешить", null, "Позже", "Микрофон",
        ),
        StepInfo(
            StepId.BLUETOOTH, "Bluetooth для звонков",
            "Звонки в Android Auto всегда идут через Bluetooth: один раз сопрягите телефон с магнитолой как телефон или гарнитуру. Музыка и навигация идут по кабелю.",
            null, "Открыть Bluetooth", null, "Уже сделано", "Bluetooth для звонков",
        ),
        StepInfo(
            StepId.LOCATION, "Геопозиция для Карт",
            "Google Картам и 2ГИС в Android Auto нужен доступ к местоположению, иначе на экране машины будет «требуется доступ к геоданным».",
            "Включите геолокацию и разрешите её приложениям карт.",
            "Открыть геолокацию", null, "Уже сделано", "Геопозиция для Карт",
        ),
        StepInfo(
            StepId.FIRST, "Первое подключение",
            "Подключите кабель к магнитоле, телефон разблокирован. На запрос «Открыть Headswap для USB-аксессуара» отметьте «По умолчанию» и нажмите «ОК».",
            "Если на магнитоле экран «启动CarLife» — нажмите эту кнопку. В машине телефон должен быть разблокирован в момент подключения, иначе на ГУ будет «Разблокируйте телефон».",
            "Понятно", null, "Назад", "Первое подключение",
        ),
    )

    fun info(id: StepId): StepInfo = STEPS.first { it.id == id }

    fun isXiaomi(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")
    }

    /** true/false - определено автоматически; null - определить нельзя (только ручная отметка «Уже сделано»). */
    fun auto(ctx: Context, id: StepId): Boolean? = when (id) {
        StepId.AUTOSTART -> AutoServer.isConnected
        StepId.NOTIF -> ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        StepId.MIC -> ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        StepId.BACKGROUND -> batteryOk(ctx)
        StepId.LOCATION -> try {
            ctx.getSystemService(LocationManager::class.java).isLocationEnabled
        } catch (_: Exception) {
            null
        }
        else -> null
    }

    fun batteryOk(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    /** Выполнено: автоопределение, а где его нет - ручная отметка. Фон на Xiaomi требует ещё и ручной отметки. */
    fun isDone(ctx: Context, id: StepId): Boolean {
        val ui = UiPrefs(ctx)
        if (id == StepId.BACKGROUND) return batteryOk(ctx) && (!isXiaomi() || ui.isManualDone(id.key))
        return auto(ctx, id) ?: ui.isManualDone(id.key)
    }

    // --- действия кнопок ---

    fun appDetails(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun requestIgnoreBattery(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            AppLog.e("не открыть запрос исключения из оптимизации батареи", e)
            try {
                ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                appDetails(ctx)
            }
        }
    }

    /** Автозапуск Xiaomi (Безопасность); не вышло - карточка приложения. */
    fun openXiaomiAutostart(ctx: Context) {
        try {
            ctx.startActivity(
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            AppLog.i("автозапуск Xiaomi не открылся (${e.javaClass.simpleName}), открываю карточку приложения")
            try {
                appDetails(ctx)
            } catch (e2: Exception) {
                AppLog.e("не открыть карточку приложения", e2)
            }
        }
    }

    fun openLocationSettings(ctx: Context) = openSettings(ctx, Settings.ACTION_LOCATION_SOURCE_SETTINGS)

    fun openBluetoothSettings(ctx: Context) = openSettings(ctx, Settings.ACTION_BLUETOOTH_SETTINGS)

    private fun openSettings(ctx: Context, action: String) {
        try {
            ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AppLog.e("не открыть $action", e)
        }
    }
}
