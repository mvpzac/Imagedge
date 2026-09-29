package com.imagedge.camera.data.hdr

import android.os.Build
import com.imagedge.camera.share.ExportFormat

/**
 * 导不出 HDR 的理由。**每一条都要带一句用户读得懂的话**——
 * 界面上灰掉开关时显示的就是它，导出被拒时弹的也是它。
 */
enum class HdrUnavailable(val message: String) {
    /** `Gainmap` 是 API 34 才有的类，低版本没有这条路径 */
    SDK_TOO_LOW("这台设备低于 Android 14，无法导出 HDR"),

    /** v1 只做 JPEG。PNG / WebP 各有各的增益图封装，没验过就不开这个口 */
    FORMAT_UNSUPPORTED("HDR 目前只支持导出为 JPEG"),

    /** 源照片本身没有 HDR 数据可带 */
    NO_SOURCE_GAIN_MAP("这张照片本身不带 HDR 数据，无法还原"),
}

/**
 * 「这张照片能不能导出 HDR」——**唯一的一份判断**。
 *
 * 界面上开关亮不亮、导出时放不放行，都调它。两处各判一次的结果是
 * 「开关亮着、点下去被拒」，那一格控件等于坏的，而用户看不出坏在哪一步。
 *
 * 之所以是纯函数（把 `sdkInt` 当参数传进来而不直接读 [Build]）：
 * 这样它能在 JVM 上被测。读 `Build.VERSION` 的版本在单元测试里恒为 0，
 * 于是「低版本拒绝」那条永远走不到——一条永远走不到的分支等于没有。
 *
 * v1 的口径是**只透传、不伪造**：源照片自带增益图（`Bitmap.hasGainmap()`）才谈得上
 * HDR 导出。从一张普通 SDR 照片反推高光，造的是用户照片里本来没有的东西，
 * 那不是还原，是加滤镜，而且用户分辨不出来。
 */
object HdrExport {

    /**
     * @return `null` 表示可以；否则是**唯一一条**该报给用户的理由。
     *   多条同时成立时按「设备 → 格式 → 源数据」取第一条：最基础的那条先报，
     *   否则界面上灰掉的字会随条件跳变。
     */
    fun availability(
        sdkInt: Int,
        format: ExportFormat,
        sourceHasGainMap: Boolean,
    ): HdrUnavailable? = when {
        sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> HdrUnavailable.SDK_TOO_LOW
        format != ExportFormat.JPEG -> HdrUnavailable.FORMAT_UNSUPPORTED
        !sourceHasGainMap -> HdrUnavailable.NO_SOURCE_GAIN_MAP
        else -> null
    }
}
