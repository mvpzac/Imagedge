package com.imagedge.camera.ptp

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/10/02
 *     desc   : 对象属性列表的解析与文件元数据模型
 *     version: 1.0
 * </pre>
 */

/** 一个对象属性的取值。相机回报的实际类型决定它落在哪个字段上。 */
sealed interface ObjectPropValue {
    data class Num(val value: Long) : ObjectPropValue
    data class Text(val value: String) : ObjectPropValue

    /** 原始字节。类型既不是整数也不是已知字符串形态时保留原样，不臆测含义。 */
    data class Raw(val bytes: ByteArray) : ObjectPropValue {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Raw && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }
}

/** 对象属性列表里的一项：某个文件的某个属性。 */
data class ObjectPropEntry(
    val objectHandle: Long,
    val propCode: Int,
    val value: ObjectPropValue
) {
    /** 取整数值；类型不符返回 null（而不是 0——0 是合法值，会掩盖问题） */
    fun asLongOrNull(): Long? = (value as? ObjectPropValue.Num)?.value

    fun asIntOrNull(): Int? = asLongOrNull()?.let {
        if (it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) it.toInt() else null
    }

    fun asBooleanOrNull(): Boolean? = asLongOrNull()?.let { it != 0L }
}

/**
 * 一次对象属性查询的结果，按文件句柄分组。
 */
class ObjectPropMap private constructor(
    private val byHandle: Map<Long, Map<Int, ObjectPropEntry>>
) {
    fun propertiesOf(objectHandle: Long): Map<Int, ObjectPropEntry> =
        byHandle[objectHandle].orEmpty()

    fun longValue(objectHandle: Long, propCode: Int): Long? =
        propertiesOf(objectHandle)[propCode]?.asLongOrNull()

    fun intValue(objectHandle: Long, propCode: Int): Int? =
        propertiesOf(objectHandle)[propCode]?.asIntOrNull()

    fun stringValue(objectHandle: Long, propCode: Int): String? =
        (propertiesOf(objectHandle)[propCode]?.value as? ObjectPropValue.Text)?.value

    val handles: Set<Long> get() = byHandle.keys

    companion object {
        /**
         * 解析 GetObjectPropList（0x9805）的数据阶段负载。
         *
         * 布局：`UINT32 条目数`，随后每条为
         * `UINT32 句柄 / UINT16 属性码 / UINT16 数据类型 / 值`。
         *
         * 条目数是相机给的计数，而每条的长度又取决于紧随其后的数据类型——
         * 一旦流错位，计数会远超真实条目数。因此每条都受剩余字节约束，
         * 越界即判为流错位并抛出，不做「读到没数据为止」的宽容解析。
         */
        fun parse(data: ByteArray): ObjectPropMap {
            // 空负载：相机回了 OK 但没有数据阶段（查询项全部不被支持）。这是正常的
            // 「什么都没问出来」，不是流错位——按缺项返回，让调用方逐项判空。
            if (data.isEmpty()) return ObjectPropMap(emptyMap())

            val buffer = PtpBuffer.reader(data)
            val count = buffer.readUInt32()
            if (count < 0 || count > MAX_ENTRY_COUNT) {
                throw PtpMalformedPacketException("对象属性条目数非法：$count（上限 $MAX_ENTRY_COUNT）")
            }

            val entries = HashMap<Long, MutableMap<Int, ObjectPropEntry>>(minOf(count.toInt(), 256))
            repeat(count.toInt()) {
                val handle = buffer.readUInt32()
                val propCode = buffer.readUInt16()
                val dataType = buffer.readUInt16()
                val value = readValue(buffer, dataType)
                entries.getOrPut(handle) { HashMap() }[propCode] = ObjectPropEntry(handle, propCode, value)
            }
            return ObjectPropMap(entries)
        }

        /** 单张卡的对象属性条目数上限。相机真实值远达不到，用于挡住流错位后的巨额分配。 */
        private const val MAX_ENTRY_COUNT = 200_000

        private fun readValue(buffer: PtpBuffer, dataType: Int): ObjectPropValue =
            when (dataType) {
                SonyObjectPropDataType.UINT8 -> ObjectPropValue.Num(buffer.readUInt8().toLong())
                SonyObjectPropDataType.INT8 -> ObjectPropValue.Num(buffer.readUInt8().toByte().toLong())
                SonyObjectPropDataType.UINT16 -> ObjectPropValue.Num(buffer.readUInt16().toLong())
                SonyObjectPropDataType.INT16 -> ObjectPropValue.Num(buffer.readUInt16().toShort().toLong())
                SonyObjectPropDataType.UINT32 -> ObjectPropValue.Num(buffer.readUInt32())
                SonyObjectPropDataType.INT32 -> ObjectPropValue.Num(buffer.readUInt32().toInt().toLong())
                SonyObjectPropDataType.UINT64, SonyObjectPropDataType.INT64 ->
                    ObjectPropValue.Num(buffer.readUInt64())
                SonyObjectPropDataType.STRING -> readString(buffer)
                // 数组/结构等复合类型：类型码本身不告诉长度，留原始字节而不是猜边界
                else -> ObjectPropValue.Raw(buffer.readRemaining())
            }

        /** PTP 字符串：`UINT16 字符数（含结尾 null）` + 字符内容。 */
        private fun readString(buffer: PtpBuffer): ObjectPropValue.Text {
            val declaredChars = buffer.readUInt16()
            if (declaredChars == 0) return ObjectPropValue.Text("")
            val bytes = buffer.readBytes(declaredChars * 2)
            return ObjectPropValue.Text(
                String(bytes, 0, declaredChars * 2 - 2, Charsets.UTF_16LE)
            )
        }
    }
}

/**
 * 从对象属性读出的文件元数据。
 *
 * 这是「不下载文件就知道它是什么」的落点：传输列表要在拿到字节之前
 * 标出 RAW、代理、以及多帧合成片，都靠这里。
 */
data class MediaMetadata(
    val objectHandle: Long,
    val width: Int?,
    val height: Int?,
    val sizeBytes: Long?,
    val isProxy: Boolean,
    /** 相机端多帧合成的帧数；1 或 null 表示单帧拍摄 */
    val primaryImageCount: Int?,
    val multiPictureType: Long?,
    val videoBitDepth: Int?,
    val colorFormat: Long?
) {
    /** 是否为相机端多帧合成的产物（像素位移、多帧降噪等） */
    val isComposite: Boolean get() = (primaryImageCount ?: 1) > 1 || (multiPictureType ?: 0L) != 0L

    /** 是否为 10bit 及以上的视频素材 */
    val isHighBitDepthVideo: Boolean get() = (videoBitDepth ?: 0) >= 10

    companion object {
        fun from(props: Map<Int, ObjectPropEntry>): MediaMetadata {
            fun num(code: Int): Long? = props[code]?.asLongOrNull()
            fun int(code: Int): Int? = props[code]?.asIntOrNull()
            return MediaMetadata(
                objectHandle = props.values.firstOrNull()?.objectHandle ?: 0L,
                width = int(SonyObjectPropCode.WIDTH),
                height = int(SonyObjectPropCode.HEIGHT),
                sizeBytes = num(SonyObjectPropCode.OBJECT_SIZE),
                isProxy = props[SonyObjectPropCode.IS_MOVIE_PROXY]?.asBooleanOrNull() ?: false,
                primaryImageCount = int(SonyObjectPropCode.PRIMARY_IMAGE_COUNT),
                multiPictureType = num(SonyObjectPropCode.MPTYPE_CODE),
                videoBitDepth = int(SonyObjectPropCode.VIDEO_BIT_DEPTH),
                colorFormat = num(SonyObjectPropCode.COLOR_FORMAT)
            )
        }
    }
}