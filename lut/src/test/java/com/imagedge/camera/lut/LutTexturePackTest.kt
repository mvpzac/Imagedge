package com.imagedge.camera.lut

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-27
 *     desc   : GPU 侧 3D LUT 上传前的打包：CPU 与 GPU 必须对同一份 LUT 得出同一张图
 * </pre>
 */
class LutTexturePackTest {

    /** 断言「这个分量原样进了纹理」，而不是「它被编码成了某个值」——被测的是钳制策略，不是编码 */
    private fun assertPackedUnchanged(packed: ShortArray, index: Int, expected: Float) {
        assertEquals(
            "第 $index 个分量应原样上传（expected=$expected）",
            HalfFloat.fromFloat(expected).toInt() and 0xFFFF,
            packed[index].toInt() and 0xFFFF
        )
    }

    @Test
    fun `lut values above one reach the shader instead of being flattened`() {
        // 胶片模拟类 .cube 常规用 >1 的值表达高光滚降，<0 表达暗部密度。
        // 上传前钳成 0..1 会把这些滚降整段抹平：同一张图，有 GPU 和没 GPU 两副面孔。
        val packed = packLutForTexture(floatArrayOf(0f, 0.5f, 1.4f), 1)

        assertPackedUnchanged(packed, 2, 1.4f)
    }

    @Test
    fun `lut values below zero survive too`() {
        val packed = packLutForTexture(floatArrayOf(-0.35f, 0f, 1f), 1)

        assertPackedUnchanged(packed, 0, -0.35f)
    }

    @Test
    fun `packing preserves the full cube and its order`() {
        // 顺序错一格不会崩，只会静默出一张色相完全不同的图，所以必须逐位比对
        val size = 2
        val expected = FloatArray(size * size * size * 3) { it / 24f }

        val packed = packLutForTexture(expected, size)

        assertEquals(expected.size, packed.size)
        expected.forEachIndexed { i, want -> assertPackedUnchanged(packed, i, want) }
    }

    @Test
    fun `packing does not change in range values`() {
        // 反证：上面的越界用例不能靠「打包根本没做任何事」蒙混过关
        val data = floatArrayOf(0f, 0.25f, 1f)

        val packed = packLutForTexture(data, 1)

        data.forEachIndexed { i, want -> assertPackedUnchanged(packed, i, want) }
    }
}
