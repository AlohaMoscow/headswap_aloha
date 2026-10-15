package com.yegorkapomidorka.headswap.link.car

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.yegorkapomidorka.headswap.BuildConfig

/**
 * Машина, выбранная пользователем: Bluetooth-устройство ГУ и привязка CompanionDeviceManager. Живёт в отдельном
 * файле prefs. Триггер (ресивер ACL, [CarAclReceiver]) включён только пока машина выбрана и сборка с бетой беспровода.
 */
class CarProfile(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("car_profile", Context.MODE_PRIVATE)

    /** MAC как пришёл от системы (регистр разный, сравнивать через [CarMac.same]). */
    val btMac: String? get() = sp.getString("btMac", null)

    val btName: String? get() = sp.getString("btName", null)

    /** id привязки CDM (API 33+); -1 - нет (на API 29-32 id не выдаётся, привязка по MAC). */
    val cdmAssociationId: Int get() = sp.getInt("cdmAssociationId", -1)

    val isSet: Boolean get() = btMac != null

    fun save(mac: String, name: String?, associationId: Int) {
        sp.edit().putString("btMac", mac).putString("btName", name).putInt("cdmAssociationId", associationId).apply()
    }

    fun clear() {
        sp.edit().clear().apply()
    }

    companion object {
        /** Включить/выключить ресивер ACL. Включается, только если машина выбрана и сборка с бетой беспровода. */
        fun enableTrigger(ctx: Context, on: Boolean) {
            val enable = on && BuildConfig.WIRELESS_BETA && CarProfile(ctx).isSet
            ctx.packageManager.setComponentEnabledSetting(
                component(ctx),
                if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }

        /** Состояние ресивера для отчёта. DEFAULT = как в манифесте (enabled=false), то есть выключен. */
        fun triggerEnabled(ctx: Context): Boolean =
            ctx.packageManager.getComponentEnabledSetting(component(ctx)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

        private fun component(ctx: Context) = ComponentName(ctx, CarAclReceiver::class.java)
    }
}
