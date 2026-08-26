package com.four.toolboxmobile

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
 */
object LanScanner {

    const val DEFAULT_PORT = 8090
    private const val CONNECT_TIMEOUT_MS = 300
    private const val CONCURRENCY = 64
    private const val HOSTS_PER_SUBNET = 254

    /** 取本机 IPv4 的 /24 前缀（如 "192.168.1"）；无可用地址返回 null */
    fun localIpv4Prefix(): String? {
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

    /** 单个地址 TCP 连接探测：能连上即视为候选设备 */
    private fun probe(ip: String, port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS) }
    }.isSuccess
}
