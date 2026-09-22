package com.imagedge.camera.upnp

import com.imagedge.camera.core.common.AppLog
import com.imagedge.camera.core.io.BoundedOutputStream
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : UPnP/SOAP 客户端（"发送到智能手机"模式，端口 64321）
 *             传输开始/结束（XPushList）+ 目录浏览（ContentDirectory）+ HTTP 下载
 *     version: 1.0
 * </pre>
 */

/** UPnP 服务默认端口 */
const val UPNP_PORT = 64321

/** 日志 tag */
private const val TAG = "upnp"
private const val MAX_SERVICE_DESCRIPTION_BYTES = 1024L * 1024
private const val MAX_SOAP_RESPONSE_BYTES = 4L * 1024 * 1024
private const val MAX_ERROR_BODY_BYTES = 4L * 1024
private const val MAX_XML_NODES = 20_000
private const val MAX_XML_DEPTH = 64

/** SOAP Action 命名空间 */
object SoapActionNs {
    const val CONTENT_DIRECTORY = "urn:schemas-upnp-org:service:ContentDirectory:1"
    const val X_PUSH_LIST = "urn:schemas-sony-com:service:XPushList:1"
    const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"
}

/**
 * UPnP/SOAP 客户端
 *
 * 用法：
 * ```
 * val client = UpnpClient("192.168.122.1")
 * client.startTransfer()
 * val root = client.browse("0")
 * client.endTransfer()
 * ```
 */
class UpnpClient(
    private val host: String,
    private val port: Int = UPNP_PORT
) {

    init {
        require(isPrivateIpv4(host)) { "UPnP 目标必须是私有或链路本地 IPv4 地址" }
        require(port in 1..65535) { "UPnP 端口无效" }
    }

    private val baseUrl: HttpUrl = "http://$host:$port/".toHttpUrl()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // Redirect destinations are camera-controlled input. Re-resolving each hop safely is
        // preferable to OkHttp silently following a redirect to another LAN/public host.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val activeCall = AtomicReference<Call?>(null)

    private val xmlMediaType = "text/xml; charset=utf-8".toMediaType()

    /** 从服务描述解析出的控制 URL 与 serviceType（解析失败则用默认推断值） */
    private var contentDirectoryControlUrl: String? = null
    private var xPushListControlUrl: String? = null
    private var connectionManagerControlUrl: String? = null
    private var contentDirectoryServiceType: String? = null
    private var xPushListServiceType: String? = null

    // ── 服务信息 ─────────────────────────────────────────────────────

    /** 获取服务描述（DmsDescPush.xml），并解析服务控制 URL */
    fun getServiceDescription(): String {
        val request = Request.Builder()
            .url(resolveUrl("DmsDescPush.xml"))
            .header("User-Agent", "Imagedge")
            .build()
        val xml = execute(request) { response ->
            if (!response.isSuccessful) throw IllegalStateException("获取服务描述失败：HTTP ${response.code}")
            readBodyLimited(response.body, MAX_SERVICE_DESCRIPTION_BYTES, "服务描述")
        }
        AppLog.i(TAG, "已读取服务描述（${xml.length} 字符）")
        // 服务描述是相机端输入：非法 XML/越界 URL 必须失败关闭，不能吞异常继续连接。
        parseServiceControlUrls(xml)
        return xml
    }

    /** 从 DmsDescPush.xml 解析 ContentDirectory / XPushList 的控制 URL 与 serviceType */
    private fun parseServiceControlUrls(xml: String) {
        val doc = parseXml(xml)
        val services = doc.getElementsByTagName("service")
        require(services.length in 1..256) { "服务描述中的 service 数量无效" }
        var foundContentDirectory = false
        var foundXPushList = false
        for (i in 0 until services.length) {
            val service = services.item(i) as Element
            val serviceType = service.getElementsByTagName("serviceType")?.item(0)?.textContent
                ?.let(::validatedServiceType) ?: continue
            val controlUrl = service.getElementsByTagName("controlURL")?.item(0)?.textContent ?: continue
            AppLog.i(TAG, "发现服务 $serviceType → $controlUrl")
            when {
                serviceType.contains("ContentDirectory") -> {
                    contentDirectoryControlUrl = resolveUrl(controlUrl)
                    contentDirectoryServiceType = serviceType
                    foundContentDirectory = true
                }
                serviceType.contains("XPushList") -> {
                    xPushListControlUrl = resolveUrl(controlUrl)
                    xPushListServiceType = serviceType
                    foundXPushList = true
                }
                serviceType.contains("ConnectionManager") -> {
                    connectionManagerControlUrl = resolveUrl(controlUrl)
                }
            }
        }
        if (!foundContentDirectory) {
            throw IllegalArgumentException("服务描述缺少 ContentDirectory")
        }
        if (!foundXPushList) {
            AppLog.w(TAG, "未找到 XPushList 服务，将回退默认路径 /upnp/control/XPushList")
        }
    }

    /** 解析相对 URL（索尼 DDD 通常用相对路径，如 /upnp/control/ContentDirectory） */
    private fun resolveUrl(url: String): String {
        val resolved = baseUrl.resolve(url.trim())
            ?: throw IllegalArgumentException("无效的相机 URL")
        require(resolved.scheme == "http") { "相机 URL 只允许 HTTP" }
        require(resolved.host == baseUrl.host) { "相机 URL 不允许跳转到其他主机" }
        require(resolved.port == baseUrl.port) { "相机 URL 不允许使用未授权端口" }
        require(resolved.username.isEmpty() && resolved.password.isEmpty()) { "相机 URL 不允许包含凭据" }
        return resolved.toString()
    }

    private fun validatedServiceType(value: String): String {
        val normalized = value.trim()
        require(normalized.length in 1..256 && normalized.startsWith("urn:") &&
            normalized.none { it == '\r' || it == '\n' || it == '"' || it == '<' || it == '>' }
        ) { "非法 serviceType" }
        return normalized
    }

    private fun isPrivateIpv4(value: String): Boolean {
        val parts = value.split('.')
        val octets = parts.mapNotNull(String::toIntOrNull)
        if (parts.size != 4 || octets.size != 4 || octets.any { it !in 0..255 }) return false
        return when (octets[0]) {
            10 -> true
            172 -> octets[1] in 16..31
            192 -> octets[1] == 168
            169 -> octets[1] == 254
            else -> false
        }
    }

    // ── 传输控制 ─────────────────────────────────────────────────────

    /** 激活 DMS 会话（标准 ConnectionManager:1 GetProtocolInfo 无参调用，索尼 DMS 需先握手才响应 Browse） */
    fun activateSession() {
        val body = "<u:GetProtocolInfo xmlns:u=\"${SoapActionNs.CONNECTION_MANAGER}\"></u:GetProtocolInfo>"
        runCatching { soapCall(SoapActionNs.CONNECTION_MANAGER, "GetProtocolInfo", body) }
            .onSuccess { AppLog.i(TAG, "DMS 会话激活成功（GetProtocolInfo）") }
            .onFailure { AppLog.w(TAG, "GetProtocolInfo 失败（非致命）：${it.message?.take(200)}") }
    }

    /** 开始传输（X_TransferStart，需带 SOAP Body 元素，空 body 会被相机拒 507） */
    fun startTransfer() {
        val body = "<u:X_TransferStart xmlns:u=\"${SoapActionNs.X_PUSH_LIST}\"></u:X_TransferStart>"
        soapCall(SoapActionNs.X_PUSH_LIST, "X_TransferStart", body)
    }

    /** 结束传输 */
    fun endTransfer() {
        val body = "<u:X_TransferEnd xmlns:u=\"${SoapActionNs.X_PUSH_LIST}\"><ErrCode>0</ErrCode></u:X_TransferEnd>"
        soapCall(SoapActionNs.X_PUSH_LIST, "X_TransferEnd", body)
    }

    // ── 目录浏览 ─────────────────────────────────────────────────────

    /**
     * 浏览目录
     * @param objectId 容器 ID（"0" 为根）
     * @param startIndex 分页起始
     * @param count 请求数量
     */
    fun browse(objectId: String = "0", startIndex: Int = 0, count: Int = 100): BrowseResult {
        // 索尼的 serviceType 可能与标准不同（如 sony-com 命名空间），用从 DDD 解析的真实值
        val serviceType = contentDirectoryServiceType ?: SoapActionNs.CONTENT_DIRECTORY
        val body = buildString {
            append("<u:Browse xmlns:u=\"$serviceType\">")
            append("<ObjectID>").append(xmlEscape(objectId.take(512))).append("</ObjectID>")
            append("<BrowseFlag>BrowseDirectChildren</BrowseFlag>")
            append("<Filter>*</Filter>")
            append("<StartingIndex>").append(startIndex).append("</StartingIndex>")
            append("<RequestedCount>").append(count).append("</RequestedCount>")
            append("<SortCriteria></SortCriteria>")
            append("</u:Browse>")
        }
        val response = soapCall(SoapActionNs.CONTENT_DIRECTORY, "Browse", body)
        val result = parseBrowseResponse(response)
        AppLog.i(TAG, "浏览 objectId=$objectId → ${result.numberReturned}/${result.totalMatches} 项")
        return result
    }

    // ── 文件下载 ─────────────────────────────────────────────────────

    /**
     * 下载文件到输出流
     * @param url 完整下载 URL（来自 Browse 结果的 res.url）
     */
    fun download(url: String, output: OutputStream, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val fullUrl = resolveUrl(url)
        AppLog.i(TAG, "HTTP 下载：$fullUrl")
        val request = Request.Builder().url(fullUrl).build()
        execute(request) { response ->
            if (!response.isSuccessful) throw IllegalStateException("下载失败：HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("空响应")
            val total = body.contentLength()
            var loaded = 0L
            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    loaded += read
                    onProgress(loaded, total)
                }
            }
            output.flush()
        }
    }

    /** Interrupt a blocking browse/download before the owning channel is discarded. */
    fun cancelActiveCall() {
        activeCall.getAndSet(null)?.cancel()
    }

    // ── SOAP 调用 ────────────────────────────────────────────────────

    /**
     * 发送 SOAP 请求
     * @param serviceNs 服务命名空间（决定 SOAPAction header）
     * @param actionName SOAP Action 名
     * @param body SOAP Body 内容（不含 envelope 包裹）
     */
    private fun soapCall(serviceNs: String, actionName: String, body: String): String {
        val envelope = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
            append("<s:Body>")
            append(body)
            append("</s:Body>")
            append("</s:Envelope>")
        }

        // 使用从 DDD 解析的 serviceType 与控制 URL，解析失败则用默认推断值
        val (controlUrl, actionType) = when (serviceNs) {
            SoapActionNs.CONTENT_DIRECTORY -> contentDirectoryControlUrl?.let { url ->
                url to (contentDirectoryServiceType ?: serviceNs)
            } ?: (resolveUrl("upnp/control/${serviceName(serviceNs)}") to serviceNs)
            SoapActionNs.X_PUSH_LIST -> xPushListControlUrl?.let { url ->
                url to (xPushListServiceType ?: serviceNs)
            } ?: (resolveUrl("upnp/control/${serviceName(serviceNs)}") to serviceNs)
            else -> resolveUrl("upnp/control/${serviceName(serviceNs)}") to serviceNs
        }
        AppLog.i(TAG, "SOAP 调用：$actionName → $controlUrl（serviceType=$actionType）")

        val request = Request.Builder()
            .url(controlUrl)
            .header("SOAPACTION", "\"$actionType#$actionName\"")
            .header("User-Agent", "Imagedge")
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .post(envelope.toRequestBody(xmlMediaType))
            .build()

        return execute(request) { response ->
            if (!response.isSuccessful) {
                val errBody = runCatching {
                    readBodyLimited(response.body, MAX_ERROR_BODY_BYTES, "SOAP 错误体")
                }.getOrNull()
                AppLog.e(TAG, "SOAP 调用失败：$actionName，HTTP ${response.code}")
                throw IllegalStateException(
                    "SOAP 调用失败：$actionName HTTP ${response.code}" +
                        if (errBody.isNullOrBlank()) "" else "：${errBody.take(200)}"
                )
            }
            readBodyLimited(response.body, MAX_SOAP_RESPONSE_BYTES, "SOAP 响应")
        }
    }

    /** 从命名空间推导控制路径（ContentDirectory / XPushList / ConnectionManager） */
    private fun serviceName(serviceNs: String): String = when (serviceNs) {
        SoapActionNs.CONTENT_DIRECTORY -> "ContentDirectory"
        SoapActionNs.X_PUSH_LIST -> "XPushList"
        SoapActionNs.CONNECTION_MANAGER -> "ConnectionManager"
        else -> throw IllegalArgumentException("未知服务命名空间：$serviceNs")
    }

    // ── DIDL-Lite 解析 ───────────────────────────────────────────────

    /** 解析 BrowseResponse，提取 DIDL-Lite 内容（internal 供单元测试） */
    internal fun parseBrowseResponse(soapXml: String): BrowseResult {
        val doc = parseXml(soapXml)

        // 提取 Result（XML 转义的 DIDL-Lite）
        val resultElement = firstElement(doc.documentElement, "Result")
            ?: return BrowseResult(emptyList(), 0, 0)
        val escapedDidl = resultElement.textContent

        val numberReturned = firstElement(doc.documentElement, "NumberReturned")?.textContent?.toIntOrNull() ?: 0
        val totalMatches = firstElement(doc.documentElement, "TotalMatches")?.textContent?.toIntOrNull() ?: 0

        val items = parseDidl(escapedDidl)
        return BrowseResult(items, numberReturned, totalMatches)
    }

    /** 解析 DIDL-Lite XML（已反转义，internal 供单元测试） */
    internal fun parseDidl(didlXml: String): List<UpnpItem> {
        if (didlXml.isBlank()) return emptyList()
        val doc = parseXml(didlXml)
        val items = mutableListOf<UpnpItem>()

        val elements = doc.getElementsByTagName("*")
        for (i in 0 until elements.length) {
            val element = elements.item(i) as? Element ?: continue
            when (localName(element)) {
                "item" -> items.add(parseMediaItem(element, isDirectory = false))
                "container" -> items.add(parseMediaItem(element, isDirectory = true))
            }
        }
        return items
    }

    /** 解析单个 item/container 元素（item 解析多个 res，选最优下载分辨率） */
    private fun parseMediaItem(element: Element, isDirectory: Boolean): UpnpItem {
        val id = element.getAttribute("id").ifEmpty { null }
        val parentId = element.getAttribute("parentID").ifEmpty { null }
        val title = childText(element, "title") ?: ""
        val upnpClass = childText(element, "class") ?: ""
        val contentType = childText(element, "contentType")

        var url: String? = null
        var size: Long? = null

        if (!isDirectory) {
            // 收集所有 <res>（索尼 DMS 会为同一照片返回 LRG/SM/TN/ORG 多分辨率）
            val resElements = childElements(element, "res")
            var bestRank = -1
            for (res in resElements) {
                val resUrl = res.textContent?.trim().orEmpty()
                if (resUrl.isEmpty()) continue
                val protocolInfo = res.getAttribute("protocolInfo")
                val resolution = res.getAttribute("resolution")
                val resSize = res.getAttribute("size").toLongOrNull()
                val rank = resQualityRank(resUrl, protocolInfo, resolution)
                // 下载 URL 取最优（ORG=4 > LRG=3 > SM=2 > TN=1）
                if (rank > bestRank) {
                    bestRank = rank
                    url = resUrl
                    size = resSize
                }
            }
        }

        return UpnpItem(
            id = id,
            parentId = parentId,
            title = title,
            upnpClass = upnpClass,
            contentType = contentType,
            url = url,
            size = size,
            isDirectory = isDirectory
        )
    }

    /**
     * 分辨率分级：ORG=4 / LRG=3 / SM=2 / TN=1 / 未知=2（默认中等，至少可下载）。
     * 依据 URL 关键字 → protocolInfo → resolution 宽度。
     */
    private fun resQualityRank(url: String, protocolInfo: String?, resolution: String?): Int {
        val u = url.uppercase()
        if (u.contains("ORG")) return 4
        if (u.contains("LRG") || u.contains("LARGE")) return 3
        if (u.contains("TN") || u.contains("THUMB")) return 1
        if (u.contains("SM") || u.contains("SMALL")) return 2
        val pi = protocolInfo?.uppercase().orEmpty()
        if (pi.contains("LRG")) return 3
        if (pi.contains("_TN")) return 1
        if (pi.contains("SM")) return 2
        val width = resolution?.substringBefore("x")?.toIntOrNull()
        if (width != null) {
            return when {
                width >= 2000 -> 3
                width >= 640 -> 2
                else -> 1
            }
        }
        return 2
    }

    /** 查找所有匹配 localName 的直接子元素 */
    private fun childElements(parent: Element, name: String): List<Element> {
        val result = mutableListOf<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element && localName(child) == name) result.add(child)
        }
        return result
    }

    // ── XML 工具 ─────────────────────────────────────────────────────

    private fun parseXml(xml: String): org.w3c.dom.Document {
        require(xml.length <= MAX_SOAP_RESPONSE_BYTES.toInt()) { "XML 文档过大" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isCoalescing = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            // These are security requirements, not optional tuning. Failing closed prevents an
            // unsupported parser implementation from silently re-enabling XXE/external fetches.
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val builder = factory.newDocumentBuilder()
        // trim 前导空白，避免 XML 声明前有空格导致解析失败
        return builder.parse(ByteArrayInputStream(xml.trim().toByteArray(Charsets.UTF_8))).also {
            validateDocumentShape(it)
        }
    }

    private fun validateDocumentShape(document: org.w3c.dom.Document) {
        val pending = java.util.ArrayDeque<Pair<Node, Int>>()
        pending.add(document to 0)
        var nodes = 0
        while (pending.isNotEmpty()) {
            val (node, depth) = pending.removeFirst()
            nodes++
            require(nodes <= MAX_XML_NODES) { "XML 节点数量超出上限" }
            require(depth <= MAX_XML_DEPTH) { "XML 嵌套深度超出上限" }
            val children = node.childNodes
            for (index in 0 until children.length) {
                pending.addLast(children.item(index) to depth + 1)
            }
        }
    }

    /** 取元素 localName（去掉命名空间前缀） */
    private fun localName(node: Node): String =
        node.localName ?: node.nodeName.substringAfter(":")

    /** 深度优先查找第一个匹配 localName 的元素 */
    private fun firstElement(root: Node, name: String): Element? {
        val pending = java.util.ArrayDeque<Node>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (node is Element && localName(node) == name) return node
            val children = node.childNodes
            for (index in children.length - 1 downTo 0) {
                pending.addLast(children.item(index))
            }
        }
        return null
    }

    /** 查找第一个匹配 localName 的子元素 */
    private fun firstChildElement(parent: Element, name: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element && localName(child) == name) return child
        }
        return null
    }

    /** 取子元素文本（匹配 localName） */
    private fun childText(parent: Element, name: String): String? =
        firstChildElement(parent, name)?.textContent?.trim()

    private fun xmlEscape(value: String): String = buildString(value.length) {
        for (character in value) {
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> character
                }
            )
        }
    }

    private fun readBodyLimited(body: ResponseBody?, maxBytes: Long, label: String): String {
        val responseBody = body ?: throw IllegalStateException("$label 为空")
        val declared = responseBody.contentLength()
        if (declared > maxBytes) {
            throw IllegalStateException("$label 超出上限：$declared > $maxBytes")
        }
        val sink = ByteArrayOutputStream(minOf(declared.takeIf { it > 0 } ?: 8192L, maxBytes).toInt())
        val bounded = BoundedOutputStream(sink, maxBytes)
        responseBody.byteStream().use { input -> input.copyTo(bounded) }
        return sink.toString(StandardCharsets.UTF_8.name())
    }

    private fun <T> execute(request: Request, block: (okhttp3.Response) -> T): T {
        val call = httpClient.newCall(request)
        activeCall.getAndSet(call)?.cancel()
        return try {
            call.execute().use(block)
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }
}
