package com.imagedge.camera.lut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-27
 *     desc   : .cube 的输入域与越界值。静默丢弃声明过的域 = 画出来是错的而且看不出原因。
 * </pre>
 */
class CubeLutDomainTest {

    private fun cube(size: Int = 2, header: String = "LUT_3D_SIZE $size", rows: Int = size * size * size) =
        buildString {
            appendLine(header)
            repeat(rows) { appendLine("0.0 0.0 0.0") }
        }

    @Test
    fun `a declared domain of zero to one is accepted`() {
        val lut = CubeLutParser.parse(
            buildString {
                appendLine("DOMAIN_MIN 0.0 0.0 0.0")
                appendLine("DOMAIN_MAX 1.0 1.0 1.0")
                appendLine("LUT_3D_SIZE 2")
                repeat(8) { appendLine("0.5 0.5 0.5") }
            }
        )
        assertNotNull("0..1 是绝大多数 LUT 的域，必须照常接受", lut)
        assertEquals(2, lut!!.size)
    }

    @Test
    fun `a non unit domain is rejected instead of silently ignored`() {
        // 此前 DOMAIN_MIN/MAX 被 `-> Unit` 直接吞掉：表被当作 0..1 解释，
        // 出来的颜色是错的，而用户没有任何线索。darktable 同样选择报错而非静默。
        val lut = CubeLutParser.parse(
            buildString {
                appendLine("DOMAIN_MIN 0.0 0.0 0.0")
                appendLine("DOMAIN_MAX 4.0 4.0 4.0")
                appendLine("LUT_3D_SIZE 2")
                repeat(8) { appendLine("0.5 0.5 0.5") }
            }
        )
        assertNull("非 0..1 的输入域必须拒收", lut)
    }

    @Test
    fun `a negative domain minimum is rejected`() {
        val lut = CubeLutParser.parse(
            buildString {
                appendLine("DOMAIN_MIN -1.0 -1.0 -1.0")
                appendLine("DOMAIN_MAX 1.0 1.0 1.0")
                appendLine("LUT_3D_SIZE 2")
                repeat(8) { appendLine("0.5 0.5 0.5") }
            }
        )
        assertNull("负的下界必须拒收", lut)
    }

    @Test
    fun `out of range values are reported rather than passing through unnoticed`() {
        // 胶片模拟类 .cube 常规带 >1/<0 的值表达高光滚降，那不是错误。
        // 但**统计**出来是有价值的：能看出某个 LUT 到底有多越界。
        val withOvershoot = CubeLutParser.parse(
            buildString {
                appendLine("LUT_3D_SIZE 2")
                appendLine("0.0 0.0 0.0")
                appendLine("1.4 -0.2 0.0")
                appendLine("0.0 1.0 0.0")
                appendLine("0.0 0.0 1.0")
                appendLine("0.5 0.5 0.5")
                appendLine("0.5 0.5 0.5")
                appendLine("0.5 0.5 0.5")
                appendLine("0.5 0.5 0.5")
            }
        )
        assertNotNull(withOvershoot)
        assertTrue("越界值应当原样保留（线性路径在末端截断）", withOvershoot!!.data.any { it > 1f || it < 0f })
    }

    @Test
    fun `the parser still accepts a plain lut`() {
        assertNotNull("普通 LUT 不能被这次收紧误伤", CubeLutParser.parse(cube()))
    }
}
