package com.gasstation.guard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.LiveData
import com.gasstation.guard.databinding.ActivityMainBinding
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * ============================================================
 *  M1：相机预览 + 夜视优化
 * ============================================================
 *
 *  本阶段只做一件事：
 *    把摄像头画面稳定地、全屏地、尽可能地清晰地显示在屏幕上，
 *    并证明它能长时间运行不卡死。
 *
 *  本阶段【不做】：
 *    - 不做车辆识别（→ M2）
 *    - 不报警        （→ M3）
 *    - 不后台常驻    （→ M4）
 *
 *  ⚠️ 安全提示：在 M3 完成之前，本应用不能用于实际夜班值守，它不会叫醒任何人。
 *
 *  设计约束（来自项目章程）：
 *    1. 相机任何异常都必须「可见」（屏幕上有红字）并自动重试，绝不允许静默失效。
 *    2. 分辨率固定 1280x720：低照度下比 1080p 更亮、噪点更少、发热更低；
 *       同时给 M2 的模型一个固定尺寸的输入。
 *    3. 屏幕常亮 + 沉浸式全屏：这是固定在支架上的值守终端，不是普通 App。
 *
 *  真机：荣耀 200 / MagicOS 10.0.0.175 / Android 16 (API 36) / 骁龙 7 Gen 3
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GasGuard"

        /** 目标分辨率。M2 的模型输入也会基于这个尺寸，不要随意改动。 */
        private const val TARGET_WIDTH = 1280
        private const val TARGET_HEIGHT = 720

        /** 相机异常后的自动重试间隔（毫秒） */
        private const val RETRY_DELAY_MS = 5_000L
    }

    /**
     * 低照度优化方案。
     *
     * 每一项都在 [buildLowLightPlan] 里先查过真机是否支持，
     * 不支持就留 null / false（跳过），绝不硬塞。
     *
     * 为什么需要它：加油站夜间只有灯光照明，CameraX 默认参数往往把帧率
     * 固定在 30fps，等于把曝光时间锁死在 1/30 秒，画面又黑又噪。
     * 下面这几项都是冲着"夜间看得清"去的。
     */
    private class LowLightPlan(
        /** AE 目标帧率区间。取「最低帧率最小」的一档，让 AE 能用更长的曝光。 */
        val fpsRange: Range<Int>?,
        /** 降噪模式，高质量降噪专治夜间噪点。 */
        val noiseReduction: Int?,
        /** 边缘增强模式，让车灯和车身轮廓更锐利。 */
        val edgeMode: Int?,
        /** 是否关闭电子防抖。 */
        val stabilizationOff: Boolean
    ) {
        /**
         * 把方案应用到「预览」用例上。
         *
         * 注：这里刻意把预览和分析写成两个方法而不是一个泛型方法 ——
         * CameraX 的 UseCase.Builder 基类在 Kotlin 里访问不到（真机编译验证过），
         * 用具体类型反而更稳。
         */
        fun applyToPreview(builder: Preview.Builder) {
            val extender = Camera2Interop.Extender(builder)
            fpsRange?.let {
                extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
            }
            noiseReduction?.let {
                extender.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, it)
            }
            edgeMode?.let {
                extender.setCaptureRequestOption(CaptureRequest.EDGE_MODE, it)
            }
            if (stabilizationOff) {
                extender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                )
            }
        }

        /** 把方案应用到「分析」用例上。参数必须和预览完全一致，否则绑定会冲突。 */
        fun applyToAnalysis(builder: ImageAnalysis.Builder) {
            val extender = Camera2Interop.Extender(builder)
            fpsRange?.let {
                extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
            }
            noiseReduction?.let {
                extender.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, it)
            }
            edgeMode?.let {
                extender.setCaptureRequestOption(CaptureRequest.EDGE_MODE, it)
            }
            if (stabilizationOff) {
                extender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                )
            }
        }

        /** 给人看的一句话摘要，显示在左上角诊断条上。 */
        fun summary(): String {
            val exposure = fpsRange?.let { "曝光≤1/${it.lower}s" } ?: "曝光默认"
            val denoise = if (noiseReduction != null) "降噪高" else "降噪默认"
            val stab = if (stabilizationOff) "防抖关" else "防抖默认"
            return "$exposure/$denoise/$stab"
        }
    }

    /** 视图绑定对象，对应 res/layout/activity_main.xml */
    private lateinit var binding: ActivityMainBinding

    /** 相机分析专用线程池。相机回调绝对不能跑在主线程上，否则界面会卡死。 */
    private lateinit var cameraExecutor: ExecutorService

    /** 相机提供者（相机系统总入口） */
    private var cameraProvider: ProcessCameraProvider? = null

    /** 当前绑定的相机对象，为 null 表示相机没起来 */
    private var camera: Camera? = null

    /** 防止重试逻辑并发进入，导致相机被重复绑定 */
    private var cameraStarting = false

    /** 当前是否在前台。退到后台时不做无意义的重试。 */
    private var isForeground = false

    /** 上一次注册的相机状态监听。重新绑定前必须先摘掉，否则会越积越多。 */
    private var cameraStateLive: LiveData<CameraState>? = null

    /**
     * 是否启用低照度优化。
     * 如果真机不支持其中某个参数导致相机起不来，会自动置 false 并立刻回退重试，
     * 绝不让"优化"变成"相机根本打不开"。
     */
    private var lowLightTuning = true

    /** 低照度优化的实际生效情况，显示在诊断条上，方便真机核对。 */
    private var lowLightSummary = "待定"

    // ============================================================
    //  诊断仪表（M1 的验证工具，不是业务功能）
    // ============================================================

    /** 分析器每收到一帧就 +1；主线程每秒读一次，两次的差值就是真实帧率 */
    private val frameCounter = AtomicInteger(0)
    private var lastFrameCount = 0

    /** 实际生效的分析分辨率。由相机回调给出，比「我要求了什么」更可信。 */
    @Volatile private var actualWidth = 0
    @Volatile private var actualHeight = 0

    /** 相机成功绑定的时刻，用来显示「已运行时长」 */
    private var boundAtMs = 0L

    private val uiHandler = Handler(Looper.getMainLooper())

    /** 每秒刷新一次左上角诊断条 */
    private val ticker = object : Runnable {
        override fun run() {
            updateDiag()
            uiHandler.postDelayed(this, 1_000L)
        }
    }

    /** 相机出错 / 被系统关闭后的自动重试任务 */
    private val retryRunnable = Runnable {
        Log.w(TAG, "自动重试：重新启动相机")
        cameraStarting = false
        startCamera()
    }

    /** 相机权限请求。用户在系统弹窗上点「允许 / 拒绝」后回调。 */
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                showError(
                    "未获得相机权限，无法工作。\n\n" +
                        "请到：设置 → 应用 → 应用管理 → 加油站车辆报警 → 权限 → 相机，\n" +
                        "改为「允许」，然后重新打开本应用。"
                )
            }
        }

    // ============================================================
    //  生命周期
    // ============================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 固定支架上的值守终端：只要这个界面在前台，屏幕永不息屏
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 沉浸式全屏：隐藏状态栏和导航栏，画面铺满整块屏幕
        enterImmersiveFullscreen()

        cameraExecutor = Executors.newSingleThreadExecutor()

        // 启动每秒刷新的诊断条
        uiHandler.post(ticker)

        // 有权限就直接开相机，没有就先申请权限
        if (hasCameraPermission()) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStart() {
        super.onStart()
        isForeground = true
        // 回到前台时如果相机没起来（比如刚被系统抢占过），补一次
        if (camera == null) {
            startCamera()
        }
    }

    override fun onStop() {
        super.onStop()
        isForeground = false
        // 退到后台时取消待执行的重试，避免在后台无意义地反复尝试
        uiHandler.removeCallbacks(retryRunnable)
        cameraStarting = false
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacksAndMessages(null)
        cameraStateLive?.removeObservers(this)
        cameraStateLive = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        camera = null
        cameraExecutor.shutdown()
        Log.i(TAG, "MainActivity 销毁，相机已释放")
    }

    // ============================================================
    //  相机
    // ============================================================

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 请求并绑定相机。
     *
     * 任何一步失败都会：
     *   ① 在屏幕中央显示红字（禁止静默降级）
     *   ② 5 秒后自动重试（如果是低照度参数不被支持，1 次立即回退）
     */
    private fun startCamera() {
        if (cameraStarting) return
        cameraStarting = true

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            // 窗口已经关了就别再绑定了，白白泄露一个相机
            if (isFinishing || isDestroyed) {
                cameraStarting = false
                return@addListener
            }

            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val selector = CameraSelector.DEFAULT_BACK_CAMERA

                // ---------- 0) 准备低照度优化方案 ----------
                //    先读真机能力，只挑它支持的参数，避免会话配置失败。
                val plan: LowLightPlan? = if (lowLightTuning) {
                    try {
                        // 先从 CameraX 拿到这块摄像头的 Camera2 编号，
                        // 再用系统 CameraManager 读出它的完整能力表
                        //（支持哪些帧率档、哪些降噪档、防抖能不能关…）。
                        val cameraId =
                            Camera2CameraInfo.from(provider.getCameraInfo(selector)).cameraId
                        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                        buildLowLightPlan(manager.getCameraCharacteristics(cameraId))
                    } catch (t: Throwable) {
                        Log.e(TAG, "读取相机能力失败，本次跳过夜视优化", t)
                        null
                    }
                } else {
                    null
                }
                lowLightSummary = plan?.summary() ?: "已关闭"

                // ---------- 1) 预览：占满屏幕 ----------
                val previewBuilder = Preview.Builder()
                    .setResolutionSelector(buildResolutionSelector())
                plan?.applyToPreview(previewBuilder)
                val preview = previewBuilder
                    .build()
                    .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

                // ---------- 2) 分析：M1 只做心跳，不加载任何模型 ----------
                //    M2 会把 YOLO 推理挂在这个 analyzer 里。
                //    现在它只做三件事：记录分辨率、帧数 +1、立刻释放图像。
                val analysisBuilder = ImageAnalysis.Builder()
                    .setResolutionSelector(buildResolutionSelector())
                    // KEEP_ONLY_LATEST：处理不过来就丢旧帧，绝不积压。
                    // 这是长时间稳定运行的关键——积压会让延迟越来越大直到内存爆掉。
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                plan?.applyToAnalysis(analysisBuilder)
                val analysis = analysisBuilder
                    .build()
                    .also { imageAnalysis ->
                        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            actualWidth = imageProxy.width
                            actualHeight = imageProxy.height
                            frameCounter.incrementAndGet()
                            imageProxy.close()   // 必须关闭，否则相机停止出帧
                        }
                    }

                // ---------- 3) 绑定到本 Activity 的生命周期 ----------
                //    页面一走，相机自动释放，不会漏电、不会占着摄像头不放。
                provider.unbindAll()
                val bound = provider.bindToLifecycle(this, selector, preview, analysis)

                camera = bound
                boundAtMs = System.currentTimeMillis()
                frameCounter.set(0)
                lastFrameCount = 0
                showError(null)

                // ---------- 4) 监听相机状态 ----------
                //    被其它应用抢占 / 硬件报错时，必须让人看得见，并自动重试。
                //
                //    ⚠️ 关键细节（真机上踩过的坑）：
                //    LiveData.observe() 会【立刻】把当前值推给我们。
                //    刚绑定的那一瞬间相机还没打开，状态是 CLOSED / PENDING_OPEN，
                //    如果直接当成「相机被系统抢占」就会立刻触发重试，
                //    重试又绑定又立刻收到 CLOSED，变成无限重启循环。
                //    所以：必须等它真正 OPEN 过一次之后，CLOSED 才算异常。
                cameraStateLive?.removeObservers(this)
                val stateLive = bound.cameraInfo.cameraState
                cameraStateLive = stateLive

                var hasEverOpened = false
                stateLive.observe(this) { state ->
                    when (state.type) {
                        CameraState.Type.OPEN -> hasEverOpened = true

                        CameraState.Type.CLOSED -> {
                            if (hasEverOpened) {
                                Log.w(TAG, "相机被关闭（可能被系统或其它应用抢占）")
                                // 只有在前台时才弹红字，避免退到后台正常释放相机时闪一下
                                if (isForeground) {
                                    showError("相机已被系统关闭，5 秒后自动重试…")
                                }
                                camera = null
                                scheduleRetry()
                            }
                        }

                        else -> Unit
                    }

                    state.error?.let { err ->
                        Log.e(TAG, "相机报错 code=${err.code}", err.cause)
                        showError("相机异常（错误码 ${err.code}），5 秒后自动重试…")
                        camera = null
                        scheduleRetry()
                    }
                }

                cameraStarting = false
                Log.i(TAG, "相机绑定成功 | 夜视: $lowLightSummary")

            } catch (t: Throwable) {
                if (lowLightTuning) {
                    // 很可能是某个低照度参数真机不支持，导致会话配置失败。
                    // 关掉夜视优化立刻重试一次 —— 绝不让"优化"变成"相机打不开"。
                    Log.e(TAG, "绑定失败，关闭夜视优化后立即重试", t)
                    lowLightTuning = false
                    lowLightSummary = "已回退（真机不支持）"
                    showError("夜视参数不被支持，已回退默认参数，正在重试…")
                    camera = null
                    scheduleRetry(0L)
                    return@addListener
                }

                // 绑定失败：被占用 / 硬件异常 / 权限被撤销
                Log.e(TAG, "相机启动失败", t)
                showError("相机启动失败：${t.message}\n5 秒后自动重试…")
                camera = null
                scheduleRetry()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 读取真机能力，组装出一套「够亮、够干净」的夜视参数。
     *
     * 每一项都是先问真机"你支不支持"，支持才用。
     * 这是夜间值守画质的关键——加油站夜里只有灯光，默认参数拍出来又黑又糊。
     */
    private fun buildLowLightPlan(chars: CameraCharacteristics): LowLightPlan {
        // ① 曝光时间预算（收益最大的一项）
        //    挑「最低帧率最小」的那一档。AE 在暗光下会用这档允许的最长曝光，
        //    例如 [15,30] 最长可曝光 1/15 秒，比锁定 30fps 的 1/30 秒亮一倍。
        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        val bestFps: Range<Int>? = fpsRanges
            ?.filter { it.lower >= 7 && it.upper <= 60 }   // 排除慢动作/高速摄影档
            ?.minByOrNull { it.lower }

        // ② 降噪拉满：夜间噪点是识别率的头号杀手
        val nrModes = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
        val nr = if (nrModes?.contains(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY) == true) {
            CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
        } else {
            null
        }

        // ③ 边缘增强拉满：让车灯、车身轮廓更锐利
        val edgeModes = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
        val edge = if (edgeModes?.contains(CaptureRequest.EDGE_MODE_HIGH_QUALITY) == true) {
            CaptureRequest.EDGE_MODE_HIGH_QUALITY
        } else {
            null
        }

        // ④ 关闭电子防抖：手机固定在支架上，根本不需要防抖；
        //    关掉还能换回被 EIS 裁切掉的一圈视野 —— 看得更宽 = 更不容易漏车。
        val stabModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        val stabOff =
            stabModes?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF) == true

        return LowLightPlan(bestFps, nr, edge, stabOff)
    }

    /** 安排一次自动重试。同一时间只会有一个待执行的重试任务。 */
    private fun scheduleRetry(delayMs: Long = RETRY_DELAY_MS) {
        cameraStarting = false
        if (!isForeground) {
            // 后台不重试；回到前台时 onStart() 会自动补一次
            return
        }
        uiHandler.removeCallbacks(retryRunnable)
        uiHandler.postDelayed(retryRunnable, delayMs)
    }

    /**
     * 分辨率策略：优先要 16:9 的 1280x720。
     * 设备不支持这一档时，退选最接近的一档（先往上找，再往下找）。
     * 实际生效的尺寸会显示在左上角诊断条上，不会偷偷降级不告诉你。
     */
    private fun buildResolutionSelector(): ResolutionSelector =
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(TARGET_WIDTH, TARGET_HEIGHT),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

    // ============================================================
    //  界面
    // ============================================================

    /** 隐藏状态栏与导航栏，让画面铺满整块屏幕 */
    private fun enterImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            // 允许用户从边缘上滑临时唤出状态栏
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** 刷新左上角诊断条：状态 / 分辨率 / 帧率 / 夜视 / 已运行时长 */
    private fun updateDiag() {
        val total = frameCounter.get()
        val fps = total - lastFrameCount
        lastFrameCount = total

        val stateText = when (camera?.cameraInfo?.cameraState?.value?.type) {
            CameraState.Type.OPEN -> "运行中"
            CameraState.Type.OPENING -> "启动中"
            CameraState.Type.PENDING_OPEN -> "等待打开"
            CameraState.Type.CLOSING -> "关闭中"
            CameraState.Type.CLOSED -> "已关闭"
            else -> "未启动"
        }

        val resText = if (actualWidth > 0) "${actualWidth}x${actualHeight}" else "等待画面…"

        val uptimeSec =
            if (boundAtMs > 0) (System.currentTimeMillis() - boundAtMs) / 1000 else 0L

        binding.tvDiag.text = String.format(
            Locale.US,
            "状态：%s\n分辨率：%s\n帧率：%d fps\n夜视：%s\n已运行：%s",
            stateText, resText, fps, lowLightSummary, formatDuration(uptimeSec)
        )
    }

    /** 秒 → HH:MM:SS */
    private fun formatDuration(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    /** 显示 / 清除居中的红色错误提示。传 null 表示清除。 */
    private fun showError(message: String?) {
        if (message.isNullOrBlank()) {
            binding.tvError.visibility = View.GONE
        } else {
            binding.tvError.text = message
            binding.tvError.visibility = View.VISIBLE
        }
    }
}
