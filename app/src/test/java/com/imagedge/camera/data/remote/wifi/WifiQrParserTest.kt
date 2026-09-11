package com.imagedge.camera.data.remote.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 二维码解析与认证方式归一化的回归测试。
 *
 * 覆盖 P0 修复：配网凭据必须按 [WifiAuth] 分支设置，
 * 开放热点（T:nopass）与 WPA3（T:SAE）不得被当成「空密码 WPA2」。
 */
class WifiQrParserTest {

    @Test
    fun `sony W01 code uses hotspot suffix plus camera name and defaults to WPA2`() {
        val info = parseWifiQr("W01:S:ZrE1;P:c4dqykdw;C:ZV-E10;M:7CB8DAEBF0E5;")
        requireNotNull(info)
        assertEquals("DIRECT-ZrE1:ZV-E10", info.ssid)
        assertEquals("c4dqykdw", info.password)
        assertEquals("7C:B8:DA:EB:F0:E5", info.bssid)
        assertEquals(WifiAuth.WPA2, info.auth)
    }

    @Test
    fun `standard WIFI code parses escaped fields and WPA auth`() {
        val info = parseWifiQr("WIFI:T:WPA;S:DIRECT-abc:cam;P:pass\\;word;;")
        requireNotNull(info)
        assertEquals("DIRECT-abc:cam", info.ssid)
        assertEquals("pass;word", info.password)
        assertEquals(WifiAuth.WPA2, info.auth)
    }

    @Test
    fun `open network keeps null password and maps to OPEN`() {
        val info = parseWifiQr("WIFI:T:nopass;S:DIRECT-open;;")
        requireNotNull(info)
        assertNull(info.password)
        assertEquals(WifiAuth.OPEN, info.auth)
    }

    @Test
    fun `wpa3 and wep are distinguished so credentials are sent correctly`() {
        assertEquals(WifiAuth.WPA3, parseWifiQr("WIFI:T:SAE;S:CAM;P:01234567;;")?.auth)
        assertEquals(WifiAuth.WEP, parseWifiQr("WIFI:T:WEP;S:CAM;P:0123;;")?.auth)
    }

    @Test
    fun `missing password is reported instead of being sent as empty WPA2`() {
        val info = parseWifiQr("WIFI:T:WPA;S:CAM;;")
        requireNotNull(info)
        assertNull(info.password)
        // 认证方式仍是 WPA2 → 调用方据此给出「二维码缺少密码」的可读错误
        assertEquals(WifiAuth.WPA2, info.auth)
    }

    @Test
    fun `non wifi content is rejected`() {
        assertNull(parseWifiQr("https://example.com"))
        assertNull(parseWifiQr("WIFI:T:WPA;P:only-password;;"))
    }
}
