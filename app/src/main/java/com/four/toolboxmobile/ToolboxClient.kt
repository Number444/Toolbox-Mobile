package com.four.toolboxmobile

import android.net.Uri
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okio.BufferedSink
import org.json.JSONObject

/** /api/status 响应 data 字段（全部可空，服务端字段缺失时降级显示 "--"） */
data class SystemStatus(
    val cpuPercent: Double?,
    val memoryUsedGB: Double?,
    val memoryTotalGB: Double?,
    val diskName: String?,
    val diskFreeGB: Double?,
    val diskTotalGB: Double?,
    val hasBattery: Boolean,
    val batteryPercent: Int?,
    val batteryStatus: String?,
    val uptime: String?,
    val ipv4: String?,
)

/** 操作日志条目（/api/events） */
data class EventItem(val time: String, val command: String, val fromIp: String)

/** 设备条目（/api/devices）：online=true 为当前连接中设备（可踢出），false 为已知设备（可移除） */
data class DeviceEntry(val name: String, val ip: String, val subtitle: String, val online: Boolean)

/** 电脑共享的文件（/api/transfer/list） */
data class ShareItem(val id: String, val name: String, val size: Long)

/** 打开的下载流：调用方负责读取并 [ResponseBody.close]；fileName 解析失败时为 null（调用方回退用共享名） */
data class DownloadStream(val fileName: String?, val body: ResponseBody)

/** 探测/请求结果三分：成功 / 未认证（401）/ 失败 */
sealed interface ApiResult {
    data class Ok(val status: SystemStatus) : ApiResult
    data object Unauthorized : ApiResult
    data class Failure(val message: String) : ApiResult
}

/** 通用调用结果（status 之外的接口统一用这个） */
sealed interface CallResult<out T> {
    data class Ok<T>(val value: T) : CallResult<T>
    data object Unauthorized : CallResult<Nothing>
    data class Fail(val message: String) : CallResult<Nothing>
}

/**
 * Toolbox 服务端 HTTP 客户端。
 * 协议契约见 docs/设计方案-整体框架.md §5.3（与 HTML 客户端逐字节一致）：
 * - 会话：POST /api/auth 成功后服务端 Set-Cookie: rc_session，由 [MemoryCookieJar] 保存并自动携带
 * - CSRF：写操作须带 X-Requested-With: RemoteControl，否则服务端 403
 * - 上传文件名：X-File-Name 头，percent-encode UTF-8（服务端 Uri.UnescapeDataString 解码，
 *   空格必须是 %20——用 android.net.Uri.encode，不能用 URLEncoder 的 +）
 */
class ToolboxClient {

    private val cookieJar = MemoryCookieJar()
    private val http = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /** 上传/下载等大传输单独用不限读超时的客户端 */
    private val httpTransfer = http.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .build()

    private fun base(ip: String) = "http://$ip:${LanScanner.DEFAULT_PORT}"

    // ==================== 状态 ====================

    /** GET /api/status：探测连接 + 拉取系统状态（轮询复用同一方法） */
    suspend fun fetchStatus(ip: String): ApiResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("${base(ip)}/api/status").get().build()
            http.newCall(request).execute().use { resp ->
                when {
                    resp.code == 401 -> ApiResult.Unauthorized
                    !resp.isSuccessful -> ApiResult.Failure("HTTP ${resp.code}")
                    else -> {
                        val json = JSONObject(resp.body?.string().orEmpty())
                        if (!json.optBoolean("success")) {
                            ApiResult.Failure(json.optString("error", "unknown"))
                        } else {
                            ApiResult.Ok(parseStatus(json.optJSONObject("data") ?: JSONObject()))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            ApiResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    /** POST /api/auth：密钥登录。成功时 rc_session Cookie 已由 CookieJar 保存 */
    suspend fun auth(ip: String, token: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("token", token).toString()
                .toRequestBody(JSON_MEDIA)
            val request = Request.Builder().url("${base(ip)}/api/auth").post(body).build()
            http.newCall(request).execute().use { resp ->
                resp.isSuccessful && JSONObject(resp.body?.string().orEmpty()).optBoolean("success")
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 断开：丢弃会话 Cookie（下次访问需重新认证） */
    fun clearSession() = cookieJar.clear()

    // ==================== 指令 ====================

    /** POST /api/command。危险指令调用方需在 [args] 里带 confirm:true（服务端强校验） */
    suspend fun sendCommand(ip: String, command: String, args: JSONObject = JSONObject()): CallResult<String> {
        val payload = JSONObject().put("command", command).put("args", args)
        return postJson(ip, "/api/command", payload).mapOk {
            if (it.optBoolean("success")) command else throw CmdFail(it.optString("error", "执行失败"))
        }
    }

    /** POST /api/app/shutdown：关闭 Toolbox 进程 */
    suspend fun appShutdown(ip: String): CallResult<String> =
        postJson(ip, "/api/app/shutdown", JSONObject()).mapOk {
            if (it.optBoolean("success")) "已请求关闭" else throw CmdFail(it.optString("error", "关闭失败"))
        }

    // ==================== 日志 / 设备 ====================

    suspend fun fetchEvents(ip: String): CallResult<List<EventItem>> =
        getJson(ip, "/api/events").mapOk { json ->
            val arr = json.optJSONArray("data")
            buildList {
                for (i in 0 until (arr?.length() ?: 0)) {
                    val o = arr!!.optJSONObject(i) ?: continue
                    add(EventItem(o.optString("time"), o.optString("command"), o.optString("fromIp", "?")))
                }
            }
        }

    suspend fun fetchDevices(ip: String): CallResult<List<DeviceEntry>> =
        getJson(ip, "/api/devices").mapOk { json ->
            val data = json.optJSONObject("data") ?: JSONObject()
            buildList {
                data.optJSONArray("connected")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        add(DeviceEntry(o.optString("deviceName"), o.optString("ip"),
                            "${o.optString("lastActive")} 活跃", online = true))
                    }
                }
                data.optJSONArray("known")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        add(DeviceEntry(o.optString("deviceName"), o.optString("ip"),
                            "${o.optString("firstSeen")} 首连", online = false))
                    }
                }
            }
        }

    suspend fun kickDevice(ip: String, targetIp: String): CallResult<String> =
        postJson(ip, "/api/devices/kick", JSONObject().put("ip", targetIp)).mapOk {
            if (it.optBoolean("success")) "已处理" else throw CmdFail(it.optString("error", "操作失败"))
        }

    // ==================== 文件传输 ====================

    suspend fun fetchShares(ip: String): CallResult<List<ShareItem>> =
        getJson(ip, "/api/transfer/list").mapOk { json ->
            val arr = json.optJSONArray("data")
            buildList {
                for (i in 0 until (arr?.length() ?: 0)) {
                    val o = arr!!.optJSONObject(i) ?: continue
                    add(ShareItem(o.optString("id"), o.optString("name"), o.optLong("size")))
                }
            }
        }

    /**
     * POST /api/transfer/upload：raw body 流式上传 + X-File-Name 头（对齐 HTML 上传路径）。
     * [openStream] 在 IO 线程被调用一次；[onProgress] 汇报 0~100。
     */
    suspend fun upload(
        ip: String,
        fileName: String,
        contentLength: Long,
        openStream: () -> InputStream?,
        onProgress: (percent: Int) -> Unit,
    ): CallResult<String> = withContext(Dispatchers.IO) {
        try {
            val body = object : RequestBody() {
                override fun contentType() = OCTET_MEDIA
                override fun contentLength() = contentLength
                override fun writeTo(sink: BufferedSink) {
                    val input = openStream() ?: throw java.io.IOException("无法读取所选文件")
                    input.use { ins ->
                        val buf = ByteArray(64 * 1024)
                        var written = 0L
                        while (true) {
                            val read = ins.read(buf)
                            if (read < 0) break
                            sink.write(buf, 0, read)
                            written += read
                            if (contentLength > 0) {
                                onProgress(((written * 100) / contentLength).toInt().coerceIn(0, 100))
                            }
                        }
                        sink.flush()
                    }
                }
            }
            val request = Request.Builder()
                .url("${base(ip)}/api/transfer/upload")
                .post(body)
                .header("X-Requested-With", "RemoteControl")
                .header("X-File-Name", Uri.encode(fileName))
                .build()
            httpTransfer.newCall(request).execute().use { resp ->
                when {
                    resp.code == 401 -> CallResult.Unauthorized
                    !resp.isSuccessful -> CallResult.Fail("HTTP ${resp.code}")
                    else -> {
                        val json = JSONObject(resp.body?.string().orEmpty())
                        if (json.optBoolean("success")) CallResult.Ok("上传完成")
                        else CallResult.Fail(json.optString("error", "上传失败"))
                    }
                }
            }
        } catch (e: Exception) {
            CallResult.Fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /** GET /api/transfer/download?id=...：打开下载流（调用方负责消费并关闭 body） */
    suspend fun openDownload(ip: String, id: String): CallResult<DownloadStream> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${base(ip)}/api/transfer/download?id=${Uri.encode(id)}")
                .get().build()
            val resp = httpTransfer.newCall(request).execute()
            when {
                resp.code == 401 -> { resp.close(); CallResult.Unauthorized }
                !resp.isSuccessful -> { resp.close(); CallResult.Fail("HTTP ${resp.code}") }
                else -> {
                    val body = resp.body
                    if (body == null) { resp.close(); CallResult.Fail("空响应") }
                    else CallResult.Ok(DownloadStream(parseDispositionName(resp.header("Content-Disposition")), body))
                }
            }
        } catch (e: Exception) {
            CallResult.Fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /** 解析 Content-Disposition: attachment; filename*=UTF-8''<escaped> */
    private fun parseDispositionName(header: String?): String? {
        if (header == null) return null
        val mark = "filename*=UTF-8''"
        val idx = header.indexOf(mark, ignoreCase = true)
        if (idx < 0) return null
        return runCatching { Uri.decode(header.substring(idx + mark.length)) }.getOrNull()
    }

    // ==================== 内部 ====================

    private class CmdFail(message: String) : Exception(message)

    private inline fun <T, R> CallResult<T>.mapOk(transform: (T) -> R): CallResult<R> = when (this) {
        is CallResult.Ok -> try {
            CallResult.Ok(transform(value))
        } catch (e: CmdFail) {
            CallResult.Fail(e.message ?: "失败")
        }
        is CallResult.Unauthorized -> CallResult.Unauthorized
        is CallResult.Fail -> this
    }

    /** JSON GET：统一成功/401/失败处理（仅返回 success=true 的 data 所在 JSON） */
    private suspend fun getJson(ip: String, path: String): CallResult<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("${base(ip)}$path").get().build()
            http.newCall(request).execute().use { resp ->
                when {
                    resp.code == 401 -> CallResult.Unauthorized
                    !resp.isSuccessful -> CallResult.Fail("HTTP ${resp.code}")
                    else -> {
                        val json = JSONObject(resp.body?.string().orEmpty())
                        if (json.optBoolean("success")) CallResult.Ok(json)
                        else CallResult.Fail(json.optString("error", "请求失败"))
                    }
                }
            }
        } catch (e: Exception) {
            CallResult.Fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /** JSON POST：写操作恒带 X-Requested-With（CSRF 防线） */
    private suspend fun postJson(ip: String, path: String, payload: JSONObject): CallResult<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("${base(ip)}$path")
                    .post(payload.toString().toRequestBody(JSON_MEDIA))
                    .header("X-Requested-With", "RemoteControl")
                    .build()
                http.newCall(request).execute().use { resp ->
                    when {
                        resp.code == 401 -> CallResult.Unauthorized
                        !resp.isSuccessful -> CallResult.Fail("HTTP ${resp.code}")
                        else -> CallResult.Ok(JSONObject(resp.body?.string().orEmpty()))
                    }
                }
            } catch (e: Exception) {
                CallResult.Fail(e.message ?: e.javaClass.simpleName)
            }
        }

    private fun parseStatus(d: JSONObject): SystemStatus {
        val disk = d.optJSONArray("disks")?.optJSONObject(0)
        val battery = d.optJSONObject("battery")
        return SystemStatus(
            cpuPercent = d.dbl("cpuPercent"),
            memoryUsedGB = d.dbl("memoryUsedGB"),
            memoryTotalGB = d.dbl("memoryTotalGB"),
            diskName = disk?.optString("name")?.takeIf { it.isNotEmpty() },
            diskFreeGB = disk?.dbl("freeGB"),
            diskTotalGB = disk?.dbl("totalGB"),
            hasBattery = battery?.optBoolean("isBatteryPresent") == true,
            batteryPercent = battery?.let { if (it.isNull("percent")) null else it.optInt("percent") },
            batteryStatus = battery?.optString("status")?.takeIf { it.isNotEmpty() },
            uptime = d.optString("uptime").takeIf { it.isNotEmpty() },
            ipv4 = d.optString("ipv4").takeIf { it.isNotEmpty() },
        )
    }

    private fun JSONObject.dbl(name: String): Double? =
        if (has(name) && !isNull(name)) runCatching { getDouble(name) }.getOrNull() else null

    /** 内存 CookieJar：按 host 保存 Cookie，等价浏览器的会话管理（进程内存级，不落盘） */
    private class MemoryCookieJar : CookieJar {
        private val store = mutableMapOf<String, List<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isNotEmpty()) store[url.host] = cookies
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store[url.host]?.filter { it.expiresAt > System.currentTimeMillis() } ?: emptyList()

        fun clear() = store.clear()
    }

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
        val OCTET_MEDIA = "application/octet-stream".toMediaType()
    }
}
