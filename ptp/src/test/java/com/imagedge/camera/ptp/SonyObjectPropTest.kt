package com.imagedge.camera.ptp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/10/02
 *     desc   : 对象属性列表解析（0x9805）与文件元数据模型
 * </pre>
 */
class SonyObjectPropTest {

    private val typeUInt16 = SonyObjectPropDataType.UINT16
    private val typeUInt32 = SonyObjectPropDataType.UINT32
    private val typeInt32 = SonyObjectPropDataType.INT32
    private val typeString = SonyObjectPropDataType.STRING

    /**
     * 拼装一条 GetObjectPropList 负载：`UINT32 条目数` + 每条
     * `UINT32 句柄 / UINT16 属性码 / UINT16 类型 / 值`。
     */
    private fun list(vararg entries: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeLe(entries.size, 4)
        entries.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun entry(handle: Long, propCode: Int, dataType: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeLe(handle, 4)
        out.writeLe(propCode, 2)
        out.writeLe(dataType, 2)
        out.write(value)
        return out.toByteArray()
    }

    private fun num(value: Long, size: Int): ByteArray = ByteArray(size).also { writeLe(it, value, size) }

    /**
     * 对象属性里的字符串：长度前缀是 **UINT16**（含结尾 null），与 0x9209 描述符里
     * 那个 UINT8 前缀的 PTP 字符串不是同一种编码，别混用。
     */
    private fun ptpString(value: String): ByteArray {
        val chars = value.toByteArray(Charsets.UTF_16LE)
        val out = ByteArrayOutputStream()
        out.writeLe((chars.size / 2) + 1, 2)     // 字符数含结尾 null
        out.write(chars)
        out.write(0); out.write(0)
        return out.toByteArray()
    }

    private fun writeLe(target: ByteArray, value: Long, size: Int) {
        for (i in 0 until size) target[i] = (((value shr (8 * i)) and 0xFF).toInt()).toByte()
    }

    private fun ByteArrayOutputStream.writeLe(value: Long, size: Int) {
        for (i in 0 until size) write(((value shr (8 * i)) and 0xFF).toInt())
    }

    private fun ByteArrayOutputStream.writeLe(value: Int, size: Int) = writeLe(value.toLong(), size)

    @Test
    fun `parses grouped entries across handles`() {
        val data = list(
            entry(11, SonyObjectPropCode.WIDTH, typeUInt32, num(6000, 4)),
            entry(11, SonyObjectPropCode.HEIGHT, typeUInt32, num(4000, 4)),
            entry(12, SonyObjectPropCode.WIDTH, typeUInt32, num(1920, 4))
        )

        val props = ObjectPropMap.parse(data)

        assertEquals(6000, props.intValue(11, SonyObjectPropCode.WIDTH))
        assertEquals(4000, props.intValue(11, SonyObjectPropCode.HEIGHT))
        assertEquals(1920, props.intValue(12, SonyObjectPropCode.WIDTH))
        // 没问过的组合必须返回 null 而不是 0
        assertNull(props.intValue(12, SonyObjectPropCode.HEIGHT))
    }

    @Test
    fun `int32 values sign-extend rather than wrapping to a large positive`() {
        val data = list(entry(11, SonyObjectPropCode.PRIMARY_IMAGE_COUNT, typeInt32, num(0xFFFFFFFFL, 4)))

        val props = ObjectPropMap.parse(data)

        assertEquals(-1L, props.longValue(11, SonyObjectPropCode.PRIMARY_IMAGE_COUNT))
    }

    @Test
    fun `string values decode and drop the terminating null`() {
        val data = list(entry(11, SonyObjectPropCode.OBJECT_FILE_NAME, typeString, ptpString("DSC00001.ARW")))

        val props = ObjectPropMap.parse(data)

        assertEquals("DSC00001.ARW", props.stringValue(11, SonyObjectPropCode.OBJECT_FILE_NAME))
    }

    @Test
    fun `empty payload yields an empty map instead of throwing`() {
        val props = ObjectPropMap.parse(ByteArray(0))
        assertTrue(props.handles.isEmpty())
    }

    @Test
    fun `a zero count is honoured rather than read as one entry`() {
        // 计数为 0 却跟着一条数据：宽容解析会把那条读成属性，制造出相机没报过的属性
        val data = list() + entry(11, SonyObjectPropCode.WIDTH, typeUInt32, num(6000, 4))

        val props = ObjectPropMap.parse(data)

        assertTrue(props.handles.isEmpty())
    }

    @Test
    fun `hostile entry count is rejected before allocating`() {
        // 0xFFFFFFFF 条目数：若据此分配会直接 OOM
        val out = ByteArrayOutputStream()
        out.writeLe(0xFFFFFFFFL, 4)

        try {
            ObjectPropMap.parse(out.toByteArray())
            throw AssertionError("超量条目数应当被判为流错位")
        } catch (e: PtpMalformedPacketException) {
            assertTrue(e.message!!.contains("条目数"))
        }
    }

    @Test
    fun `truncated entry throws instead of returning a partial map`() {
        val full = list(entry(11, SonyObjectPropCode.WIDTH, typeUInt32, num(6000, 4)))
        val truncated = full.copyOf(full.size - 2)

        try {
            ObjectPropMap.parse(truncated)
            throw AssertionError("被截断的条目应当抛错，而不是安静地少报一项")
        } catch (e: PtpMalformedPacketException) {
            // 期望的失败：调用方据此重连，而不是拿半个属性继续跑
        }
    }

    @Test
    fun `media metadata flags composite shots and high bit depth video`() {
        val props = ObjectPropMap.parse(
            list(
                entry(11, SonyObjectPropCode.PRIMARY_IMAGE_COUNT, typeUInt32, num(4, 4)),
                entry(11, SonyObjectPropCode.MPTYPE_CODE, typeUInt32, num(1, 4))
            )
        ).propertiesOf(11)

        val meta = MediaMetadata.from(props)

        assertTrue(meta.isComposite)
        assertEquals(4, meta.primaryImageCount)
        assertFalse(meta.isHighBitDepthVideo)
    }

    @Test
    fun `a single frame shot is not composite`() {
        val props = ObjectPropMap.parse(
            list(entry(11, SonyObjectPropCode.PRIMARY_IMAGE_COUNT, typeUInt32, num(1, 4)))
        ).propertiesOf(11)

        val meta = MediaMetadata.from(props)

        assertFalse(meta.isComposite)
    }

    @Test
    fun `absent metadata reads as unknown rather than zero`() {
        val meta = MediaMetadata.from(emptyMap())

        assertNull(meta.width)
        assertNull(meta.height)
        assertFalse(meta.isComposite)      // 缺数据不等于「单帧」
        assertFalse(meta.isProxy)
        assertNull(meta.primaryImageCount)
    }

    @Test
    fun `proxy flag decodes from zero and nonzero`() {
        val proxy = MediaMetadata.from(
            ObjectPropMap.parse(list(entry(11, SonyObjectPropCode.IS_MOVIE_PROXY, typeUInt16, num(1, 2))))
                .propertiesOf(11)
        )
        val real = MediaMetadata.from(
            ObjectPropMap.parse(list(entry(11, SonyObjectPropCode.IS_MOVIE_PROXY, typeUInt16, num(0, 2))))
                .propertiesOf(11)
        )

        assertTrue(proxy.isProxy)
        assertFalse(real.isProxy)
    }
}