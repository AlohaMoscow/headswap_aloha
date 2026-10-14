package com.yegorkapomidorka.headswap.link.wifi

import android.os.SystemClock
import com.yegorkapomidorka.headswap.link.diag.WLog
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** IPv4-интерфейс локальной сети, куда шлём маяк: хотспот телефона (ветка B) или сеть ГУ, куда вошёл телефон (C′). */
class LanIface(val name: String, val ip: Inet4Address, val prefix: Int, val broadcast: Inet4Address) {
    /** Адрес в подсети интерфейса (по нему же принимаются сокеты ГУ). */
    fun contains(a: InetAddress): Boolean {
        if (a !is Inet4Address) return false
        val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
        return (toInt(a) and mask) == (toInt(ip) and mask)
    }

    /** Узлы подсети без своего адреса, сети и broadcast; только для /24 и уже (иначе слишком много). */
    fun hosts(): List<Inet4Address> {
        if (prefix < 24 || prefix > 30) return emptyList()
        val mask = -1 shl (32 - prefix)
        val net = toInt(ip) and mask
        val n = 1 shl (32 - prefix)
        val me = toInt(ip)
        return (1 until n - 1).map { net + it }.filter { it != me }.map { fromInt(it) }
    }

    override fun toString() = "$name ${ip.hostAddress}/$prefix"

    companion object {
        fun toInt(a: Inet4Address): Int = a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }

        fun fromInt(v: Int): Inet4Address = InetAddress.getByAddress(
            byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()),
        ) as Inet4Address

        /**
         * Подходит ли интерфейс для маяка. Мобильные данные (rmnet, ccmni), loopback, туннели и dummy нет;
         * p2p нет (это ветка A, как в «mix»-режиме Baidu). Остальные IPv4 с broadcast-адресом да.
         */
        fun eligibleName(name: String): Boolean =
            EXCLUDED_PREFIXES.none { name.startsWith(it) }

        /** Хотспот (а не STA): свип unicast-маяками только по ним, в домашнюю сеть через wlan0 не сыплем. */
        fun sweepName(name: String): Boolean = name != "wlan0"

        private val EXCLUDED_PREFIXES = listOf("rmnet", "ccmni", "lo", "dummy", "tun", "ppp", "p2p", "ip6tnl", "sit", "v4-", "clat")

        /** Текущие подходящие интерфейсы. */
        fun scan(): List<LanIface> {
            val out = ArrayList<LanIface>()
            for (ni in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
                try {
                    if (!ni.isUp || ni.isLoopback || !eligibleName(ni.name)) continue
                } catch (_: SocketException) {
                    continue
                }
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address as? Inet4Address ?: continue
                    val b = ia.broadcast as? Inet4Address ?: continue
                    out += LanIface(ni.name, a, ia.networkPrefixLength.toInt(), b)
                }
            }
            return out
        }
    }
}

/** Байты ветки B: маяк телефона и ответ на опрос ГУ. */
object BeaconCodec {
    const val BEACON_PORT = 7999
    const val PROBE_PORT = 8999

    /** Что шлёт Baidu MD 8.6.7 (ConnectManager.sendHotspotUdp): ГУ берёт только адрес отправителя. */
    val BEACON: ByteArray = "CarlifeHost".toByteArray(Charsets.US_ASCII)

    /** Ответ FT/CL2AA на опрос 8999 (расширение под отдельные ГУ, в официальном MD слушателя нет). */
    val READY: ByteArray = """{"carlifehost":"carlife","status":"ready"}""".toByteArray(Charsets.US_ASCII)

    /** Опрос ГУ: датаграмма со строкой carlifehost (регистр важен, как у FT). */
    fun isProbe(data: ByteArray, len: Int): Boolean = String(data, 0, len, Charsets.ISO_8859_1).contains("carlifehost")
}

/**
 * Ветка B: телефон раздаёт хотспот, ГУ в нём клиент. Раз в 2 с маяк «CarlifeHost» на UDP 7999 в broadcast каждого
 * подходящего интерфейса, раз в 12 с unicast каждому адресу подсети хотспота (часть ГУ broadcast не ловит: FT, CL2AA);
 * ответчик на UDP 8999. Работает, пока ГУ не подключила 6 сокетов (stop()). Всё в WL-лог, без содержимого пакетов ГУ.
 */
class HotspotBeacon(
    private val ifaces: () -> List<LanIface> = { LanIface.scan() },
    private val intervalMs: Long = 2_000,
    private val sweepMs: Long = 12_000,
    private val beaconPort: Int = BeaconCodec.BEACON_PORT,
    private val probePort: Int = BeaconCodec.PROBE_PORT,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val running = AtomicBoolean(false)
    private var sender: Thread? = null
    private var responder: Thread? = null
    @Volatile private var probeSocket: DatagramSocket? = null
    @Volatile private var known: Set<String> = emptySet()

    @Volatile var sent = 0L
        private set
    @Volatile var failed = 0L
        private set
    @Volatile var probes = 0L
        private set

    /** Фактический порт ответчика (в тестах 0 = любой). -1, пока не открыт. */
    val probePortBound: Int get() = probeSocket?.localPort ?: -1

    /** Подсети, куда сейчас идёт маяк: по ним Tcp6Listener пускает ГУ. */
    @Volatile var current: List<LanIface> = emptyList()
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        sender = Thread({ sendLoop() }, "wl-beacon").apply { isDaemon = true; start() }
        responder = Thread({ probeLoop() }, "wl-probe-8999").apply { isDaemon = true; start() }
    }

    fun stop(why: String) {
        if (!running.compareAndSet(true, false)) return
        probeSocket?.close()
        sender?.interrupt()
        WLog.ev("udp", "beacon-stop", "why" to why, "sent" to sent, "failed" to failed, "probes" to probes)
    }

    private fun refresh(): List<LanIface> {
        val list = try {
            ifaces()
        } catch (e: Exception) {
            WLog.ev("net", "iface-fail", "err" to e.javaClass.simpleName)
            current
        }
        val keys = list.map { it.toString() }.toSet()
        if (keys != known) {
            for (k in keys - known) WLog.ev("net", "iface-up", "iface" to k)
            for (k in known - keys) WLog.ev("net", "iface-down", "iface" to k)
            if (keys.isEmpty()) WLog.ev("net", "no-lan", "hint" to "хотспот выключен или нет подходящего интерфейса")
            known = keys
        }
        current = list
        return list
    }

    private fun sendLoop() {
        val firstOk = HashSet<String>()
        val firstErr = HashSet<String>()
        val firstSweep = HashSet<String>()
        var lastSweep = Long.MIN_VALUE / 2
        var lastSummary = clock()
        try {
            DatagramSocket().use { s ->
                s.broadcast = true
                while (running.get()) {
                    val now = clock()
                    val list = refresh()
                    val sweep = now - lastSweep >= sweepMs
                    if (sweep) lastSweep = now
                    for (i in list) {
                        val targets = ArrayList<InetAddress>()
                        targets += i.broadcast
                        if (sweep && LanIface.sweepName(i.name)) targets += i.hosts()
                        for (t in targets) {
                            try {
                                s.send(DatagramPacket(BeaconCodec.BEACON, BeaconCodec.BEACON.size, t, beaconPort))
                                sent++
                                if (firstOk.add(i.name)) WLog.ev("udp", "beacon-first", "iface" to i.toString(), "to" to t.hostAddress)
                            } catch (e: Exception) {
                                failed++
                                if (firstErr.add(i.name)) WLog.ev("udp", "beacon-fail", "iface" to i.toString(), "err" to "${e.javaClass.simpleName}: ${e.message}")
                            }
                        }
                        if (sweep && LanIface.sweepName(i.name) && i.hosts().isNotEmpty() && firstSweep.add(i.name)) {
                            WLog.ev("udp", "sweep", "iface" to i.name, "hosts" to i.hosts().size)
                        }
                    }
                    if (now - lastSummary >= SUMMARY_MS) {
                        lastSummary = now
                        WLog.ev("udp", "beacon", "sent" to sent, "failed" to failed, "probes" to probes, "ifaces" to list.joinToString(",") { it.name })
                    }
                    Thread.sleep(intervalMs)
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            WLog.ev("udp", "beacon-dead", "err" to "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun probeLoop() {
        try {
            val s = DatagramSocket(null)
            probeSocket = s
            s.reuseAddress = true
            s.bind(InetSocketAddress(probePort))
            WLog.ev("udp", "probe-listen", "port" to s.localPort)
            val buf = ByteArray(1500)
            s.use {
                while (running.get()) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val hit = BeaconCodec.isProbe(p.data, p.length)
                    probes++
                    var res = "skip"
                    if (hit) {
                        res = try {
                            s.send(DatagramPacket(BeaconCodec.READY, BeaconCodec.READY.size, p.socketAddress))
                            "replied"
                        } catch (e: Exception) {
                            e.javaClass.simpleName
                        }
                    }
                    if (probes <= 5 || probes % 50 == 0L) {
                        WLog.ev("udp", "probe", "from" to p.address.hostAddress, "len" to p.length, "carlifehost" to hit, "res" to res, "n" to probes)
                    }
                }
            }
        } catch (e: Exception) {
            if (running.get()) WLog.ev("udp", "probe-fail", "port" to probePort, "err" to "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    companion object {
        private const val SUMMARY_MS = 30_000L
    }
}
