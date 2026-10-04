package com.imagedge.camera.feature.edit.triptych

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-10-04
 *     desc   : 顶/中/底那一排 chip 的显隐判据——它必须跟着真正被裁的那个源走
 * </pre>
 */
class AlignmentMattersTest {

    private val target169 = 16f / 9f

    /**
     * 评审点名的那一格：视频 16:9 + 静态图 4:3，目标选 16:9。
     *
     * 拼图那一格裁的是**静态图**（`cropToAspect`，未重选封面时），4:3 比 16:9 更高
     * → 走「裁上下」那一支 → 对齐是活的。而上一版的门用 `videoWidth / videoHeight` 判，
     * 视频与目标**精确等比**（下面第一行断言就是把这个前提钉住：两者是同一个 float），
     * 于是 `|srcRatio − target| < 0.01` 成立、chip 被收起，用户选过的「顶」还在生效却没了入口。
     */
    @Test
    fun `视频等比而静态图更高时对齐仍然生效`() {
        assertEquals(0f, kotlin.math.abs(1920f / 1080f - target169), 0f)
        assertTrue(kotlin.math.abs(4032f / 3024f - target169) > CROP_ASPECT_EPS)

        assertTrue(
            alignmentMatters(
                imageWidth = 4032, imageHeight = 3024,
                videoWidth = 1920, videoHeight = 1080,
                targetRatio = target169,
            )
        )
    }

    /**
     * 反过来那一半也要钉住：**只按静态图判会漏掉导出的那一路**。
     *
     * `cropFractions`（转码时的裁切分数）吃的是视频尺寸、与封面选没选过无关，
     * 视频 4:3 配 16:9 目标时它按对齐裁上下。此刻静态图是 2:1（比目标更宽、
     * `cropToAspect` 裁左右、对齐不动任何像素）——
     * 一个「把门换成 imageWidth/imageHeight」的修法正好会在这里收起一枚还在生效的 chip。
     */
    @Test
    fun `静态图更宽而视频更高时对齐靠视频那一路生效`() {
        assertFalse(2048f / 1024f < target169 - CROP_ASPECT_EPS)

        assertTrue(
            alignmentMatters(
                imageWidth = 2048, imageHeight = 1024,
                videoWidth = 1440, videoHeight = 1080,
                targetRatio = target169,
            )
        )
    }

    /**
     * 两路都等比 → 对齐不动任何一个像素 → chip 该收起（规格 §4「隐藏无效控件」）。
     * 这一条同时说明新判据**没有把 chip 变成常驻**：默认的 16:9 素材配 16:9 目标仍然看不见。
     */
    @Test
    fun `两路都与目标等比时对齐不显示`() {
        assertFalse(
            alignmentMatters(
                imageWidth = 1920, imageHeight = 1080,
                videoWidth = 1920, videoHeight = 1080,
                targetRatio = target169,
            )
        )
    }

    /**
     * 两路都**比目标更宽** → 两处都裁左右、`y` 恒为 0 → 对齐不显示。
     *
     * 这一格上一版是**多显示**：它的门是「比例差多少都显示」（`abs(diff) < eps` 之外一律在），
     * 2:1 的源配 16:9 目标差 0.2222，chip 在、但按下去一帧都不变。
     * 新判据按「哪一路真的会裁上下」收起它。
     */
    @Test
    fun `两路都比目标更宽时对齐不显示`() {
        assertTrue(2048f / 1024f - target169 > CROP_ASPECT_EPS)
        assertFalse(
            alignmentMatters(
                imageWidth = 2048, imageHeight = 1024,
                videoWidth = 2048, videoHeight = 1024,
                targetRatio = target169,
            )
        )
    }

    /**
     * 容差与 `cropToAspect` 的等比早退**同一条**：静态图 1912×1080 与 16:9 差 0.00741，
     * 落在那道 0.01 之内 → `cropToAspect` 原样返回、对齐一个像素都不动 → 这里也必须说 false。
     *
     * 前提不是散文，是下面那行断言：差值确实小于 [CROP_ASPECT_EPS]。
     */
    @Test
    fun `落在等比容差内的静态图按不裁计`() {
        val imageRatio = 1912f / 1080f
        assertTrue(kotlin.math.abs(imageRatio - target169) < CROP_ASPECT_EPS)
        assertFalse(
            alignmentMatters(
                imageWidth = 1912, imageHeight = 1080,
                videoWidth = 1920, videoHeight = 1080,
                targetRatio = target169,
            )
        )
    }

    /**
     * 静态图尺寸读不出来（`readImageBounds` 回 `0 to 0`）时那一路**不参与**判定：
     * 那种素材连画面都解不出来、拼图里这一格被跳过，此刻对齐只剩视频那一路。
     * 拿 0/0 = 0 去比目标会得到「比任何目标都高 → 永远显示 chip」的错误结论。
     */
    @Test
    fun `静态图尺寸为零时只按视频那一路回答`() {
        assertFalse(
            alignmentMatters(
                imageWidth = 0, imageHeight = 0,
                videoWidth = 1920, videoHeight = 1080,
                targetRatio = target169,
            )
        )
        assertTrue(
            alignmentMatters(
                imageWidth = 0, imageHeight = 0,
                videoWidth = 1440, videoHeight = 1080,
                targetRatio = target169,
            )
        )
    }

    /**
     * 四档目标下同一份 4:3 素材（源比例 1.3333，静态图与视频同尺寸）的结论**各不相同**：
     * 16:9（1.7778）与 27:16（1.6875）都比源宽 → 源更高 → 裁上下 → 对齐生效；
     * 1:1（1.0）与 4:5（0.8）都比源窄 → 源更宽 → 裁左右 → 对齐不动任何像素。
     * 判据跟着 `aspect.ratio` 与源比例的**大小关系**走，不是跟着档位名字走。
     */
    @Test
    fun `四档目标下同一张四比三静态图的结论各不相同`() {
        for (aspect in Aspect.entries) {
            val expected = when (aspect) {
                // 1.3333 < 1.7778 − 0.01 → 静态图被纵裁
                Aspect.R16_9 -> true
                // 1.0 与 0.8 都窄于源比例 1.3333 → 源更宽 → 只裁左右
                Aspect.R1_1, Aspect.R4_5 -> false
                // 27/16 = 1.6875 仍宽于 1.3333 → 还是纵裁
                Aspect.R27_16 -> true
            }
            assertEquals(
                aspect.name,
                expected,
                alignmentMatters(
                    imageWidth = 4032, imageHeight = 3024,
                    videoWidth = 4032, videoHeight = 3024,
                    targetRatio = aspect.ratio,
                ),
            )
        }
    }
}
