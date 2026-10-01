package com.gasstation.guard

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.LiveData
import com.gasstation.guard.alarm.AlarmController
import com.gasstation.guard.databinding.ActivityMainBinding
import com.gasstation.guard.detect.Detection
import com.gasstation.guard.detect.DetectionGate
import com.gasstation.guard.detect.RoiRect
import com.gasstation.guard.detect.YoloDetector
import com.gasstation.guard.detect.isInsideRoi
import com.gasstation.guard.evidence.EvidenceRecorder
import com.gasstation.guard.evidence.EvidenceStore
import com.gasstation.guard.service.MonitorService
import com.gasstation.guard.service.WatchdogReceiver
import com.gasstation.guard.alarm.VoiceAnnouncer
import com.gasstation.guard.databinding.DialogReadinessBinding
import com.gasstation.guard.databinding.ItemReadinessRowBinding
import com.gasstation.guard.settings.ReadinessChecker
import com.gasstation.guard.settings.SettingsStore
import com.gasstation.guard.thermal.ThermalGuard
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

        /**
         * 两次推理之间的最小间隔（毫秒）。
         *
         * 相机每秒出 30 帧，但【完全没必要】每帧都跑模型：
         *  - 车辆驶入是个持续几秒的过程，每秒看 2 次足够发现
         *  - 推理很吃 CPU 和电，跑满会让手机发烫（M6 的温控问题）
         *  - 章程本身就规划了 1~5 fps 的低帧率工作模式
         * 现在固定 500ms（2 fps），M6 会改成按电池温度自适应。
         */
        private const val DETECT_INTERVAL_MS = 500L

        /** 调试触发报警时用的拨号延时（秒）。故意很长，防止测试时误拨真实号码。 */
        private const val DEBUG_DIAL_DELAY_SECONDS = 300

        // ---------- M4 看门狗 ----------

        /**
         * 画面连续多少秒没有出帧，就判定相机卡死并重建。
         *
         * 设 10 秒是权衡：太短会把"短暂的 AE/AF 调整"误判成故障而频繁重启相机；
         * 太长则卡死之后会有很长一段无人值守的时间。
         */
        private const val FRAME_WATCHDOG_SECONDS = 10

        /** 给值守服务发心跳的间隔。服务那边 45 秒收不到心跳才会认为界面挂了。 */
        private const val SERVICE_HEARTBEAT_INTERVAL_MS = 10_000L

        /**
         * 调试触发报警的延迟（M5）。
         *
         * 要留够时间让相机启动、模型加载、**以及告警录像的环形缓冲填满** ——
         * 否则"报警前"那段画面是空的，取证功能等于没测到。
         */
        private const val DEBUG_ALARM_DELAY_MS = 20_000L

        // ---------- M6 温控 ----------

        /** 每多少秒检查一次温度。温度变化很慢，每秒查是浪费。 */
        private const val THERMAL_CHECK_INTERVAL_S = 5
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
    //  车辆识别（M2）
    // ============================================================

    /**
     * YOLO 检测器。模型有十几 MB，加载需要时间，所以放在后台线程里初始化，
     * 加载完成前这里是 null —— 相机预览不受影响，先出画面再出识别框。
     */
    @Volatile
    private var detector: YoloDetector? = null

    /** 上一次执行推理的时刻，用来限制推理频率 */
    private var lastDetectAtMs = 0L

    /** 最近一次推理检出的目标数量，显示在诊断条上 */
    @Volatile
    private var lastDetectionCount = 0

    /** 最近一次推理的耗时（毫秒） */
    @Volatile
    private var lastInferenceMs = 0L

    // ============================================================
    //  报警（M3）
    // ============================================================

    /** 应用参数：紧急号码、监测区域、拨号延时… */
    private lateinit var settings: SettingsStore

    /** 多帧确认闸门：决定"连续看到几帧才算真的有车" */
    private val gate = DetectionGate()

    /** 报警控制器：声音 + 震动 + 中文语音 + 升级拨号 */
    private lateinit var alarmController: AlarmController

    /** 当前生效的监测区域。分析线程会读它，所以用 @Volatile。 */
    @Volatile
    private var roi: RoiRect = RoiRect.FULL

    /** 红色闪烁动画 */
    private var flashAnimator: ValueAnimator? = null

    /** 报警界面倒计时刷新任务 */
    private var countdownRunnable: Runnable? = null

    /**
     * 本次报警是否由调试入口触发。
     *
     * 如果是，自动拨号的延时会从 30 秒拉长到 5 分钟 ——
     * 防止在自动化测试中因为"忘了及时解除报警"而真的把电话拨出去。
     * （2026-10 实际发生过：测试时用户已经填了真实号码，
     *   连续跑了几次报警却没在 30 秒内停掉，无法确认是否误拨。）
     */
    private var debugTriggeredAlarm = false

    /** 是否已授予拨号权限。没有它，最后一道防线就是空的 —— 必须显式提示，不静默。 */
    private var canAutoDial = false

    /** 最近一帧是否有目标落在监测区域内 */
    @Volatile
    private var lastRoiHit = false

    /** 多帧确认闸门的状态描述，显示在诊断条上 */
    @Volatile
    private var lastGateSummary = "-"

    /** 最近一帧的检出结果，报警时写进告警详情 */
    @Volatile
    private var lastDetections: List<Detection> = emptyList()

    // ============================================================
    //  告警证据（M5）
    // ============================================================

    private lateinit var evidenceStore: EvidenceStore
    private lateinit var evidenceRecorder: EvidenceRecorder

    // ============================================================
    //  温控（M6）
    // ============================================================

    private lateinit var thermalGuard: ThermalGuard
    private lateinit var voiceAnnouncer: VoiceAnnouncer

    /** 值守就绪检查（M7）：启动时把缺的配置直接摆在用户面前 */
    private lateinit var readinessChecker: ReadinessChecker

    /** 用户点了「去处理」跳出去修配置——回到本界面时要重新检查一遍 */
    private var awaitingFix = false

    /** 当前温控档位 */
    @Volatile
    private var thermalLevel = ThermalGuard.Level.NORMAL

    /** 温度检查的秒计数（每若干秒查一次就够，温度变化很慢） */
    private var thermalTickCounter = 0

    /**
     * 当前生效的识别间隔（毫秒）。
     *
     * 会随温控档位动态变化 —— 这是"自适应帧率"的实现点。
     * 用 @Volatile 是因为 analyze 线程要读它。
     */
    @Volatile
    private var currentDetectIntervalMs = 500L

    // ============================================================
    //  值守看门狗（M4）
    // ============================================================

    /** 上一次刷新时算出的帧率，供相机看门狗判断"画面是不是停了" */
    @Volatile
    private var lastFps = 0

    /** 连续多少秒没有出帧 */
    private var zeroFpsStreak = 0

    /** 上一次给值守服务发心跳的时刻 */
    private var lastHeartbeatAt = 0L

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

    /** 每秒刷新一次诊断条、跑一次相机看门狗、给值守服务发心跳 */
    private val ticker = object : Runnable {
        override fun run() {
            updateDiag()
            checkCameraWatchdog()
            reportHeartbeatToService()
            tickThermal()
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

    /** 通知权限（M4）。被拒时前台服务通知不显示，部分 ROM 会因此杀掉服务。 */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Log.w(TAG, "未授予通知权限：前台服务通知不显示，部分 ROM 会因此杀掉服务")
            }
        }

    /** 拨号权限的申请结果。被拒时会在屏幕上明确提示"自动拨号不可用"。 */
    private val callPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            canAutoDial = granted
            if (!granted) {
                Log.w(TAG, "未授予拨号权限：30 秒后自动拨号这道防线不可用")
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

        // ---------- M2：车辆识别模型加载 ----------
        // 模型十几 MB，放主线程会卡住启动。丢到相机线程池里初始化：
        // 相机预览先出来，模型加载完再开始画框，互不阻塞。
        cameraExecutor.execute {
            try {
                detector = YoloDetector(this)
            } catch (t: Throwable) {
                Log.e(TAG, "车辆识别模型加载失败", t)
                uiHandler.post {
                    showError(
                        "识别模型加载失败：${t.message}\n\n" +
                            "请确认 app/src/main/assets/yolo_int8.tflite 存在，\n" +
                            "并且 app/build.gradle.kts 里有 noCompress += \"tflite\"。"
                    )
                }
            }
        }

        // ---------- M6：温控与语音播报 ----------
        thermalGuard = ThermalGuard(this)
        voiceAnnouncer = VoiceAnnouncer(this)

        // ---------- M5：告警证据 ----------
        evidenceStore = EvidenceStore(this)
        evidenceRecorder = EvidenceRecorder(
            context = this,
            store = evidenceStore,
            onSessionSaved = { dir, videoOk ->
                Log.i(TAG, "告警证据已保存：${dir.name}（视频${if (videoOk) "正常" else "编码失败"}）")
            }
        )
        // 启动时清理过期记录（7 天前）。放后台线程，别拖慢启动。
        cameraExecutor.execute {
            val removed = evidenceStore.cleanupExpired()
            Log.i(TAG, "启动清理：删除 $removed 个过期告警目录")
        }

        // ---------- M3：报警相关初始化 ----------
        settings = SettingsStore(this)
        readinessChecker = ReadinessChecker(this, settings)

        // 读出已保存的监测区域（没设过就是整幅画面）
        roi = RoiRect(
            settings.roiLeft, settings.roiTop, settings.roiRight, settings.roiBottom
        )
        binding.roiOverlay.updateRoi(roi)
        binding.roiOverlay.onRoiChanged = { left, top, right, bottom ->
            settings.saveRoi(left, top, right, bottom)
            roi = RoiRect(left, top, right, bottom)
            binding.roiOverlay.updateRoi(roi)
            Log.i(TAG, "监测区域已更新：L=%.3f T=%.3f R=%.3f B=%.3f"
                .format(left, top, right, bottom))
        }
        binding.roiOverlay.onCalibrationChanged = { calibrating ->
            // 框选时把诊断条藏起来，免得挡住视线
            binding.tvDiag.visibility = if (calibrating) View.GONE else View.VISIBLE
        }

        alarmController = AlarmController(
            context = this,
            onEscalateDial = { number -> dialEmergencyNumber(number) }
        )

        // 「已到岗」长按解除报警（时长可在设置里调，默认 2 秒）
        applyHoldDuration()
        binding.btnImHere.onConfirmed = { dismissAlarm() }

        // 长按左上角诊断条 = 打开设置
        // （M6 会做成正式的隐藏设置页，这里是 M3 的最小可用入口）
        // 长按状态胶囊 = 打开设置。
        // 胶囊是主界面上唯一常驻的元素，也是最自然的入口。
        binding.statusChip.setOnLongClickListener {
            showSettingsDialog()
            true
        }
        // 调试面板也保留同样的入口（打开调试信息时用得上）
        binding.tvDiag.setOnLongClickListener {
            showSettingsDialog()
            true
        }

        // 拨号权限
        canAutoDial = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
        if (!canAutoDial) {
            callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
        }

        // ---------- M4：启动值守前台服务 ----------
        // 它让进程保持前台优先级（防低内存杀手），并在界面挂掉时把它拉回来。
        try {
            MonitorService.start(this)
        } catch (t: Throwable) {
            Log.e(TAG, "启动值守服务失败", t)
        }

        // 通知权限：没有它，前台服务的常驻通知不显示，
        // 部分 ROM（包括 MagicOS）会因此直接杀掉服务。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 禁止用返回键退出。值守工具被误退出一次 = 整晚没有值守。
        onBackPressedDispatcher.addCallback(this) {
            Log.i(TAG, "已忽略返回键（值守中不允许用返回键退出）")
        }

        // ---------- 仅 debug 生效：报警通道自测入口 ----------
        // 没有车的时候也需要验证"报警到底响不响、闪不闪、震不震、说不说话"，
        // 否则要等到真有机动车经过才能发现问题，太被动。
        //
        // 用 BuildConfig.DEBUG 严格守住：正式版（release）里这段代码根本不生效，
        // 外部应用无法通过 Intent 触发假报警。
        // 用法：adb shell am start -n com.gasstation.guard/.MainActivity --ez debug_alarm true
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("debug_alarm", false) == true) {
            // 延迟 20 秒再触发：要给相机启动、模型加载、环形缓冲填满留出时间。
            // 太早触发的话"报警前"那一帧都没有，测不出取证功能到底有没有用。
            uiHandler.postDelayed({
                Log.w(TAG, "【仅调试】通过 Intent 触发一次报警自测（拨号延时已拉长到 5 分钟）")
                debugTriggeredAlarm = true
                startAlarm()
            }, DEBUG_ALARM_DELAY_MS)
        }

        // M6：把设置里的参数（识别频率 / 灵敏度 / 音量 / 温控阈值）应用上去
        applyAllSettings()

        // ---------- M7：启动就绪检查 ----------
        // 延后 2 秒弹，先让相机画面出来 —— 一进来就糊一个弹窗，
        // 用户第一眼看到的是"这软件怎么这么多要求"而不是"它在看着我门口"。
        uiHandler.postDelayed({ checkReadinessOnLaunch() }, 2_000L)

        // 启动每秒刷新的诊断条
        uiHandler.post(ticker)

        // 有权限就直接开相机，没有就先申请权限
        if (hasCameraPermission()) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户刚去系统设置里改过东西，回来立刻重新检查一遍，
        // 把"已修好的打勾、还没修的留着"直接呈现出来 —— 形成闭环。
        if (awaitingFix) {
            awaitingFix = false
            uiHandler.postDelayed({ showReadinessDialog(firstRun = false) }, 600L)
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
        // 报警必须在窗口销毁前停掉 —— 否则声音会一直响到进程被杀
        if (::alarmController.isInitialized) {
            alarmController.stop()
        }
        stopFlash()
        stopCountdown()
        uiHandler.removeCallbacksAndMessages(null)
        cameraStateLive?.removeObservers(this)
        cameraStateLive = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        camera = null
        // 让关闭动作排在推理任务之后执行，避免线程竞争
        cameraExecutor.execute {
            detector?.close()
            detector = null
        }
        if (::evidenceRecorder.isInitialized) {
            // 正常退出时应该已经 finishAlarm 了；这里兜底，避免留下半截录制
            evidenceRecorder.shutdown()
        }
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
                            // 心跳计数器【每帧】都记：它反映的是"相机还活着吗"，
                            // 不能被下面的推理限流影响，否则诊断条就骗人了。
                            frameCounter.incrementAndGet()

                            // ---------- 车辆识别（M2）----------
                            // 推理按间隔限流，省电控温（M6 会按电池温度自适应）。
                            val now = SystemClock.elapsedRealtime()
                            val activeDetector = detector
                            // M6：识别间隔由温控动态决定 ——
                            // 温度正常时是设置的频率，降载时自动变稀，
                            // 暂停时是 Long.MAX_VALUE（也就是这一帧直接跳过）
                            if (activeDetector != null &&
                                now - lastDetectAtMs >= currentDetectIntervalMs
                            ) {
                                lastDetectAtMs = now
                                try {
                                    // ImageProxy → Bitmap（CameraX 帮我们做格式转换）
                                    val frame = imageProxy.toBitmap()
                                    val result = activeDetector.detect(
                                        frame,
                                        imageProxy.imageInfo.rotationDegrees
                                    )
                                    // ---------- M5：喂给告警录像器 ----------
                                    // 每一帧都喂，这样报警时才能倒出"报警前"的画面。
                                    // 内部会缩到 640x360 再压缩，开销很小。
                                    //
                                    // ⚠️⚠️ 这一句【必须】在 frame.recycle() 之前 ⚠️⚠️
                                    // 真实踩过：一开始写在 recycle 之后，结果传进去的是
                                    // 已经回收的 Bitmap，压缩 100% 失败 —— 而且不报错，
                                    // 只是证据目录建好了、里面一帧都没有（静默失效）。
                                    evidenceRecorder.offerFrame(frame)

                                    frame.recycle()          // 立刻回收，减轻 GC 压力

                                    lastDetectionCount = result.detections.size
                                    lastInferenceMs = result.inferenceMs
                                    lastDetections = result.detections

                                    // ---------- M3：ROI 过滤 + 多帧确认 ----------
                                    // ① 只有【落在监测区域内】的目标才算数。
                                    //    这一步挡掉马路上的过路车、对面楼里的灯光、树影晃动。
                                    val currentRoi = roi
                                    val hit = result.detections.any { it.isInsideRoi(currentRoi) }
                                    lastRoiHit = hit

                                    // ② 多帧确认：单帧检出不算数，要窗口内多次命中。
                                    //    已经在报警时不再重复触发（避免同一个目标反复触发）。
                                    val confirmed = if (alarmController.isRinging) {
                                        false
                                    } else {
                                        gate.offer(hit)
                                    }
                                    lastGateSummary = gate.describe()

                                    // 画框必须回主线程：View 不是线程安全的
                                    uiHandler.post {
                                        if (!isFinishing && !isDestroyed) {
                                            binding.detectionOverlay.update(result)
                                            binding.roiOverlay.updateImageSize(
                                                result.imageWidth, result.imageHeight
                                            )
                                            if (confirmed) startAlarm()
                                        }
                                    }
                                } catch (t: Throwable) {
                                    // 单帧推理失败绝不能拖垮整个值守：记下来，跳过这帧
                                    Log.e(TAG, "推理失败，跳过本帧", t)
                                }
                            }

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
    //  报警（M3）
    // ============================================================

    // ============================================================
    //  值守看门狗（M4）
    // ============================================================

    // ============================================================
    //  温控与自适应帧率（M6）
    // ============================================================

    /** 每若干秒查一次温度；档位变化时播报并调整识别频率 */
    private fun tickThermal() {
        if (!::thermalGuard.isInitialized) return
        thermalTickCounter++
        if (thermalTickCounter < THERMAL_CHECK_INTERVAL_S) return
        thermalTickCounter = 0

        val level = thermalGuard.update()
        if (level != thermalLevel) {
            thermalLevel = level
            onThermalLevelChanged(level)
        }
        refreshDetectInterval()
    }

    /**
     * 档位变化时的处理。
     *
     * ⚠️ 两级降载都必须【说出来】：
     *    降载和暂停都会导致这段时间识别能力下降甚至完全停止。
     *    如果默默降载，值班的人会以为一切正常，而实际上车可能已经漏了。
     *    章程红线：禁止静默降级。
     */
    private fun onThermalLevelChanged(level: ThermalGuard.Level) {
        val temp = thermalGuard.lastTemperatureC
        Log.w(TAG, "温度保护：$level（${temp}°C）")
        when (level) {
            ThermalGuard.Level.NORMAL -> {
                binding.tvThermalWarning.visibility = View.GONE
                voiceAnnouncer.say("温度已恢复正常，继续监视")
            }
            ThermalGuard.Level.THROTTLED -> {
                binding.tvThermalWarning.text = "手机温度偏高（${temp}°C），已降低识别频率散热"
                binding.tvThermalWarning.visibility = View.VISIBLE
                voiceAnnouncer.say("手机温度偏高，已降低识别频率")
            }
            ThermalGuard.Level.PAUSED -> {
                binding.tvThermalWarning.text =
                    "⚠ 手机温度过高（${temp}°C），已暂停车辆识别\n请改善散热（移开阳光直射、垫高留出风道）"
                binding.tvThermalWarning.visibility = View.VISIBLE
                voiceAnnouncer.say("手机温度过高，已暂停车辆识别，请改善散热")
            }
        }
    }

    /** 把设置里的频率与当前温控档位合成出最终生效的识别间隔 */
    private fun refreshDetectInterval() {
        val base = settings.detectIntervalMs
        currentDetectIntervalMs = thermalGuard.detectIntervalMs(thermalLevel, base)
    }

    /** 把设置里的各项参数应用到运行时 */
    private fun applyAllSettings() {
        YoloDetector.confThreshold = settings.confidenceThreshold
        alarmController.volumePercent = settings.alarmVolumePercent
        thermalGuard.throttleC = settings.throttleTempC
        thermalGuard.pauseC = settings.pauseTempC
        refreshDetectInterval()
        Log.i(
            TAG,
            "参数已应用：间隔=${currentDetectIntervalMs}ms 阈值=${settings.confidenceThreshold} " +
                "音量=${settings.alarmVolumePercent}% 温控=${settings.throttleTempC}/${settings.pauseTempC}°C"
        )
    }

    /**
     * 相机看门狗：画面停止出帧超过阈值就强制重建相机。
     *
     * 为什么必须有：M1 已经处理了"相机报错/被抢占"，但有一种情况它抓不到 ——
     * **相机对象还活着、状态也是 OPEN，但就是不出帧了**（HAL 卡死、
     * 驱动异常、长时间运行后的资源泄漏）。
     *
     * 这种故障在界面上表现为"画面冻住"，诊断条的帧率会掉到 0，
     * 但没有任何异常抛出。如果没有这个看门狗，它会一直冻到天亮 ——
     * **整晚零值守，而且没有任何报错**，正是章程明令禁止的静默失效。
     *
     * 判据用帧率而不是相机状态：帧率是"实际有没有画面"的唯一可信信号。
     */
    private fun checkCameraWatchdog() {
        if (!isForeground || camera == null) {
            zeroFpsStreak = 0
            return
        }

        // 报警时不判定：报警界面盖住了预览，而且此时重建相机会打断报警
        if (alarmController.isRinging) {
            zeroFpsStreak = 0
            return
        }

        if (lastFps <= 0) {
            zeroFpsStreak++
            if (zeroFpsStreak >= FRAME_WATCHDOG_SECONDS) {
                Log.w(TAG, "相机看门狗：连续 ${zeroFpsStreak} 秒没有画面，强制重建相机")
                showError("画面已停止流动，正在自动恢复…")
                zeroFpsStreak = 0
                camera = null
                scheduleRetry(0L)
            }
        } else {
            zeroFpsStreak = 0
        }
    }

    /** 定期告诉值守服务"我还活着"，让它的看门狗知道不需要拉起界面 */
    private fun reportHeartbeatToService() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastHeartbeatAt < SERVICE_HEARTBEAT_INTERVAL_MS) return
        lastHeartbeatAt = now
        MonitorService.heartbeat(this)
    }

    // ============================================================
    //  报警（M3）
    // ============================================================

    /** 报警界面上要额外显示的警告（号码没设 / 没有拨号权限），空串表示无 */
    private var alarmWarning = ""

    /**
     * 启动强制报警。
     *
     * 触发这条路径的前提是：监测区域内连续多帧确认有车。
     * 一旦进入这里，声音、震动、语音三条通道会同时上，
     * 而且只有"长按 2 秒 已到岗"才能停下来。
     */
    private fun startAlarm() {
        if (alarmController.isRinging) return
        Log.i(TAG, "多帧确认通过，启动强制报警")

        val phone = settings.emergencyPhone
        alarmWarning = when {
            phone.isBlank() ->
                "紧急号码未设置，30 秒后无法自动拨号（长按左上角信息条填写）"
            !canAutoDial ->
                "未授予拨号权限，30 秒后无法自动拨号"
            else -> ""
        }

        binding.alarmOverlay.visibility = View.VISIBLE
        // 报警时藏起左上角诊断条：实测它会和报警文字叠在一起，看着像画面坏了，
        // 而人在半梦半醒时最不需要的就是"这个屏幕是不是出故障了"的困惑。
        binding.tvDiag.visibility = View.GONE
        // 报警时把状态胶囊也收起来：整屏都是报警界面，
        // 再挂一个"值守中"的胶囊既多余、又和报警信息抢注意力。
        binding.statusChip.visibility = View.GONE
        // 长按时长以设置里的当前值为准（用户可能刚改过）
        applyHoldDuration()
        startFlash()

        // M5：开始记录告警证据。会把环形缓冲里"报警前"的画面一起倒出来 ——
        // 那几秒才是车真正开进来的过程，等报警响了再录就晚了。
        evidenceRecorder.beginAlarm(lastDetections)

        // 调试触发时把拨号延时拉长，避免自动化测试误拨真实号码
        val dialDelay = if (debugTriggeredAlarm) DEBUG_DIAL_DELAY_SECONDS
        else settings.autoDialDelaySeconds

        alarmController.start(phone, dialDelay)
        startCountdown(dialDelay)
    }

    /** 值班人员确认已到岗，解除报警 */
    private fun dismissAlarm() {
        if (!alarmController.isRinging) return
        Log.i(TAG, "值班人员确认已到岗，解除报警")

        stopCountdown()
        alarmController.stop()
        stopFlash()
        binding.tvAlarmSubtitle.text = ""
        binding.alarmOverlay.visibility = View.GONE
        binding.tvAlarmDiag.visibility = View.GONE
        binding.statusChip.visibility = View.VISIBLE
        // 不在这里强制显示调试面板 —— 交给 updateDiag 按设置决定，
        // 否则会覆盖用户"关闭运行信息"的选择。

        // 通知闸门：等画面清空之后才重新武装，避免同一个目标反复触发
        gate.onAlarmDismissed()

        // M5：结束取证。会再补录几秒才开始编码，编码在后台线程做。
        evidenceRecorder.finishAlarm()
    }

    /** 红色闪烁：亮 0.4 秒 ↔ 暗 0.4 秒，无限循环 */
    private fun startFlash() {
        flashAnimator?.cancel()
        flashAnimator = ValueAnimator.ofFloat(0.92f, 0.22f).apply {
            duration = 400L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { binding.alarmFlash.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun stopFlash() {
        flashAnimator?.cancel()
        flashAnimator = null
    }

    /** 报警界面上的倒计时：还有多久自动拨号 */
    private fun startCountdown(totalSeconds: Int) {
        val startAt = SystemClock.elapsedRealtime()

        val runnable = object : Runnable {
            override fun run() {
                if (!alarmController.isRinging) return
                val elapsed = (SystemClock.elapsedRealtime() - startAt) / 1000
                val remain = (totalSeconds - elapsed).coerceAtLeast(0)
                val line = if (remain > 0) "${remain} 秒后自动拨号" else "正在自动拨号…"
                binding.tvAlarmSubtitle.text =
                    if (alarmWarning.isEmpty()) line else "$alarmWarning\n$line"
                uiHandler.postDelayed(this, 1_000L)
            }
        }
        countdownRunnable = runnable
        uiHandler.post(runnable)
    }

    private fun stopCountdown() {
        countdownRunnable?.let { uiHandler.removeCallbacks(it) }
        countdownRunnable = null
    }

    /**
     * 拨打紧急号码 —— 这是最后一道防线。
     *
     * 有没有拨号权限、号码有没有设置，都必须【显式失败】，
     * 绝不允许"以为拨了其实没拨"。章程红线。
     */
    private fun dialEmergencyNumber(number: String) {
        if (!canAutoDial) {
            showError(
                "自动拨号失败：未授予「电话」权限。\n\n" +
                    "请到：设置 → 应用 → 应用管理 → 加油站车辆报警 → 权限 → 电话，\n" +
                    "改为「允许」。"
            )
            return
        }
        try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "自动拨号失败", t)
            showError("自动拨号失败：${t.message}")
        }
    }

    /**
     * 设置对话框：紧急号码 + 解除报警的长按时长。
     *
     * 入口：长按左上角信息条。
     * M3 阶段的最小可用入口，M6 会做成正式的隐藏设置页。
     */
    private fun showSettingsDialog() {
        // 刻意做得紧凑：手机是【横屏】的，屏幕高度只有约 436dp，
        // 第一次做成竖排单选项时，"2 秒（推荐）"被挤到对话框外面完全看不见 ——
        // 用户能看到的只有「1 秒」和「1.5 秒」，恰恰是我最不希望被选中的两个。
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 4)
        }

        // ---------- ① 紧急联系电话 ----------
        container.addView(TextView(this).apply {
            text = "紧急联系电话"
            textSize = 16f
        })
        container.addView(TextView(this).apply {
            text = "报警 30 秒无人解除时自动拨打。留空 = 不拨号。"
            textSize = 12f
            setPadding(0, 2, 0, 8)
        })
        val phoneInput = EditText(this).apply {
            hint = "例如 13800138000"
            setText(settings.emergencyPhone)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            textSize = 16f
        }
        container.addView(phoneInput)

        // ---------- ② 解除报警的长按时长 ----------
        container.addView(TextView(this).apply {
            text = "解除报警的长按时长"
            textSize = 16f
            setPadding(0, 20, 0, 2)
        })
        container.addView(TextView(this).apply {
            // 这行警告不是客套话：误触解除 = 漏报，是项目章程里的头号红线。
            text = "⚠ 设得太短，报警时手擦过屏幕就误解除 —— 那就是漏报"
            textSize = 12f
            setTextColor(0xFFFF6D00.toInt())
            setPadding(0, 0, 0, 8)
        })

        val choices = listOf(1.0f, 1.5f, 2.0f, 3.0f, 5.0f)
        // 横向排布，省掉大量竖向空间（横屏下高度是最稀缺的资源）
        val radioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        choices.forEachIndexed { index, seconds ->
            val label = buildString {
                // ⚠️ Kotlin 里中文字符是合法的标识符字符，
                //    所以 "$seconds秒" 会被当成变量名 seconds秒 而编译失败。
                //    紧跟中文时必须用 ${} 包起来。
                append(
                    if (seconds == seconds.toInt().toFloat()) "${seconds.toInt()}秒"
                    else "${seconds}秒"
                )
                if (seconds == 2.0f) append("★")
            }
            radioGroup.addView(RadioButton(this).apply {
                id = index
                text = label
                textSize = 15f
                isChecked = seconds == settings.dismissHoldSeconds
            })
        }
        container.addView(radioGroup)
        container.addView(TextView(this).apply {
            text = "★ = 推荐（2 秒）。最短只允许 1 秒。"
            textSize = 12f
            setPadding(0, 0, 0, 4)
        })

        // 设置对话框自身的引用：下面几个按钮点完要先关掉对话框。
        // 必须声明在使用它的按钮之前 —— Kotlin 的局部变量不能先用后声明。
        var settingsDialog: AlertDialog? = null

        // ---------- ③ 识别与温控（M6） ----------
        container.addView(TextView(this).apply {
            text = "识别与温控"
            textSize = 16f
            setPadding(0, 20, 0, 2)
        })

        // 监测区域
        container.addView(TextView(this).apply {
            text = "监测区域：${if (settings.hasCustomRoi) "已框选" else "整幅画面（未框选）"}"
            textSize = 13f
        })
        container.addView(Button(this).apply {
            text = "重新框选监测区域"
            setOnClickListener {
                settingsDialog?.dismiss()
                // 稍等一下再进入框选，避免对话框的消失动画还在跑
                uiHandler.postDelayed({ binding.roiOverlay.startCalibration() }, 300L)
            }
        })

        // 识别灵敏度
        container.addView(TextView(this).apply {
            text = "识别灵敏度（越低越容易报警，也越容易误报）"
            textSize = 13f
            setPadding(0, 12, 0, 4)
        })
        val confOptions = listOf(0.25f, 0.35f, 0.50f)
        val confLabels = listOf("宽松0.25", "标准0.35★", "严格0.50")
        val confGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        confOptions.forEachIndexed { i, v ->
            confGroup.addView(RadioButton(this).apply {
                id = i
                text = confLabels[i]
                textSize = 14f
                isChecked = kotlin.math.abs(v - settings.confidenceThreshold) < 0.01f
            })
        }
        container.addView(confGroup)

        // 识别频率
        container.addView(TextView(this).apply {
            text = "识别频率（越稀越省电、越不发热，也越容易漏车）"
            textSize = 13f
            setPadding(0, 12, 0, 4)
        })
        val intervalOptions = listOf(300L, 500L, 1000L, 2000L)
        val intervalLabels = listOf("快300ms", "标准500ms★", "省电1s", "最省2s")
        val intervalGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        intervalOptions.forEachIndexed { i, v ->
            intervalGroup.addView(RadioButton(this).apply {
                id = i
                text = intervalLabels[i]
                textSize = 14f
                isChecked = v == settings.detectIntervalMs
            })
        }
        container.addView(intervalGroup)

        // 报警音量
        container.addView(TextView(this).apply {
            text = "报警音量（占闹钟最大音量的比例）"
            textSize = 13f
            setPadding(0, 12, 0, 4)
        })
        val volumeOptions = listOf(100, 80, 60, 40)
        val volumeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        volumeOptions.forEachIndexed { i, v ->
            volumeGroup.addView(RadioButton(this).apply {
                id = i
                text = if (v == 100) "最大★" else "${v}%"
                textSize = 14f
                isChecked = v == settings.alarmVolumePercent
            })
        }
        container.addView(volumeGroup)
        container.addView(TextView(this).apply {
            text = "⚠ 音量每降一档，叫醒成功率就低一档。夜班建议保持「最大」。"
            textSize = 12f
            setTextColor(0xFFFF6D00.toInt())
        })

        // 温度（只读展示；阈值按章程固定 45/50，不开放改动）
        container.addView(TextView(this).apply {
            text = "\n温度保护：降载 ${settings.throttleTempC.toInt()}°C ／ " +
                "暂停 ${settings.pauseTempC.toInt()}°C（章程固定值）\n" +
                "当前电池温度：${thermalGuard.describe(thermalLevel)}"
            textSize = 12f
            setPadding(0, 8, 0, 0)
        })

        // 运行信息开关
        container.addView(TextView(this).apply {
            text = "显示运行信息（帧率 / 分辨率 / 闸门状态）"
            textSize = 14f
            setPadding(0, 16, 0, 2)
        })
        container.addView(TextView(this).apply {
            text = "默认关闭，让界面干净。⚠ 做长时间值守验收时必须打开，" +
                "否则看不到帧率掉没掉、画面有没有卡。"
            textSize = 12f
            setTextColor(0xFFFF6D00.toInt())
            setPadding(0, 0, 0, 6)
        })
        val debugOptions = listOf(false, true)
        val debugGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        debugOptions.forEachIndexed { i, v ->
            debugGroup.addView(RadioButton(this).apply {
                id = i
                text = if (v) "显示" else "隐藏（默认）"
                textSize = 14f
                isChecked = v == settings.showDebugOverlay
            })
        }
        container.addView(debugGroup)

        // ---------- ④ 系统权限（M4 后台值守必需） ----------
        container.addView(TextView(this).apply {
            text = "系统权限（M4 后台值守必需）"
            textSize = 16f
            setPadding(0, 20, 0, 2)
        })
        container.addView(TextView(this).apply {
            text = "「显示在其他应用上层」是 Android 上【后台启动界面】的唯一可靠豁免。" +
                "没有它，手机重启后看门狗拉不起值守界面，等于没有自动恢复。"
            textSize = 12f
            setPadding(0, 0, 0, 8)
        })
        container.addView(Button(this).apply {
            text = "① 授予「显示在其他应用上层」"
            setOnClickListener {
                openSystemSettings(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ),
                    "打开失败，请手动到：设置 → 应用 → 应用管理 → 加油站车辆报警 → 特殊访问权限 → 显示在其他应用上层"
                )
            }
        })
        container.addView(Button(this).apply {
            text = "② 忽略电池优化"
            setOnClickListener {
                openSystemSettings(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    ),
                    "打开失败，请手动到：设置 → 电池 → 更多电池设置 → 应用耗电管理"
                )
            }
        })
        container.addView(TextView(this).apply {
            text = "③ 自启动管理：设置 → 应用 → 应用启动管理 → 关掉「自动管理」，" +
                "把三个开关全部打开\n" +
                "④ 后台加锁：在最近任务里下拉本应用的卡片，点锁图标\n" +
                "（③④ 荣耀没有标准入口，只能手动点，详见 docs/M4-操作手册.md）"
            textSize = 12f
            setPadding(0, 8, 0, 0)
        })

        // ---------- ④ 告警记录（M5） ----------
        container.addView(TextView(this).apply {
            text = "告警记录"
            textSize = 16f
            setPadding(0, 20, 0, 2)
        })
        val evidenceInfo = TextView(this).apply {
            text = evidenceSummary()
            textSize = 12f
        }
        container.addView(evidenceInfo)

        container.addView(Button(this).apply {
            text = "导出全部记录（打包分享）"
            setOnClickListener {
                settingsDialog?.dismiss()
                exportEvidence()
            }
        })
        container.addView(Button(this).apply {
            text = "删除全部记录"
            setTextColor(0xFFD32F2F.toInt())
            setOnClickListener {
                confirmDeleteEvidence {
                    evidenceStore.deleteAll()
                    evidenceInfo.text = evidenceSummary()
                }
            }
        })
        container.addView(TextView(this).apply {
            text = "录到顾客或无关画面时，可以随时在这里清掉；" +
                "没清的话超过 ${EvidenceStore.RETENTION_DAYS} 天也会自动删除。"
            textSize = 12f
            setPadding(0, 4, 0, 12)
        })

        // ---------- ⑤ 退出值守 ----------
        container.addView(Button(this).apply {
            text = "退出值守"
            setTextColor(0xFFD32F2F.toInt())
            setOnClickListener {
                settingsDialog?.dismiss()
                confirmExitMonitoring()
            }
        })

        // 内容变多了，包一层 ScrollView，避免横屏下又被挤出屏幕外
        val scroll = ScrollView(this).apply { addView(container) }

        settingsDialog = AlertDialog.Builder(this)
            .setTitle("设置")
            .setView(scroll)
            // 「使用说明」放在中性按钮上：它既不是"确认"也不是"取消"，
            // 放在这里用户随时能翻出来看，又不会占据主要位置。
            .setNeutralButton("使用说明") { _, _ ->
                showReadinessDialog(firstRun = true)
            }
            .setPositiveButton("保存") { _, _ ->
                settings.emergencyPhone = phoneInput.text.toString()

                val picked = choices.getOrNull(radioGroup.checkedRadioButtonId)
                    ?: settings.dismissHoldSeconds
                settings.dismissHoldSeconds = picked

                // 立刻生效，不用等下次报警
                applyHoldDuration()

                // M6：识别灵敏度 / 频率 / 音量
                confOptions.getOrNull(confGroup.checkedRadioButtonId)?.let {
                    settings.confidenceThreshold = it
                }
                intervalOptions.getOrNull(intervalGroup.checkedRadioButtonId)?.let {
                    settings.detectIntervalMs = it
                }
                volumeOptions.getOrNull(volumeGroup.checkedRadioButtonId)?.let {
                    settings.alarmVolumePercent = it
                }
                debugOptions.getOrNull(debugGroup.checkedRadioButtonId)?.let {
                    settings.showDebugOverlay = it
                }
                applyAllSettings()

                Log.i(TAG, "设置已更新：长按 ${settings.dismissHoldSeconds} 秒")
            }
            .setNegativeButton("取消", null)
            .create()
            .also { it.show() }
    }

    // ============================================================
    //  值守就绪检查与使用教程（M7）
    // ============================================================

    /**
     * 启动时的判断：
     *   首次安装 → 弹完整教程
     *   之后     → 只在发现缺项时弹
     *
     * 为什么不能每次都弹：用户会烦到直接养成"看到弹窗就关"的习惯，
     * 那样真正缺项时他也会关掉 —— 保护就失效了。
     */
    private fun checkReadinessOnLaunch() {
        if (isFinishing || isDestroyed) return
        val firstRun = !settings.hasSeenOnboarding
        val missing = readinessChecker.missingCount()

        if (firstRun) {
            Log.i(TAG, "首次启动，展示使用教程")
            showReadinessDialog(firstRun = true)
        } else if (missing > 0) {
            Log.w(TAG, "启动自检发现 $missing 项未就绪")
            showReadinessDialog(firstRun = false)
        }
    }

    /**
     * 展示「值守准备」对话框。
     *
     * @param firstRun true = 首次安装，展示完整教程文案；false = 只展示检查清单
     */
    private fun showReadinessDialog(firstRun: Boolean) {
        val items = readinessChecker.check()
        val dialogBinding = DialogReadinessBinding.inflate(layoutInflater)

        val intro = if (firstRun) {
            settings.hasSeenOnboarding = true
            "这个应用会在有车辆进入监测区域时强制报警：" +
                "响铃 + 震动 + 中文语音播报，必须长按「已到岗」才能解除。\n\n" +
                "为保证关键时刻真的能叫醒人，下面几项必须就绪。"
        } else {
            "启动自检发现下面几项还没就绪。\n\n" +
                "这些项目任何一项缺失都不会让界面报错，" +
                "但会让它在关键时刻不起作用 —— 请尽快补上。"
        }

        var dialog: AlertDialog? = null
        dialogBinding.checkList.removeAllViews()

        items.forEach { item ->
            val row = ItemReadinessRowBinding.inflate(
                layoutInflater, dialogBinding.checkList, false
            )
            row.tvMark.text = if (item.ok) "✓" else "✗"
            row.tvMark.setTextColor(
                ContextCompat.getColor(this, if (item.ok) R.color.accent else R.color.danger)
            )
            row.tvItemTitle.text = item.title
            row.tvItemWhy.text = item.why

            if (item.ok) {
                // 已就绪的项不显示按钮，但保留说明 —— 让用户知道"这一项是干什么的"
                row.btnFix.visibility = View.GONE
            } else {
                row.btnFix.setOnClickListener {
                    dialog?.dismiss()
                    awaitingFix = true
                    handleFix(item.fix)
                }
            }
            dialogBinding.checkList.addView(row.root)
        }

        dialog = AlertDialog.Builder(this)
            .setTitle(if (firstRun) "使用说明 · 值守准备" else "值守未就绪")
            .setMessage(intro)
            .setView(dialogBinding.root)
            .setPositiveButton("我知道了", null)
            .create()
            .also { it.show() }
    }

    /** 把就绪检查里的一项缺项转成具体动作 */
    private fun handleFix(fix: ReadinessChecker.Fix) {
        when (fix) {
            ReadinessChecker.Fix.CAMERA_PERM ->
                permissionLauncher.launch(Manifest.permission.CAMERA)

            ReadinessChecker.Fix.CALL_PERM ->
                callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)

            ReadinessChecker.Fix.NOTIFICATION_PERM ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }

            ReadinessChecker.Fix.OVERLAY_PERM ->
                openSystemSettings(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ),
                    "打开失败，请手动到：设置 → 应用 → 应用管理 → 加油站车辆报警 → " +
                        "特殊访问权限 → 显示在其他应用上层"
                )

            ReadinessChecker.Fix.BATTERY_OPT ->
                openSystemSettings(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    ),
                    "打开失败，请手动到：设置 → 电池 → 更多电池设置 → 应用耗电管理"
                )

            // 这两项没有对应的系统页面，只能回到应用内解决
            ReadinessChecker.Fix.PHONE_NUMBER -> showSettingsDialog()

            ReadinessChecker.Fix.ROI ->
                uiHandler.postDelayed({ binding.roiOverlay.startCalibration() }, 400L)

            ReadinessChecker.Fix.NONE -> Unit
        }
    }

    // ============================================================
    //  告警记录（M5）
    // ============================================================

    private fun evidenceSummary(): String =
        "已有 ${evidenceStore.sessionCount()} 次记录，占用 ${evidenceStore.usedSizeText()}\n" +
            "超过 ${EvidenceStore.RETENTION_DAYS} 天自动清理；也可随时手动删除。"

    /**
     * 导出全部告警记录。
     *
     * 打包成 zip 后走系统分享，用户可以发到微信/邮件，或存到网盘。
     * 直接访问 /Android/data 目录在 Android 11+ 上被系统限制，
     * 分享是唯一对普通用户友好的出口。
     */
    private fun exportEvidence() {
        val zip = evidenceStore.buildExportZip()
        if (zip == null) {
            showError("还没有任何告警记录可以导出。")
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", zip)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "加油站值守告警记录")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "导出告警记录"))
        } catch (t: Throwable) {
            Log.e(TAG, "导出告警记录失败", t)
            showError("导出失败：${t.message}")
        }
    }

    private fun confirmDeleteEvidence(onDeleted: () -> Unit) {
        val count = evidenceStore.sessionCount()
        if (count == 0) {
            showError("目前没有任何告警记录。")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除全部告警记录？")
            .setMessage(
                "将删除 $count 次记录，共 ${evidenceStore.usedSizeText()}。\n\n" +
                    "删除后无法恢复。"
            )
            .setPositiveButton("确认删除") { _, _ ->
                Log.w(TAG, "用户手动清空告警记录")
                evidenceStore.deleteAll()
                onDeleted()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ============================================================
    //  退出值守
    // ============================================================

    /**
     * 二次确认。
     *
     * 退出的后果必须说清楚 —— 对夜班值守来说，"以为还在守、其实已经关了"
     * 比"关不掉"危险得多。
     */
    private fun confirmExitMonitoring() {
        AlertDialog.Builder(this)
            .setTitle("退出值守？")
            .setMessage(
                "退出后将【停止监视加油站入口】，不会再有任何识别和报警。\n\n" +
                    "需要重新值守时，请再打开本应用。"
            )
            .setPositiveButton("确认退出") { _, _ -> exitMonitoring() }
            .setNegativeButton("继续值守", null)
            .show()
    }

    /**
     * 真正退出。
     *
     * ⚠️ 顺序不能错，错了会出现"用户以为退出了、其实还在跑"的情况：
     *
     *   ① 先解除外部看门狗闹钟 —— 否则 5 分钟后它会把服务重新拉起来
     *   ② 再停止前台服务 —— stopService 会同时取消 START_STICKY 的重启
     *   ③ 最后关闭界面 —— 如果先关界面，服务的界面看门狗 45 秒后会把界面拉回来
     */
    private fun exitMonitoring() {
        Log.w(TAG, "用户主动退出值守")

        WatchdogReceiver.disarm(this)

        try {
            stopService(Intent(this, MonitorService::class.java))
        } catch (t: Throwable) {
            Log.e(TAG, "停止值守服务失败", t)
        }

        finishAndRemoveTask()
    }

    /**
     * 打开系统设置页。
     *
     * 失败【必须明确报出来】：打不开设置页意味着用户没法完成 M4 必需的授权，
     * 那值守就是不可靠的 —— 不能让人以为"点过了就好了"。
     */
    private fun openSystemSettings(intent: Intent, failureHint: String) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "打开系统设置失败", t)
            showError(failureHint)
        }
    }

    /** 把设置里的长按时长应用到「已到岗」按钮上 */
    private fun applyHoldDuration() {
        binding.btnImHere.holdMillis = (settings.dismissHoldSeconds * 1000f).toLong()
    }

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
        lastFps = fps

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

        // 识别状态：模型还在加载 / 检出了几个目标 / 单帧耗时
        val detectText = if (detector == null) {
            "模型加载中…"
        } else if (lastDetectionCount > 0) {
            "检出 $lastDetectionCount 个 (${lastInferenceMs}ms)"
        } else {
            "无目标 (${lastInferenceMs}ms)"
        }

        // 监测区域与多帧确认闸门的状态
        val roiText = if (settings.hasCustomRoi) "已框选" else "整幅画面(未框选)"
        val gateText = if (lastRoiHit) "区域内·$lastGateSummary" else "区域内无车"

        // 报警中：在报警界面上实时显示三条通道的状态
        if (alarmController.isRinging) {
            binding.tvAlarmDiag.text =
                alarmController.diagnosticSummary() + "\n证据：" + evidenceRecorder.debugSummary()
            binding.tvAlarmDiag.visibility = View.VISIBLE
        } else {
            binding.tvAlarmDiag.visibility = View.GONE
        }

        // 暂停时 currentDetectIntervalMs 是 Long.MAX_VALUE，
        // 直接打出来会变成一串 19 位的天文数字，用户只会一脸问号。显示成"已暂停"。
        val intervalText =
            if (currentDetectIntervalMs >= Long.MAX_VALUE / 2) "已暂停"
            else "${currentDetectIntervalMs}ms"

        // ---------- 状态胶囊：主界面上唯一的常驻信息 ----------
        // 只放"现在能不能靠它"这一个判断所需的最少内容：
        // 一个状态词 + 一个温度。其余全部进调试面板。
        val (chipText, chipColorRes) = when {
            camera == null -> "相机异常" to R.color.danger
            thermalLevel == ThermalGuard.Level.PAUSED -> "已暂停识别" to R.color.danger
            thermalLevel == ThermalGuard.Level.THROTTLED -> "降载中" to R.color.warning
            else -> "值守中" to R.color.accent
        }
        binding.tvStatusPrimary.text = chipText
        binding.tvStatusSecondary.text = String.format(
            Locale.CHINA, "%.1f°C", thermalGuard.lastTemperatureC
        ).takeIf { !thermalGuard.lastTemperatureC.isNaN() } ?: ""
        binding.statusDot.background?.setTint(
            ContextCompat.getColor(this, chipColorRes)
        )

        // ---------- 调试面板：默认关闭 ----------
        // 它是仪表不是界面。做长稳验收时在设置里打开。
        if (settings.showDebugOverlay && !alarmController.isRinging) {
            binding.tvDiag.visibility = View.VISIBLE
            binding.tvDiag.text = String.format(
                Locale.US,
                "%s\n分辨率：%s\n帧率：%d fps\n夜视：%s\n识别：%s（间隔 %s）\n" +
                    "区域：%s | %s\n温度：%s | %s\n已运行：%s",
                stateText, resText, fps, lowLightSummary, detectText,
                intervalText,
                roiText, gateText,
                thermalGuard.describe(thermalLevel), lastGateSummary,
                formatDuration(uptimeSec)
            )
        } else {
            binding.tvDiag.visibility = View.GONE
        }
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
