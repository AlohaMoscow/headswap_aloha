package com.yegorkapomidorka.headswap.link.car

import android.content.Context

/**
 * Wi-Fi точка доступа машины (ГУ раздаёт её сам): по ней телефон входит в сеть машины через WifiNetworkSpecifier, не теряя
 * интернет основной сети. Отдельный файл prefs `car_wifi`, исключён из бэкапа (res/xml/backup_rules.xml, data_extraction_rules.xml).
 * SSID и пароль не пишем никуда, кроме этого файла и экрана настроек: ни в WLog, ни в AppLog, ни в отчёт.
 */
class CarWifi(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("car_wifi", Context.MODE_PRIVATE)

    val ssid: String? get() = sp.getString("ssid", null)

    /** Пароль WPA2/WPA3; пустая строка (или null) - открытая сеть. */
    val pass: String get() = sp.getString("pass", null).orEmpty()

    val isSet: Boolean get() = !ssid.isNullOrEmpty()

    fun save(ssid: String, pass: String) {
        sp.edit().putString("ssid", ssid).putString("pass", pass).apply()
    }

    fun clear() {
        sp.edit().clear().apply()
    }
}
