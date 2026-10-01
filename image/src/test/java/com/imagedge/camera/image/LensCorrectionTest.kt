package com.imagedge.camera.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 镜头畸变与横向色差的**逆映射**。
 *
 * 为什么是「逆」而不是正向：镜头的正向模型（场景 → 照片）没有闭式反函数，而我们要的是
 * 「校正后画面上这个像素，该去畸变图的哪里取样」。所以实现的是牛顿迭代解
 * `Rd = Ru·(1 − k1 + k1·Ru²)`，收敛判据是**相对步长**而不是绝对残差——绝对残差在 float
 * 下对小 k1 永远达不到 1e-5（上游 lensfun 就栽在这里：真实镜头 k1 ≈ 0.001，改写成首一形式
 * 后系数放大到 926 量级，float 的舍入噪声就有 5.5e-5，于是每个像素都耗尽迭代预算、
 * 坐标原样返回）。
 *
 * 归一化约定照 lensfun：**畸变与色差用 min(半宽, 半高)**（同一套文档里暗角用的是半对角线，
 * 两个约定并存，是这套库里最容易搞错的地方——本文件不做暗角，所以只需要记住前者）。
 */
class LensCorrectionTest {

    private fun rdOf(ru: Float, k1: Float): Float = ru * (1f - k1 + k1 * ru * ru)

    @Test
    fun `no coefficient means no correction`() {
        for (rd in listOf(0.1f, 0.5f, 1f, 1.4f)) {
            assertEquals("k1 = 0 时必须是恒等", 1f, LensCorrection.undistortFactor(rd, 0f), 1e-6f)
        }
    }

    @Test
    fun `the centre is its own source`() {
        // rd = 0 时 Ru/Rd 是 0/0。这一格不是边角——它就是画面正中那个像素，
        // 每帧都有一次，除法写成 0/0 得到 NaN 的话，正中会出现一个永远修不好的点
        assertEquals(1f, LensCorrection.undistortFactor(0f, 0.2f), 0f)
    }

    @Test
    fun `correcting the forward model returns the original radius`() {
        // 这条是本文件里最硬的一条：它不依赖任何外部实现，只要求「正向多项式」与
        // 「我们的牛顿解」互为逆。参考实现算错了符号或错了一项，它就会红
        for (k1 in listOf(0.005f, 0.02f, -0.01f, 0.05f)) {
            for (ru in listOf(0.05f, 0.3f, 0.7f, 1f, 1.3f)) {
                val rd = rdOf(ru, k1)
                val back = rd * LensCorrection.undistortFactor(rd, k1)
                assertEquals("k1=$k1 ru=$ru 往返后是 $back", ru, back, 1e-4f)
            }
        }
    }

    @Test
    fun `a positive coefficient pulls the source outward and a negative one pulls it in`() {
        // 桶形（广角把直线掰成鼓）存下来的图边缘是被压紧的，校正要去更外面取；
        // 枕形反之。符号定错了不会崩、不会错得很明显——它只是把一种畸变修成另一种
        val rd = 0.8f
        val barrel = rd * LensCorrection.undistortFactor(rd, 0.03f)
        val pincushion = rd * LensCorrection.undistortFactor(rd, -0.03f)

        assertTrue("k1 > 0 应把取样点推到更外侧（实际 $barrel）", barrel > rd)
        assertTrue("k1 < 0 应把取样点收到更内侧（实际 $pincushion）", pincushion < rd)
    }

    @Test
    fun `the correction depends only on the radius`() {
        // 径向对称：偏左和偏上同样距离的像素，取样倍率必须一模一样。
        // 一旦某处不小心用了 x 和 y 的绝对值而不是各自的偏移，四角就会被拉成不同的形状
        val left = 0.6f * LensCorrection.undistortFactor(0.6f, 0.02f)
        val up = 0.6f * LensCorrection.undistortFactor(0.6f, 0.02f)

        assertEquals(left, up, 0f)
    }

    @Test
    fun `the iterated radius is never negative`() {
        // 大半径 + 大系数会让牛顿迭代冲出定义域。负半径不是位置——上游把这种坐标原样留下，
        // 我们返回 1.0（不校正）是同一个决定
        for (rd in listOf(0.5f, 1f, 1.41f, 2f, 5f)) {
            for (k1 in listOf(-0.05f, 0f, 0.1f, 0.4f)) {
                val factor = LensCorrection.undistortFactor(rd, k1)
                assertTrue("rd=$rd k1=$k1 给出 $factor", factor.isFinite() && factor >= 0f)
            }
        }
    }

    @Test
    fun `an extreme radius cannot produce a non finite factor`() {
        // NaN 会一路走到取样坐标，再写进位图就是一个永远修不好的点。
        // 宁可退回 1.0（不校正），也不要 NaN
        val factor = LensCorrection.undistortFactor(Float.MAX_VALUE, 0.3f)

        assertTrue("得到 $factor", factor.isFinite())
    }

    @Test
    fun `lateral aberration scales red and blue away from green`() {
        // 横向色差是**逐通道**的径向缩放，绿通道不动。色差在画面边缘最明显，
        // 中心几乎没有——所以缩放因子必须随半径增长而不是一个常数
        val atCentre = LensCorrection.tcaScale(0f, 0.004f, channel = 0)
        val atEdge = LensCorrection.tcaScale(1f, 0.004f, channel = 0)

        assertEquals("中心处不该有色差", 1f, atCentre, 1e-6f)
        assertTrue("边缘处必须有", abs(atEdge - 1f) > 0.001f)
    }

    @Test
    fun `green never moves and red and blue move oppositely`() {
        val r = 1f
        val tca = 0.004f

        assertEquals("绿通道是基准，不动", 1f, LensCorrection.tcaScale(r, tca, channel = 1), 0f)
        val red = LensCorrection.tcaScale(r, tca, channel = 0)
        val blue = LensCorrection.tcaScale(r, tca, channel = 2)
        assertTrue("红与蓝必须往相反方向走（red=$red blue=$blue）", (red - 1f) * (blue - 1f) < 0f)
    }

    @Test
    fun `no aberration means every channel is untouched`() {
        for (channel in 0..2) {
            for (r in listOf(0f, 0.5f, 1.2f)) {
                assertEquals(1f, LensCorrection.tcaScale(r, 0f, channel), 0f)
            }
        }
    }

    @Test
    fun `the hoisted per pixel offsets equal the per channel calls`() {
        // 6→1 那次提取是**纯重构**：它把 `undistortFactor`（含 ln 与 6 步牛顿迭代）
        // 从每像素 6 次降到 1 次。这条钉住它没顺手改掉任何结果——
        // 「提速顺便修了点东西」正是那种没人能审的改动
        val params = LensCorrectionParams(k1 = 0.02f, tca = 0.004f)
        val w = 4000
        val h = 3000
        val dx = FloatArray(3)
        val dy = FloatArray(3)
        val midX = (w - 1) / 2f
        val midY = (h - 1) / 2f

        for (y in 0 until h step 97) {
            for (x in 0 until w step 89) {
                LensCorrection.offsetsOf(x.toFloat(), y.toFloat(), w, h, params, dx, dy)
                for (ch in 0..2) {
                    assertEquals(
                        "($x,$y) 通道$ch 的 x 偏移",
                        LensCorrection.sourceX(x.toFloat(), y.toFloat(), w, h, params, ch),
                        midX + dx[ch],
                        1e-4f
                    )
                    assertEquals(
                        "($x,$y) 通道$ch 的 y 偏移",
                        LensCorrection.sourceY(x.toFloat(), y.toFloat(), w, h, params, ch),
                        midY + dy[ch],
                        1e-4f
                    )
                }
            }
        }
    }

    @Test
    fun `a tiny coefficient still converges exactly`() {
        // 这条是给「把方程通除 k1 化成首一形式」那个优化设的绊线。真实镜头的 k1 量级就是
        // 0.001（上游文档里点名那支 500mm 是 0.00108）：通除之后系数放大到 ~1000，
        // float 的舍入噪声本身就有 1e-5 量级，于是按**绝对残差**判收敛会永远不成立，
        // 每个像素耗尽 6 步预算后原样返回。相对步长判据就是为了这个。
        val k1 = 0.00108f
        val ru = 0.7f
        val rd = rdOf(ru, k1)

        val back = rd * LensCorrection.undistortFactor(rd, k1)

        assertEquals("小 k1 下往返应当仍然精确（实际 $back）", ru, back, 1e-5f)
    }

    @Test
    fun `the radius is normalised by the shorter side not the longer one`() {
        // lensfun 对畸变用 min(半宽, 半高)、对暗角用半对角线，两个约定并存。
        // 记错的表现不会崩：它只是让所有系数差一个固定的倍数，边缘还是歪的、怎么调都不对。
        // 4:3 画幅上，两条边中点各自的归一化半径因此不相等
        val w = 4000
        val h = 3000
        val leftEdge = LensCorrection.normalisedRadius(0f, (h - 1) / 2f, w, h)
        val topEdge = LensCorrection.normalisedRadius((w - 1) / 2f, 0f, w, h)

        // 断言**绝对值**而不是两者的比值：两条边除的是同一个 half，比值把约定消掉了，
        // 于是按半宽归一（1999.5/2000）与按半高归一（1999.5/1500）会给出同一个比值——
        // 那条断言怎么写都绿。记错约定的表现恰恰是「所有系数差一个固定倍数」。
        assertEquals("左边中点应按半高 1500 归一", 1999.5f / 1500f, leftEdge, 1e-4f)
        assertEquals("上边中点应按半高 1500 归一", 1499.5f / 1500f, topEdge, 1e-4f)
    }

    @Test
    fun `the slider scale round trips`() {
        // 两个方向的换算各写一遍就会慢慢分家：滑条读回的值不是用户刚拖到的那个，
        // 表现是「松手之后数字自己动了一下」
        for (step in 0..LensCorrectionParams.K1_STEPS) {
            val back = lensK1StepOf(lensK1Of(step))
            assertEquals("k1 滑条刻度 $step 往返成了 $back", step, back)
        }
        for (step in 0..LensCorrectionParams.TCA_STEPS) {
            assertEquals("色差滑条刻度 $step 往返", step, lensTcaStepOf(lensTcaOf(step)))
        }
    }

    @Test
    fun `the slider scale covers the range real lenses need`() {
        // 真实镜头的 k1 量级是 0.001 上下（上游点名那支 500mm 是 0.00108），
        // 而常见桶形能到 0.05。滑条要够细也要够宽
        // 范围是**上限决定的**，不是随手取的：条带缓冲的大小随位移线性涨，
        // 21 MP 上 k1 = 0.1 要 88 MB、k1 = 0.02 只要约 20 MB
        assertEquals("k1 = 0 应在滑条正中", LensCorrectionParams.K1_STEPS / 2, lensK1StepOf(0f))
        assertEquals("刻度 0 是 -0.02", -0.02f, lensK1Of(0), 1e-6f)
        assertEquals("刻度末位是 +0.02", 0.02f, lensK1Of(LensCorrectionParams.K1_STEPS), 1e-6f)
        assertEquals("色差刻度末位就是它的上限", LensCorrectionParams.TCA_LIMIT, lensTcaOf(LensCorrectionParams.TCA_STEPS), 1e-6f)
    }

    @Test
    fun `a pixel near the corner maps back inside the picture`() {
        // 最容易出问题的地方：角落。半径最大，系数影响最重，越界一点点就会
        // 取到画面外的像素——要么被钳成边缘（出现一圈拉伸的糊边），要么越界读出垃圾
        val w = 4000
        val h = 3000
        val p = LensCorrectionParams(k1 = 0.05f, tca = 0.004f)
        val cornerX = (w - 1).toFloat()
        val cornerY = (h - 1).toFloat()

        val sx = LensCorrection.sourceX(cornerX, cornerY, w, h, p, channel = 0)
        val sy = LensCorrection.sourceY(cornerX, cornerY, w, h, p, channel = 0)

        // 系数越大去得越远，但必须仍然落在画面内（允许一点点越界，取样端会钳）
        assertTrue("x 跑到了 $sx", sx > -w * 0.2f && sx < w * 1.2f)
        assertTrue("y 跑到了 $sy", sy > -h * 0.2f && sy < h * 1.2f)
    }

    @Test
    fun `the centre pixel of the picture is exactly its own source`() {
        val w = 4000
        val h = 3000
        val p = LensCorrectionParams(k1 = 0.05f, tca = 0.004f)
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f

        for (channel in 0..2) {
            assertEquals(cx, LensCorrection.sourceX(cx, cy, w, h, p, channel), 1e-3f)
            assertEquals(cy, LensCorrection.sourceY(cx, cy, w, h, p, channel), 1e-3f)
        }
    }
}
