package com.imagedge.camera.data.transfer

import android.content.Context
import androidx.core.content.edit
import com.imagedge.camera.data.model.MediaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-24
 *     desc   : 传输策略与自动保存去重（T3）
 *     version: 1.0
 * </pre>
 */

/**
 * 传输范围：这一批到底传哪些。
 *
 * **它不是用户偏好，而是当前会话的事实**——由相册的浏览模式与相机连接方式决定，
 * 所以不存进 [TransferPolicy]，只在展示时从 [com.imagedge.camera.feature.photos.BrowseMode] 推导。
 * 存成偏好会出现「prefs 写着整卡、实际连着选片集」这种更糟的假信息。
 *
 * 此前界面上完全看不出来：相册有选片集/整卡两种浏览模式，下载页却不说明条目来自哪种范围，
 * 用户无法判断「少了几张」是被过滤还是没传。
 */
enum class TransferScope {
    /** 相机端「待传内容集」（选片集模式）。范围由**相机**决定，不是手机能看到的全部 */
    CAMERA_SELECTION,

    /** 整卡枚举（ContentsTransfer 模式）。切换模式会使既有对象句柄失效 */
    WHOLE_CARD;

    /** 这个范围是谁定的：相机 vs 手机。界面必须把这条差别讲清楚 */
    val decidedByCamera: Boolean get() = this == CAMERA_SELECTION

    companion object {
        /**
         * 由相册浏览模式推导范围。
         *
         * 放在这里而不是让界面各写一份 if：「整卡模式 = ContentsTransfer」这条映射
         * 是 PTP 功能模式的知识，写散了两处就会有一处忘。
         */
        fun forBrowseMode(fullCard: Boolean): TransferScope =
            if (fullCard) WHOLE_CARD else CAMERA_SELECTION
    }
}

/**
 * 尺寸 / 格式策略。
 *
 * [LOCAL_THUMBNAIL] **不减少任何传输字节**：它是在原对象下载完成之后于本机生成的预览，
 * 相机→手机这一趟走的仍是原文件。相机侧生成小图（索尼 2M 传输）需要尚未实测过的请求路径，
 * 在它被验证之前界面不能说「省流量」。
 */
enum class TransferSizeMode {
    /** 传相机端原对象（当前唯一经实测可用的方式） */
    ORIGINAL,

    /** 下载后在本机另存一份缩图；传输量与 ORIGINAL 完全相同 */
    LOCAL_THUMBNAIL;

    /** 该模式是否会减少相机→手机的传输量。目前没有任何一种会 */
    val reducesTransferredBytes: Boolean get() = false
}

/**
 * 续传方式。
 *
 * [PARTIAL_OBJECT] 在 `:ptp` 里已实现，但 ZV-E10 整卡模式下每个分块都回 `0x2009`
 * （见 DownloadManager 的说明），因此它**不是**一个可以全局打开的开关——
 * 只有当某机型被实测证明支持后才允许启用，且默认关闭。
 */
enum class ResumeMode {
    /** 失败即整对象重传 */
    WHOLE_OBJECT,

    /** GetPartialObject 分块续传：未验证机型上会被相机拒绝 */
    PARTIAL_OBJECT;

    /** 当前是否有经实测确认支持分块续传的机型。没有，所以界面不得默认启用 */
    val verifiedAvailable: Boolean get() = this == WHOLE_OBJECT
}

/** 一次传输的完整策略 */
data class TransferPolicy(
    val sizeMode: TransferSizeMode = TransferSizeMode.ORIGINAL,
    val resumeMode: ResumeMode = ResumeMode.WHOLE_OBJECT,
    /** 拍后自动保存：用户主动开启，默认关。自动路径必须走去重，见 [AutoSaveLedger] */
    val autoSaveAfterCapture: Boolean = false
) {
    companion object {
        const val KEY_SIZE = "transfer_size_mode"
        const val KEY_RESUME = "transfer_resume_mode"
        const val KEY_AUTO_SAVE = "transfer_auto_save"

        /**
         * 从存储还原。枚举名不认识时回落到默认值而不是抛——
         * 降级安装/手工改过 prefs 的情况下，宁可回到最保守的策略。
         */
        fun fromStored(
            sizeMode: String?,
            resumeMode: String?,
            autoSave: Boolean?
        ): TransferPolicy = TransferPolicy(
            sizeMode = sizeMode?.let { name -> TransferSizeMode.entries.firstOrNull { it.name == name } }
                ?: TransferSizeMode.ORIGINAL,
            resumeMode = resumeMode?.let { name -> ResumeMode.entries.firstOrNull { it.name == name } }
                ?: ResumeMode.WHOLE_OBJECT,
            autoSaveAfterCapture = autoSave ?: false
        )
    }
}

/** 传输策略读写（与其他应用设置同一个 `settings` 文件） */
@Singleton
class TransferPolicyStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _policy = MutableStateFlow(load())
    val policy: StateFlow<TransferPolicy> = _policy.asStateFlow()

    fun update(transform: (TransferPolicy) -> TransferPolicy) {
        val next = transform(_policy.value)
        if (next == _policy.value) return
        _policy.value = next
        prefs.edit {
            putString(TransferPolicy.KEY_SIZE, next.sizeMode.name)
            putString(TransferPolicy.KEY_RESUME, next.resumeMode.name)
            putBoolean(TransferPolicy.KEY_AUTO_SAVE, next.autoSaveAfterCapture)
        }
    }

    private fun load(): TransferPolicy = TransferPolicy.fromStored(
        sizeMode = prefs.getString(TransferPolicy.KEY_SIZE, null),
        resumeMode = prefs.getString(TransferPolicy.KEY_RESUME, null),
        autoSave = if (prefs.contains(TransferPolicy.KEY_AUTO_SAVE)) {
            prefs.getBoolean(TransferPolicy.KEY_AUTO_SAVE, false)
        } else {
            null
        }
    )
}

/**
 * 自动保存去重账本。
 *
 * 为什么需要它：下载队列的既有去重只覆盖**进行中**的任务，完成后再来一次同名事件会重新
 * 入队并再次写盘（`downloadToGallery` 是无条件 insert）。手动点「下载」重复一次是用户意图，
 * 但拍后自动拉回不是——CaptureComplete 与内容事件可能各来一次，重复落盘就是相册里多张同名。
 *
 * 所以规则拆成两条：手动入队不看账本，自动入队必须先过 [claim]。
 *
 * 持久化是必要的：不存的话每次重连都会把相机里已有的照片再自动拉一遍。
 */
@Singleton
class AutoSaveLedger @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Volatile
    private var saved: MutableSet<String> = LinkedHashSet(
        prefs.getStringSet(KEY_SAVED, emptySet()).orEmpty()
    )

    /**
     * 占用该条目：首次返回 true（可以自动保存），重复返回 false。
     *
     * 判定与登记在同一次调用里完成，因为「查一下再决定」之间一旦被别的事件插入就会双写。
     */
    @Synchronized
    fun claim(item: MediaItem): Boolean = claim(item.thumbKey)

    @Synchronized
    fun claim(thumbKey: String): Boolean = claimAll(listOf(thumbKey)).isNotEmpty()

    /**
     * 批量占用，返回其中**首次**出现、可以自动保存的条目。
     *
     * 单独提供批量版本是因为整批拉回时逐条 claim 会逐条落盘；一次写入即可。
     */
    @Synchronized
    fun claimAll(thumbKeys: List<String>): List<String> {
        val fresh = thumbKeys.filter { saved.add(it) }
        if (fresh.isNotEmpty()) persist()
        return fresh
    }

    /** 已经自动保存过没有（只问不占） */
    @Synchronized
    fun contains(thumbKey: String): Boolean = thumbKey in saved

    /** 用户手动删除记录后允许重新自动拉回 */
    @Synchronized
    fun forget(thumbKey: String) {
        if (saved.remove(thumbKey)) persist()
    }

    private fun persist() {
        // 有界：LinkedHashSet 保插入序，超出上限丢最旧的。相册增长无上限，
        // 账本也不该无限占 prefs；被丢掉的最旧条目若再次触发事件，最多多存一份，
        // 不会漏存——方向是安全的
        val entries = if (saved.size > MAX_ENTRIES) saved.drop(saved.size - MAX_ENTRIES) else saved.toList()
        saved = LinkedHashSet(entries)
        prefs.edit { putStringSet(KEY_SAVED, entries.toSet()) }
    }

    private companion object {
        const val KEY_SAVED = "auto_saved_thumb_keys"

        /** 约 2 万条 × 几十字节，够覆盖长期使用的相机；再多的历史条目已无去重价值 */
        const val MAX_ENTRIES = 20_000
    }
}
