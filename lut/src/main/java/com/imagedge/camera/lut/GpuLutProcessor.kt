package com.imagedge.camera.lut

import android.graphics.Bitmap
import android.graphics.Canvas
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import com.imagedge.camera.core.common.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * GPU LUT 处理器（OpenGL ES 3.0 + 3D 纹理）。
 *
 * ## 为什么是 GLES 3.0 而不是 Vulkan NDK
 *
 * 3D LUT 的本质是「对每个像素做一次三维查表 + 三线性插值」。GLES 3.0 的
 * `sampler3D` 硬件就带三线性过滤，一张 `GL_RGB16F` 的 3D 纹理就能把插值交给纹理单元，
 * 不需要着色器里手写 8 点插值，也不需要 NDK/Vulkan 的设备选择、内存与同步管理——
 * 后者在手机上出问题的面积远大于收益。minSdk 29 已保证 ES 3.0 可用。
 *
 * ## 快路径为什么不走 ByteArray
 *
 * [LutProcessor.apply] 的字节数组接口要求调用方把 Bitmap 拆成 IntArray 再转 RGBA 字节，
 * 处理完再拼回 Bitmap：1080p 图光这两趟 CPU 拷贝就要几十毫秒，足以吃掉 GPU 的收益。
 * 因此 GPU 走 [applyToBitmap] 直通路径（纹理上传 → 离屏渲染 → 读回位图），
 * 字节数组接口直接交给 CPU 实现（见 [apply]）。
 *
 * ## 一致性与回退
 *
 * - 调色数值来自 [AdjustUniforms]，与 [CpuLutProcessor] 同源，两条路径结果一致；
 * - LUT 采样用 `texCoord = c * (N-1)/N + 0.5/N`，端点对齐后硬件插值等价于标准三线性；
 * - 小图（低于 [minPixels]）不做 GPU 往返（省下的算力不够付上下文与纹理开销）；
 * - 任何一步失败（EGL/GLES 不可用、着色器编译失败、超大纹理）→ 记录原因并**永久回退 CPU**，
 *   由 [fallback] 保证功能不中断。
 */
class GpuLutProcessor(
    private val fallback: LutProcessor = CpuLutProcessor(),
    /** 低于该像素数不走 GPU：小图（如滤镜缩略图 128²）的 GPU 往返开销大于收益 */
    private val minPixels: Int = 200_000,
) : LutProcessor {

    /**
     * GL 上下文与纹理都必须固定在同一个线程上操作（EGL 上下文是线程绑定的）。
     * 同时保留底层 executors：销毁 GL 资源也必须回到这个线程（见 [disable]）。
     */
    private val glExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "imagedge-gpu-lut").apply { isDaemon = true }
    }
    private val glDispatcher = glExecutor.asCoroutineDispatcher()

    @Volatile
    private var disabled = false

    /** 仅 [glDispatcher] 线程访问 */
    private var engine: Engine? = null

    override val supportsBitmapPath: Boolean get() = !disabled

    /**
     * 字节数组接口一律走 CPU：GPU 的快路径需要位图（见类注释），
     * 在这里额外做一次 Bitmap 往返反而更慢。
     */
    override suspend fun apply(
        pixels: ByteArray,
        width: Int,
        height: Int,
        lutData: FloatArray,
        lutSize: Int,
        strength: Int,
        adjust: ColorAdjust,
    ): ByteArray = fallback.apply(pixels, width, height, lutData, lutSize, strength, adjust)

    override suspend fun applyToBitmap(
        source: Bitmap,
        lutData: FloatArray,
        lutSize: Int,
        strength: Int,
        adjust: ColorAdjust,
    ): Bitmap? {
        if (disabled) return null
        if (source.width * source.height < minPixels) return null // 交给调用方的 CPU 路径
        return try {
            withContext(glDispatcher) { engine().process(source, lutData, lutSize, strength, adjust) }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: SizeUnsupportedException) {
            // 只是这一张图超限（超出了设备的纹理上限）：不关掉 GPU，后续图片继续走 GPU
            AppLog.w(TAG, "${e.message}，本次回退 CPU")
            null
        } catch (t: Throwable) {
            disable("GPU 处理失败（${t::class.simpleName}: ${t.message}），回退 CPU", engine)
            null
        }
    }

    /** 惰性创建引擎；只在 [glDispatcher] 线程调用 */
    private fun engine(): Engine {
        engine?.let { return it }
        return try {
            Engine().also {
                engine = it
                AppLog.i(TAG, "GPU LUT 启用（GLES ${it.glesVersion}，3D 纹理上限 ${it.max3dSize}，纹理上限 ${it.maxTextureSize}）")
            }
        } catch (t: Throwable) {
            disable("GPU 初始化失败（${t::class.simpleName}: ${t.message}），回退 CPU", null)
            throw t
        }
    }

    /**
     * 永久回退 CPU。
     *
     * [toClose] 的销毁**必须回到 GL 线程**执行：EGL 上下文与纹理都是线程绑定的，
     * 从调用方线程直接调 GL 删除接口会因为没有 current context 而静默失败（资源泄漏）。
     * 因此这里只是把销毁任务排进同一个单线程 executor，不阻塞调用方。
     */
    private fun disable(reason: String, toClose: Engine?) {
        if (disabled) return
        disabled = true
        AppLog.w(TAG, reason)
        engine = null
        if (toClose != null) glExecutor.execute { runCatching { toClose.close() } }
    }

    /** 图片尺寸超出设备纹理上限：只影响本次，不永久禁用 GPU */
    private class SizeUnsupportedException(message: String) : Exception(message)

    // ────────────────────────── GL 引擎 ──────────────────────────

    /**
     * EGL 上下文 + 着色器 + 纹理/FBO 的持有者。
     *
     * 生命周期：随进程存活（单例处理器）。GL 资源总量很小（两张与输入等大的 RGBA 纹理
     * + 一张 3D LUT 纹理），且纹理按最大尺寸复用，不随每次调用增长。
     */
    private class Engine : AutoCloseable {

        private val display: EGLDisplay
        private val context: EGLContext
        private val surface: EGLSurface
        private val program: Int

        val maxTextureSize: Int
        val max3dSize: Int
        val glesVersion: String

        private var srcTexId = 0
        private var dstTexId = 0
        private var srcTexW = 0
        private var srcTexH = 0
        private var dstTexW = 0
        private var dstTexH = 0
        private var fboId = 0
        private var vaoId = 0
        private var vboId = 0
        private var lutTexId = 0
        private var lutTexSize = 0
        private var lutTexSource: FloatArray? = null

        private val uSrc: Int
        private val uLut: Int
        private val uGain: Int
        private val uContrast: Int
        private val uSaturation: Int
        private val uStrength: Int
        private val uLutScale: Int
        private val uLutOffset: Int
        private val uHasLut: Int

        init {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay 失败" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize 失败" }

            val configAttrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val configCount = IntArray(1)
            check(
                EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, 1, configCount, 0) &&
                    configCount[0] > 0 && configs[0] != null
            ) { "找不到支持 ES 3.0 的 EGL 配置" }
            val config = configs[0]!!

            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext 失败" }

            // 只做离屏渲染（FBO），1×1 的 pbuffer 就够了
            surface = EGL14.eglCreatePbufferSurface(
                display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            check(surface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface 失败" }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent 失败" }

            val limits = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, limits, 0)
            maxTextureSize = limits[0]
            GLES30.glGetIntegerv(GLES30.GL_MAX_3D_TEXTURE_SIZE, limits, 0)
            max3dSize = limits[0]
            glesVersion = GLES30.glGetString(GLES30.GL_VERSION).orEmpty()

            program = buildProgram()
            uSrc = glGetUniform(program, "uSrc")
            uLut = glGetUniform(program, "uLut")
            uGain = glGetUniform(program, "uGain")
            uContrast = glGetUniform(program, "uContrast")
            uSaturation = glGetUniform(program, "uSaturation")
            uStrength = glGetUniform(program, "uStrength")
            uLutScale = glGetUniform(program, "uLutScale")
            uLutOffset = glGetUniform(program, "uLutOffset")
            uHasLut = glGetUniform(program, "uHasLut")

            // 全屏四边形：顶点坐标 [-1,1]，纹理坐标在顶点着色器里换算
            val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            val buffer = ByteBuffer.allocateDirect(quad.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().put(quad).apply { position(0) }
            val ids = IntArray(1)
            GLES30.glGenVertexArrays(1, ids, 0)
            vaoId = ids[0]
            GLES30.glBindVertexArray(vaoId)
            GLES30.glGenBuffers(1, ids, 0)
            vboId = ids[0]
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, buffer, GLES30.GL_STATIC_DRAW)
            val aPos = GLES30.glGetAttribLocation(program, "aPos")
            check(aPos >= 0) { "顶点属性 aPos 未找到" }
            GLES30.glEnableVertexAttribArray(aPos)
            GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 0, 0)
            GLES30.glBindVertexArray(0)
            checkGl("初始化")
        }

        /**
         * 处理位图：按条带渲染，避免一次性为大图申请两张全尺寸纹理。
         *
         * @return 新位图；调用方负责回收
         */
        fun process(
            source: Bitmap,
            lutData: FloatArray,
            lutSize: Int,
            strength: Int,
            adjust: ColorAdjust,
        ): Bitmap {
            val w = source.width
            val h = source.height
            if (w !in 1..maxTextureSize || h !in 1..maxTextureSize) {
                throw SizeUnsupportedException("图片尺寸 ${w}x$h 超出 GPU 纹理上限 $maxTextureSize")
            }
            val hasLut = lutSize >= 2 && lutData.size >= lutSize * lutSize * lutSize * 3
            if (hasLut && lutSize > max3dSize) {
                throw SizeUnsupportedException("LUT 尺寸 $lutSize 超出 3D 纹理上限 $max3dSize")
            }

            // 源位图必须是 ARGB_8888（copyPixelsToBuffer 的要求；HARDWARE 位图先软拷贝）
            val input = if (source.config == Bitmap.Config.ARGB_8888) {
                source
            } else {
                source.copy(Bitmap.Config.ARGB_8888, false)
                    ?: throw IllegalStateException("位图格式 ${source.config} 无法转换为 ARGB_8888")
            }
            val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val u = AdjustUniforms.of(adjust)
            val strengthF = strength.coerceIn(0, 100) / 100f

            if (hasLut) uploadLut(lutData, lutSize)

            // 条带高度：单条带不超过约 400 万像素，且不超过纹理上限
            val stripRows = (4_000_000 / w).coerceIn(64, 2048).coerceAtMost(h)
            var y = 0
            while (y < h) {
                val rows = minOf(stripRows, h - y)
                val strip = if (rows == h) input else Bitmap.createBitmap(input, 0, y, w, rows)
                val rendered = renderStrip(
                    strip, w, rows,
                    if (hasLut) lutSize else 0, strengthF, u
                )
                canvas.drawBitmap(rendered, 0f, y.toFloat(), null)
                rendered.recycle()
                if (strip !== input) strip.recycle()
                y += rows
            }
            if (input !== source) input.recycle()
            return output
        }

        /** 单条带渲染：上传纹理 → 离屏绘制 → 读回位图 */
        private fun renderStrip(
            strip: Bitmap,
            w: Int,
            h: Int,
            lutSize: Int,
            strength: Float,
            u: AdjustUniforms,
        ): Bitmap {
            ensureSourceTexture(w, h)
            ensureTargetTexture(w, h)

            // 上传源像素（ARGB_8888 的内存排布在小端机上正好是 RGBA 字节序）
            val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            strip.copyPixelsToBuffer(pixels)
            pixels.position(0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTexId)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels
            )

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
            GLES30.glViewport(0, 0, w, h)
            GLES30.glUseProgram(program)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTexId)
            uniform1i(uSrc, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, if (lutSize >= 2) lutTexId else 0)
            uniform1i(uLut, 1)
            if (uGain >= 0) GLES30.glUniform3f(uGain, u.gainR, u.gainG, u.gainB)
            uniform1f(uContrast, u.contrast)
            uniform1f(uSaturation, u.saturation)
            uniform1f(uStrength, strength)
            uniform1f(uHasLut, if (lutSize >= 2) 1f else 0f)
            if (lutSize >= 2) {
                // 端点对齐：LUT 的第 0 个采样点对应输入 0，第 N-1 个对应输入 1
                uniform1f(uLutScale, (lutSize - 1).toFloat() / lutSize)
                uniform1f(uLutOffset, 0.5f / lutSize)
            }

            GLES30.glBindVertexArray(vaoId)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glBindVertexArray(0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            checkGl("渲染 ${w}x$h")

            val out = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, out)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            out.position(0)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(out)
            return bitmap
        }

        private fun ensureSourceTexture(w: Int, h: Int) {
            if (srcTexId == 0) {
                val ids = IntArray(1)
                GLES30.glGenTextures(1, ids, 0)
                srcTexId = ids[0]
            }
            if (srcTexW == w && srcTexH == h) return
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTexId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
            )
            srcTexW = w
            srcTexH = h
        }

        private fun ensureTargetTexture(w: Int, h: Int) {
            if (dstTexId == 0 || fboId == 0) {
                val ids = IntArray(1)
                GLES30.glGenTextures(1, ids, 0)
                dstTexId = ids[0]
                GLES30.glGenFramebuffers(1, ids, 0)
                fboId = ids[0]
            }
            if (dstTexW == w && dstTexH == h) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
                GLES30.glFramebufferTexture2D(
                    GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, dstTexId, 0
                )
                return
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dstTexId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
            )
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, dstTexId, 0
            )
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) { "FBO 不完整：0x${status.toString(16)}" }
            dstTexW = w
            dstTexH = h
        }

        /** 上传 3D LUT（半精度浮点：ES 3.0 保证半浮点纹理可线性过滤，32F 不保证） */
        private fun uploadLut(data: FloatArray, size: Int) {
            if (lutTexId == 0) {
                val ids = IntArray(1)
                GLES30.glGenTextures(1, ids, 0)
                lutTexId = ids[0]
            }
            if (lutTexSize == size && lutTexSource === data) return

            val texelCount = size * size * size
            val shorts = ShortArray(texelCount * 3)
            for (i in 0 until texelCount * 3) {
                shorts[i] = HalfFloat.fromFloat(data[i].coerceIn(0f, 1f))
            }
            val buffer = ByteBuffer.allocateDirect(shorts.size * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer().put(shorts).apply { position(0) }

            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 2)
            GLES30.glTexImage3D(
                GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F,
                size, size, size, 0,
                GLES30.GL_RGB, GLES30.GL_HALF_FLOAT, buffer
            )
            checkGl("上传 LUT($size³)")
            lutTexSize = size
            lutTexSource = data
        }

        private fun buildProgram(): Int {
            val vertex = compile(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fragment = compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            val program = GLES30.glCreateProgram()
            check(program != 0) { "glCreateProgram 失败" }
            GLES30.glAttachShader(program, vertex)
            GLES30.glAttachShader(program, fragment)
            GLES30.glLinkProgram(program)
            val linked = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0)
            check(linked[0] == GLES30.GL_TRUE) { "着色器链接失败：${GLES30.glGetProgramInfoLog(program)}" }
            GLES30.glDeleteShader(vertex)
            GLES30.glDeleteShader(fragment)
            return program
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            check(shader != 0) { "glCreateShader 失败（type=$type）" }
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
            check(compiled[0] == GLES30.GL_TRUE) { "着色器编译失败：${GLES30.glGetShaderInfoLog(shader)}" }
            return shader
        }

        private fun glGetUniform(program: Int, name: String): Int {
            // 允许 -1：部分驱动会优化掉「只在运行时分支里用到」的 uniform，
            // 这种情况跳过赋值即可，不能因此把 GPU 整条路径判死
            return GLES30.glGetUniformLocation(program, name)
        }

        /** 对可能被驱动优化掉的 uniform 做保护性赋值 */
        private fun uniform1f(location: Int, value: Float) {
            if (location >= 0) GLES30.glUniform1f(location, value)
        }

        private fun uniform1i(location: Int, value: Int) {
            if (location >= 0) GLES30.glUniform1i(location, value)
        }

        private fun checkGl(step: String) {
            val error = GLES30.glGetError()
            check(error == GLES30.GL_NO_ERROR) { "$step 时 GL 错误 0x${error.toString(16)}" }
        }

        override fun close() {
            // GL objects must be deleted while this engine's context is current. Unbinding first
            // makes glDelete* silently fail on several drivers and leaves cleanup to context loss.
            runCatching { EGL14.eglMakeCurrent(display, surface, surface, context) }
            runCatching {
                val textures = intArrayOf(srcTexId, dstTexId, lutTexId).filter { it != 0 }.toIntArray()
                if (textures.isNotEmpty()) GLES30.glDeleteTextures(textures.size, textures, 0)
                if (fboId != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
                if (vboId != 0) GLES30.glDeleteBuffers(1, intArrayOf(vboId), 0)
                if (vaoId != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vaoId), 0)
                if (program != 0) GLES30.glDeleteProgram(program)
            }
            runCatching { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
            runCatching { EGL14.eglDestroySurface(display, surface) }
            runCatching { EGL14.eglDestroyContext(display, context) }
            runCatching { EGL14.eglTerminate(display) }
        }

        companion object {
            /** EGL_OPENGL_ES3_BIT_KHR（EGL14 里没有为 ES3 提供常量） */
            private const val EGL_OPENGL_ES3_BIT = 0x40

            private const val VERTEX_SHADER = """
                #version 300 es
                in vec2 aPos;
                out vec2 vUv;
                void main() {
                    vUv = aPos * 0.5 + 0.5;
                    gl_Position = vec4(aPos, 0.0, 1.0);
                }
            """

            /**
             * 与 [CpuLutProcessor] 相同的处理顺序：逐通道增益 → 对比度 → 饱和度 → LUT → 强度混合。
             * 权重用 Rec.601（0.299/0.587/0.114），与 CPU 实现一致。
             */
            private const val FRAGMENT_SHADER = """
                #version 300 es
                precision highp float;
                precision highp sampler2D;
                precision highp sampler3D;
                in vec2 vUv;
                out vec4 fragColor;
                uniform sampler2D uSrc;
                uniform sampler3D uLut;
                uniform vec3 uGain;
                uniform float uContrast;
                uniform float uSaturation;
                uniform float uStrength;
                uniform float uLutScale;
                uniform float uLutOffset;
                uniform float uHasLut;
                void main() {
                    vec4 src = texture(uSrc, vUv);
                    vec3 c = src.rgb * uGain;
                    c = clamp((c - 0.5) * uContrast + 0.5, 0.0, 1.0);
                    float luma = dot(c, vec3(0.299, 0.587, 0.114));
                    c = clamp(mix(vec3(luma), c, uSaturation), 0.0, 1.0);
                    if (uHasLut > 0.5) {
                        vec3 lutRgb = texture(uLut, c * uLutScale + uLutOffset).rgb;
                        c = mix(c, lutRgb, uStrength);
                    }
                    fragColor = vec4(c, src.a);
                }
            """
        }
    }

    companion object {
        private const val TAG = "lut"
    }
}
