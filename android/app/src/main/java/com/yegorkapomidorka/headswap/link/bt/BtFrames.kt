package com.yegorkapomidorka.headswap.link.bt

import com.yegorkapomidorka.headswap.PbReader
import com.yegorkapomidorka.headswap.Wire

/**
 * Кадры Bluetooth-канала беспроводного CarLife (Baidu MD 8.6.7, CommonParams, BluetoothConnect.readData):
 * [длина тела u16 BE][резерв u16 = 0][тип u32 BE] + protobuf. Длина без заголовка.
 */
object BtMsg {
    const val MD_WIRELESS_INFO_REQUEST = 0x00100001
    const val HU_WIRELESS_INFO = 0x00108002
    const val MD_TARGET_INFO_REQUEST = 0x00100004
    const val HU_TARGET_INFO = 0x00108005
    const val HU_IP_REQUEST = 0x00108006
    const val MD_WIFI_IP = 0x00100007
    const val MD_STATUS = 0x00100008
    const val HU_STATUS = 0x00108009

    const val HDR = 8
    const val MAX_BODY = 4096

    fun name(type: Int): String = when (type) {
        MD_WIRELESS_INFO_REQUEST -> "WIRELESS_INFO_REQUEST"
        HU_WIRELESS_INFO -> "WIRELESS_INFO"
        MD_TARGET_INFO_REQUEST -> "TARGET_INFO_REQUEST"
        HU_TARGET_INFO -> "TARGET_INFO"
        HU_IP_REQUEST -> "IP_REQUEST"
        MD_WIFI_IP -> "WIFI_IP"
        MD_STATUS -> "MD_STATUS"
        HU_STATUS -> "HU_STATUS"
        else -> "0x%08X".format(type)
    }

    /** wirlessType из INFO_RESPONSE: 2..4 запускают беспроводную ветку у Baidu, 1 (WIFI) нет. */
    fun wirelessTypeName(t: Int?): String = when (t) {
        null -> "-"
        1 -> "1(wifi)"
        2 -> "2(p2p)"
        3 -> "3"
        4 -> "4"
        else -> t.toString()
    }

    fun frame(type: Int, body: ByteArray? = null): ByteArray = Wire.cmdInner(type, body)
}

class BtFrame(val type: Int, val body: ByteArray)

/** Сборка кадров из кусков RFCOMM-потока. Мусор в заголовке (резерв != 0, длина > MAX_BODY): сдвиг на байт. */
class BtFrameParser {
    private var buf = ByteArray(0)

    /** Сколько байт выброшено при ресинхронизации (для лога). */
    var skipped = 0L
        private set

    fun feed(chunk: ByteArray, n: Int = chunk.size): List<BtFrame> {
        buf += chunk.copyOf(n)
        val out = ArrayList<BtFrame>()
        var p = 0
        while (buf.size - p >= BtMsg.HDR) {
            val len = Wire.u16(buf, p)
            if (len > BtMsg.MAX_BODY || Wire.u16(buf, p + 2) != 0) {
                p++
                skipped++
                continue
            }
            if (buf.size - p < BtMsg.HDR + len) break
            out += BtFrame(Wire.u32(buf, p + 4), buf.copyOfRange(p + BtMsg.HDR, p + BtMsg.HDR + len))
            p += BtMsg.HDR + len
        }
        buf = buf.copyOfRange(p, buf.size)
        return out
    }
}

/**
 * Сводки ответов ГУ для WL-лога без секретов: имя сети и пароль из p2pNetInfo не выводятся, только число частей
 * и частота; scanRecord (зашифрованные реквизиты бокса) - только длина.
 */
object BtSummary {
    fun wirelessInfo(body: ByteArray): List<Pair<String, Any?>> {
        val f = PbReader.parse(body) ?: return listOf("parse" to "fail", "len" to body.size)
        return listOf("wirelessType" to BtMsg.wirelessTypeName(PbReader.int(f, 1)), "wifiFreq" to PbReader.int(f, 2))
    }

    fun targetInfo(body: ByteArray): List<Pair<String, Any?>> {
        val f = PbReader.parse(body) ?: return listOf("parse" to "fail", "len" to body.size)
        val name = PbReader.str(f, 1)
        val target = PbReader.str(f, 2)
        val scan = PbReader.all(f, 3).firstOrNull()?.data
        val net = PbReader.str(f, 4)
        return listOf(
            "p2pNameLen" to (name?.length ?: -1),
            "p2pNameDirect" to (name?.startsWith("DIRECT-") ?: false),
            "targetInfoLen" to (target?.length ?: -1),
            "scanRecordLen" to (scan?.size ?: -1),
        ) + netInfo(net)
    }

    /** p2pNetInfo = «ssid,пароль[,частота МГц]»: наличие, число частей, частота. */
    fun netInfo(net: String?): List<Pair<String, Any?>> {
        if (net == null) return listOf("p2pNetInfo" to "нет")
        val parts = net.split(',')
        val freq = parts.getOrNull(2)?.trim()?.toIntOrNull()
        return listOf("p2pNetInfo" to "есть", "netParts" to parts.size, "netFreq" to freq)
    }

    fun status(body: ByteArray): List<Pair<String, Any?>> {
        val f = PbReader.parse(body) ?: return listOf("parse" to "fail", "len" to body.size)
        return listOf("status" to PbReader.int(f, 1))
    }
}
