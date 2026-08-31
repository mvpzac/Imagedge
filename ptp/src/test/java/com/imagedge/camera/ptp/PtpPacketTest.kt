package com.imagedge.camera.ptp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : PTP/IP 数据包与数据类型编解码单元测试
 *     version: 1.0
 * </pre>
 */
class PtpPacketTest {

    @Test
    fun operationRequestRoundtrip() {
        val original = OperationRequest(
            dataPhaseInfo = DataPhaseInfo.NO_DATA,
            operationCode = PtpOperationCode.GET_OBJECT,
            transactionId = 3,
            parameters = longArrayOf(0x1234L)
        )
        val parsed = PtpIpPacket.read(ByteArrayInputStream(original.serialize())) as OperationRequest

        assertEquals(DataPhaseInfo.NO_DATA, parsed.dataPhaseInfo)
        assertEquals(PtpOperationCode.GET_OBJECT, parsed.operationCode)
        assertEquals(3L, parsed.transactionId)
        assertEquals(1, parsed.parameters.size)
        assertEquals(0x1234L, parsed.parameters[0])
    }

    @Test
    fun operationResponseRoundtrip() {
        val original = OperationResponse(
            responseCode = PtpResponseCode.OK,
            transactionId = 1,
            parameters = longArrayOf(1L)
        )
        val parsed = PtpIpPacket.read(ByteArrayInputStream(original.serialize())) as OperationResponse

        assertEquals(PtpResponseCode.OK, parsed.responseCode)
        assertEquals(1L, parsed.transactionId)
    }

    @Test
    fun initCommandRequestRoundtrip() {
        val guid = ByteArray(16) { it.toByte() }
        val original = InitCommandRequest(guid, "Imagedge", 1, 0)
        val parsed = PtpIpPacket.read(ByteArrayInputStream(original.serialize())) as InitCommandRequest

        assertEquals("Imagedge", parsed.friendlyName)
        assertEquals(1, parsed.versionMajor)
        assertEquals(0, parsed.versionMinor)
    }

    @Test
    fun startDataRoundtrip() {
        val original = StartData(transactionId = 5, dataLength = 123456L)
        val parsed = PtpIpPacket.read(ByteArrayInputStream(original.serialize())) as StartData

        assertEquals(5L, parsed.transactionId)
        assertEquals(123456L, parsed.dataLength)
    }

    @Test
    fun objectInfoParse() {
        // 构造 ObjectInfo 数据：storageId(u32) + format(u16) + protection(u16) + size(u32) +
        // thumbFormat(u16) + thumbSize(u32) + thumbW(u32) + thumbH(u32) +
        // imgW(u32) + imgH(u32) + bitDepth(u32) + parent(u32) + assocType(u16) + assocDesc(u32) +
        // seq(u32) + filename(str) + captureDate(str) + modDate(str) + keywords(str)
        val buffer = PtpBuffer.writer()
            .writeUInt32(0x10001)          // storageId
            .writeUInt16(ObjectFormat.JPEG) // format
            .writeUInt16(0)                 // protection
            .writeUInt32(8_000_000)         // compressedSize
            .writeUInt16(ObjectFormat.JPEG) // thumbFormat
            .writeUInt32(40_000)            // thumbSize
            .writeUInt32(160)               // thumbW
            .writeUInt32(120)               // thumbH
            .writeUInt32(6000)              // imgW
            .writeUInt32(4000)              // imgH
            .writeUInt32(24)                // bitDepth
            .writeUInt32(0)                 // parent
            .writeUInt16(0)                 // assocType
            .writeUInt32(0)                 // assocDesc
            .writeUInt32(1)                 // seq
            .writePtpString("DSC00001.JPG")
            .writePtpString("20260827T120000")
            .writePtpString("20260827T120000")
            .writePtpString("")

        val info = ObjectInfo.parse(PtpBuffer.reader(buffer.toByteArray()))

        assertEquals(ObjectFormat.JPEG, info.formatCode)
        assertEquals(8_000_000L, info.compressedSize)
        assertEquals(6000, info.imageWidth)
        assertEquals(4000, info.imageHeight)
        assertEquals("DSC00001.JPG", info.filename)
        assertEquals(PhotoType.JPEG, info.photoType)
        assertTrue(info.captureDate != null)
    }

    @Test
    fun classifyFormatByCode() {
        assertEquals(PhotoType.RAW, classifyFormat(ObjectFormat.RAW_SONY, "DSC00001.ARW"))
        assertEquals(PhotoType.JPEG, classifyFormat(ObjectFormat.JPEG, "DSC00001.JPG"))
        assertEquals(PhotoType.VIDEO, classifyFormat(ObjectFormat.MP4, "C0001.MP4"))
        assertEquals(PhotoType.OTHER, classifyFormat(ObjectFormat.ASSOCIATION, "DCIM"))
    }

    @Test
    fun classifyFormatByExtension() {
        assertEquals(PhotoType.RAW, classifyFormat(0, "foo.arw"))
        assertEquals(PhotoType.VIDEO, classifyFormat(0, "foo.mov"))
        assertEquals(PhotoType.JPEG, classifyFormat(0, "foo.jpg"))
    }
}
