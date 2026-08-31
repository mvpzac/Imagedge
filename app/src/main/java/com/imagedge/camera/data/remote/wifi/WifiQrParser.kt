package com.imagedge.camera.data.remote.wifi

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/28
 *     desc   : WiFi 二维码内容解析（标准 WIFI:T/S/P/H 格式，索尼相机「连接智能手机」二维码）
 *     version: 1.0
 * </pre>
 */

/** WiFi 二维码解析结果 */
data class WifiQrInfo(
    val ssid: String,
    val password: String?,
    val authType: String?,   // WPA / WEP / nopass
    val model: String? = null,  // 索尼 W01 格式附带（C 字段）
    val bssid: String? = null   // 索尼 W01 格式附带（M 字段，配网时精确匹配用）
)

/**
 * 解析 WiFi 二维码内容，支持两种格式：
 *
 * 1. 标准格式：`WIFI:T:WPA;S:DIRECT-xx-ZV-E10;P:password;H:false;;`
 *    字段：T=认证类型，S=SSID，P=密码，H=隐藏；字段顺序不固定，值可含转义 `\;` `\:`。
 *
 * 2. 索尼私有格式（ZV-E10「智能手机连接」实机采样）：
 *    `W01:S:ZrE1;P:c4dqykdw;C:ZV-E10;M:7CB8DAEBF0E5;`
 *    字段：S=热点名尾段，P=密码，C=设备名（相机菜单自定义名，实测语义——非固定机型名），
 *    M=相机 MAC。认证类型未标注——索尼热点固定 WPA2。
 *    实测热点完整名 = DIRECT-{S}:{C}（例 DIRECT-ZrE1:ZV-E10）。
 *
 * @return 解析失败（两种格式都不匹配或缺 SSID）返回 null
 */
fun parseWifiQr(content: String): WifiQrInfo? {
    val trimmed = content.trim()
    return when {
        trimmed.startsWith("WIFI:", ignoreCase = true) -> parseStandardWifiQr(trimmed)
        trimmed.startsWith("W01:") -> parseSonyWifiQr(trimmed)
        else -> null
    }
}

/** 解析索尼 W01 私有格式：`W01:S:...;P:...;C:...;M:...;`（分号分隔，值内转义未定义，按原文取） */
private fun parseSonyWifiQr(content: String): WifiQrInfo? {
    val body = content.removePrefix("W01:").trimEnd(';')
    if (body.isEmpty()) return null
    var suffix: String? = null
    var password: String? = null
    var model: String? = null
    var mac: String? = null
    for (field in body.split(';')) {
        val idx = field.indexOf(':')
        if (idx <= 0) continue
        val key = field.substring(0, idx).uppercase()
        val value = field.substring(idx + 1)
        when (key) {
            "S" -> suffix = value.takeIf { it.isNotEmpty() }
            "P" -> password = value.takeIf { it.isNotEmpty() }
            "C" -> model = value.takeIf { it.isNotEmpty() }
            "M" -> mac = value.takeIf { it.isNotEmpty() }
        }
    }
    val s = suffix ?: return null
    // 实机验证（ZV-E10）：二维码 S 字段只是热点名尾段，完整 SSID = DIRECT-{S}:{机型}
    // 例：S=ZrE1, C=ZV-E10 → 实际广播名 DIRECT-ZrE1:ZV-E10
    val fullSsid = if (model != null) "DIRECT-$s:$model" else "DIRECT-$s"
    return WifiQrInfo(
        ssid = fullSsid,
        password = password,
        authType = "WPA",
        model = model,
        bssid = mac?.let { formatMac(it) }
    )
}

/** 解析标准 WIFI:T/S/P/H 格式 */
private fun parseStandardWifiQr(content: String): WifiQrInfo? {
    val body = content.substring(5).trimEnd(';')
    if (body.isEmpty()) return null

    val fields = mutableMapOf<String, String>()
    // 按未转义的 ; 分割（兼容 \; 转义：扫描时忽略前面带反斜杠的分号）
    val sb = StringBuilder()
    val parts = mutableListOf<String>()
    var escaped = false
    for (ch in body) {
        if (escaped) {
            sb.append(ch)
            escaped = false
            continue
        }
        when (ch) {
            '\\' -> { sb.append(ch); escaped = true }
            ';' -> { parts.add(sb.toString()); sb.clear() }
            else -> sb.append(ch)
        }
    }
    parts.add(sb.toString())

    for (field in parts) {
        val idx = field.indexOf(':')
        if (idx <= 0) continue
        val key = field.substring(0, idx).uppercase()
        val value = unescape(field.substring(idx + 1))
        fields[key] = value
    }

    val ssid = fields["S"]?.takeIf { it.isNotEmpty() } ?: return null
    return WifiQrInfo(
        ssid = ssid,
        password = fields["P"]?.takeIf { it.isNotEmpty() },
        authType = fields["T"]?.uppercase()
    )
}

/** 索尼 M 字段为无分隔 MAC（7CB8DAEBF0E5），格式化为冒号分隔（配网 BSSID 匹配需要） */
private fun formatMac(raw: String): String? {
    val hex = raw.filter { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' }
    if (hex.length != 12) return null
    return hex.chunked(2).joinToString(":").uppercase()
}

/** 还原转义字符（\; → ;，\: → :，\\ → \） */
private fun unescape(value: String): String {
    val sb = StringBuilder(value.length)
    var escaped = false
    for (ch in value) {
        if (escaped) {
            sb.append(ch)
            escaped = false
        } else if (ch == '\\') {
            escaped = true
        } else {
            sb.append(ch)
        }
    }
    if (escaped) sb.append('\\')
    return sb.toString()
}
