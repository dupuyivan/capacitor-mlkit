package io.capawesome.capacitorjs.plugins.mlkit.barcodescanning

import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.getcapacitor.JSObject
import java.util.concurrent.Executor

internal class CustomScannerPlugin(private val plugin: BarcodeScannerPlugin) {

    private var processCameraProvider: ProcessCameraProvider? = null
    private var previewView: PreviewView? = null
    private var camera: Camera? = null
    private var analyzerThread: HandlerThread? = null
    private var analyzerHandler: Handler? = null
    private var barcodeAnalyzer: BarcodeAnalyzer? = null

    fun startScan(
        scanSettings: ScanSettings,
        scanningRegion: JSObject?,
        manualLowLightMode: Boolean,
        callback: StartScanResultCallback
    ) {
        stopScan()
        hideWebViewBackground()

        analyzerThread = HandlerThread("BarcodeAnalyzerThread").apply { start() }
        analyzerHandler = Handler(analyzerThread!!.looper)

        val context = plugin.getContext()
        val lifecycleOwner = when {
            plugin.getActivity() is LifecycleOwner -> plugin.getActivity() as LifecycleOwner
            context is LifecycleOwner -> context
            else -> null
        }

        if (lifecycleOwner == null) {
            callback.error(Exception("Cannot bind camera because LifecycleOwner is unavailable."))
            return
        }

        previewView = PreviewView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            setBackgroundColor(Color.BLACK)
        }

        val parent = plugin.getBridge().getWebView().parent
        if (parent is ViewGroup) {
            parent.addView(previewView, 0)
        } else {
            callback.error(Exception("Unable to attach camera preview to WebView parent."))
            return
        }

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView!!.surfaceProvider)
        }

        barcodeAnalyzer = BarcodeAnalyzer(
            plugin = plugin,
            previewView = previewView!!,
            scanningRegion = scanningRegion,
            manualLowLightMode = manualLowLightMode
        )

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(Size(1280, 720))
            .build()

        imageAnalysis.setAnalyzer(Executor { runnable -> analyzerHandler?.post(runnable) }, barcodeAnalyzer!!)

        val cameraSelector = CameraSelector.Builder().requireLensFacing(scanSettings.lensFacing).build()

        ProcessCameraProvider.getInstance(context).addListener(
            {
                try {
                    processCameraProvider = ProcessCameraProvider.getInstance(context).get()
                    processCameraProvider?.unbindAll()

                    camera = processCameraProvider?.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )

                    barcodeAnalyzer?.bindCamera(camera)
                    callback.success()
                } catch (exception: Exception) {
                    callback.error(exception)
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    fun stopScan() {
        try {
            camera?.cameraControl?.enableTorch(false)
        } catch (ignored: Exception) {
        }

        processCameraProvider?.unbindAll()
        processCameraProvider = null
        camera = null
        barcodeAnalyzer = null

        analyzerHandler?.removeCallbacksAndMessages(null)
        analyzerHandler = null
        analyzerThread?.quitSafely()
        analyzerThread = null

        previewView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        previewView = null

        showWebViewBackground()
    }

    private fun hideWebViewBackground() {
        plugin.getBridge().getWebView().setBackgroundColor(Color.TRANSPARENT)
    }

    private fun showWebViewBackground() {
        plugin.getBridge().getWebView().setBackgroundColor(Color.WHITE)
    }
}
