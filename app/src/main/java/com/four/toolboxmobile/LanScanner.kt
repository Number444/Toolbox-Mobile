package com.four.toolboxmobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 局域网扫描器：并发遍历本机 IPv4 所在 /24 网段的 8090 端口，找到 Toolbox 服务端。
 *
 * - 单连接超时 300ms，并发上限 64，整轮约 1 秒内完成
 * - 进度与已发现设备通过 [onProgress] 实时回调（回调发生在 IO 线程，
 *   接收方需保证线程安全——StateFlow 直接赋值是安全的）
 * - **VPN 陷阱（2026-09-01 真机实测）**：Clash TUN 模式接管所有 TCP 连接，
 *   裸 connect() 对网段内任意地址都被隧道应答"已连接"→ 254 台全部误判为候选。
 *   对策：[preferWifiTransport] 把扫描套接字绑定到 Wi-Fi 网络（Network.socketFactory），
 *   流量绕过 VPN 隧道直连局域网；同时网段前缀优先取 Wi-Fi 网卡地址
 *   （TUN 接口的伪地址会污染 NetworkInterface 遍历结果）
 */
object LanScanner {

    const val DEFAULT_PORT = 8090
    private const val CONNECT_TIMEOUT_MS = 300
    private const val CONCURRENCY = 64
    private const val HOSTS_PER_SUBNET = 254

    /** 扫描绑定网络（通常为 Wi-Fi）；null = 系统默认路由（未调用 preferWifiTransport 或无 Wi-Fi） */
    @Volatile private var scanNetwork: Network? = null

    /** Wi-Fi 网卡的 /24 前缀（优先于接口遍历，防 VPN 伪地址污染） */
    @Volatile private var scanPrefix: String? = null

    /** 扫描前调用一次：绑定 Wi-Fi 网络并记录其网段前缀。无 Wi-Fi（纯蜂窝）时保持默认行为 */
    fun preferWifiTransport(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val wifi = cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return
        scanNetwork = wifi
        val props = cm.getLinkProperties(wifi) ?: return
        for (la in props.linkAddresses) {
            val addr = la.address
            if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                scanPrefix = addr.hostAddress?.substringBeforeLast('.')
                return
            }
        }
    }

    /** 取本机 IPv4 的 /24 前缀（如 "192.168.1"）；无可用地址返回 null */
    fun localIpv4Prefix(): String? {
        scanPrefix?.let { return it }
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (iface in interfaces.toList()) {
            val usable = runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false)
            if (!usable) continue
            for (addr in iface.inetAddresses.toList()) {
                if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                    val host = addr.hostAddress ?: continue
                    if (host.startsWith("127.")) continue
                    return host.substringBeforeLast('.')
                }
            }
        }
        return null
    }

    /**
     * 扫描 [prefix].1 ~ [prefix].254 的 [port] 端口。
     * 返回按末段排序的可达地址列表。
     */
    suspend fun scanSubnet(
        prefix: String,
        port: Int = DEFAULT_PORT,
        onProgress: (scanned: Int, total: Int, found: List<String>) -> Unit = { _, _, _ -> },
    ): List<String> = coroutineScope {
        val targets = (1..HOSTS_PER_SUBNET).map { "$prefix.$it" }
        val found = Collections.synchronizedList(mutableListOf<String>())
        val scanned = AtomicInteger(0)
        val semaphore = Semaphore(CONCURRENCY)

        targets.map { ip ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    if (probe(ip, port)) found.add(ip)
                    val done = scanned.incrementAndGet()
                    onProgress(done, targets.size, found.toList())
                }
            }
        }.forEach { it.await() }

        found.sortedBy { it.substringAfterLast('.').toIntOrNull() ?: Int.MAX_VALUE }
    }

    /** 单个地址 TCP 连接探测：能连上即视为候选设备（绑定 scanNetwork 时绕过 VPN 隧道） */
    private fun probe(ip: String, port: Int): Boolean = runCatching {
        val socket = scanNetwork?.socketFactory?.createSocket() ?: Socket()
        socket.use { it.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS) }
    }.isSuccess
}
