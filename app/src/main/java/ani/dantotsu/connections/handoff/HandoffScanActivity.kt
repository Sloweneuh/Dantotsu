package ani.dantotsu.connections.handoff

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityHandoffScanBinding
import ani.dantotsu.initActivity
import ani.dantotsu.loadImage
import ani.dantotsu.navBarHeight
import ani.dantotsu.snackString
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Scans another device's handoff QR code with the camera. On a successful scan it decodes the
 * [HandoffPayload], shows what was received (cover/title/progress), and lets the user open the
 * media — resuming the exact chapter/episode via [HandoffNavigator].
 *
 * Uses CameraX for the preview/frames and ZXing to decode; no network or account involved.
 */
class HandoffScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHandoffScanBinding
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var handled = false

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else {
                snackString(getString(R.string.handoff_camera_permission_needed))
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager(this).applyTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityHandoffScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)

        binding.scanClose.setOnClickListener { finish() }
        binding.scanClose.translationY = statusBarHeight.toFloat()
        binding.scanResult.updatePadding(bottom = binding.scanResult.paddingBottom + navBarHeight)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera()
        else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            cameraProvider = provider
            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(binding.scanPreview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, QrAnalyzer { onScanned(it) }) }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            }.onFailure {
                snackString(getString(R.string.handoff_camera_unavailable))
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Called on the analysis thread for every decoded QR; ignores non-handoff codes. */
    private fun onScanned(text: String) {
        if (handled) return
        val uri = Uri.parse(text)
        val payload = HandoffPayload.fromDeepLink(uri) ?: return
        handled = true
        val code = uri.getQueryParameter(HandoffPayload.QUERY_CODE)
        if (code != null) {
            // Upgrade to the full payload (exact source entry) from the cloud; fall back to the
            // QR's embedded payload if it's unavailable. CloudHandoff callbacks are on the main thread.
            CloudHandoff.fetch(code, consume = false) { full -> showResult(full ?: payload) }
        } else runOnUiThread { showResult(payload) }
    }

    private fun showResult(payload: HandoffPayload) {
        // Freeze the camera while the result is shown; "scan again" rebinds it.
        runCatching { cameraProvider?.unbindAll() }
        binding.scanHint.isVisible = false
        binding.scanResultFrom.text = getString(R.string.handoff_incoming_from, payload.senderName)
        binding.scanResultTitle.text = payload.title
        binding.scanResultCover.loadImage(payload.cover)
        val info = progressLine(payload)
        binding.scanResultInfo.isVisible = info != null
        binding.scanResultInfo.text = info
        binding.scanResult.isVisible = true

        binding.scanOpen.setOnClickListener {
            binding.scanOpen.isEnabled = false
            lifecycleScope.launch {
                HandoffNavigator.navigate(this@HandoffScanActivity, payload)
                finish()
            }
        }
        binding.scanAgain.setOnClickListener {
            binding.scanResult.isVisible = false
            binding.scanHint.isVisible = true
            handled = false
            startCamera()
        }
    }

    /** "Vol. 4 Ch. 20 • Page 8" / "Episode 5 • 12:34"; number shown verbatim (source labels it). */
    private fun progressLine(payload: HandoffPayload): String? {
        val number = payload.number?.takeIf { payload.hasProgress } ?: return null
        return if (payload.isAnime) {
            payload.positionMs?.takeIf { it > 0 }?.let { "$number • ${formatTime(it)}" } ?: number
        } else {
            payload.page?.takeIf { it > 0 }
                ?.let { "$number • ${getString(R.string.handoff_page_label, it.toString())}" } ?: number
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { cameraProvider?.unbindAll() }
        analysisExecutor.shutdown()
    }

    /** Decodes QR codes from CameraX frames using ZXing's Y-plane luminance. */
    private class QrAnalyzer(private val onFound: (String) -> Unit) : ImageAnalysis.Analyzer {
        private val reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }

        override fun analyze(image: ImageProxy) {
            try {
                val plane = image.planes[0]
                val data = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
                val source = PlanarYUVLuminanceSource(
                    data, plane.rowStride, image.height,
                    0, 0, image.width, image.height, false
                )
                val result = runCatching {
                    reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
                }.getOrNull()
                result?.text?.let(onFound)
            } catch (_: Exception) {
                // Frame wasn't decodable; the next one will be tried.
            } finally {
                reader.reset()
                image.close()
            }
        }
    }
}
