package com.imagedge.camera.domain.camera

/**
 * 为什么打开向导（设计 §2「用户选择目标后保存 ConnectPurpose 与来源」）。
 *
 * 成功页的按钮因此是「查看照片」或「进入遥控」，而不是模糊的「完成」——
 * 用户点进来要做的事，出去时应该正好落在那件事上。
 * [Browse] 是用户从相机工作台主动点「连接相机」：没有单一目标，成功页列出可用目标让他选。
 */
enum class ConnectPurpose { Photos, Remote, Browse }
