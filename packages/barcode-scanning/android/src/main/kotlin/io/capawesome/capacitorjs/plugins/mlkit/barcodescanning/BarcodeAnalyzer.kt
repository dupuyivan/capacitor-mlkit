package io.capawesome.capacitorjs.plugins.mlkit.barcodescanning

import android.graphics.Matrix
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import androidx.camera.core.Camera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.ReaderException
import com.google.zxing.Result
import com.google.zxing.ResultPoint
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.LuminanceSource
import com.google.zxing.DecodeHintType
import com.google.zxing.BarcodeFormat
import java.nio.ByteBuffer
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicInteger

internal class BarcodeAnalyzer(
    private val plugin: BarcodeScannerPlugin,
    private val previewView: PreviewView,
    scanningRegion: JSObject?,
    private val manualLowLightMode: Boolean
) : ImageAnalysis.Analyzer {

    companion object {
        private const val ANALYSIS_WIDTH = 1280
        private const val ANALYSIS_HEIGHT = 720
        private val SUPPORTED_FORMATS = intArrayOf(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_CODE_128)
    }

    private val region = ScanningRegion.fromJsObject(scanningRegion)
    private val cropRect = Rect()
    private val yuvBytes = ByteArray(ANALYSIS_WIDTH * ANALYSIS_HEIGHT)
    private val rowBuffer = ByteArray(ANALYSIS_WIDTH * 3)
    private val floatPoints = FloatArray(8)
    private val mappedPoints = FloatArray(8)
    private val mlKitScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_CODE_128)
            .build()
    )
    private val zxingReader = MultiFormatReader().apply {
        setHints(createDecodeHints())
    }
    private val yuvSource: ReusableYuvLuminanceSource
    private val hybridBinarizer: HybridBinarizer
    private val binaryBitmap: BinaryBitmap
    private val frameCounter = AtomicInteger(0)
    private var camera: Camera? = null

    init {
        val left = region.leftPixel(ANALYSIS_WIDTH)
        val top = region.topPixel(ANALYSIS_HEIGHT)
        val width = region.widthPixel(ANALYSIS_WIDTH)
        val height = region.heightPixel(ANALYSIS_HEIGHT)
        yuvSource = ReusableYuvLuminanceSource(
            yuvBytes,
            ANALYSIS_WIDTH,
            ANALYSIS_HEIGHT,
            left,
            top,
            width,
            height,
            false
        )
        hybridBinarizer = HybridBinarizer(yuvSource)
        binaryBitmap = BinaryBitmap(hybridBinarizer)
    }

    fun bindCamera(camera: Camera?) {
        this.camera = camera
    }

    override fun analyze(image: ImageProxy) {
        val mediaImage = image.image
        if (mediaImage == null) {
            image.close()
            return
        }

        updateCropRect(image.width, image.height)
        image.cropRect = cropRect

        val yPlane = image.planes[0]
        val rowStride = yPlane.rowStride
        val width = image.width
        val height = image.height
        val buffer = yPlane.buffer
        if (rowStride * height > yuvBytes.size) {
            image.close()
            return
        }
        buffer.position(0)
        if (rowStride == width) {
            buffer.get(yuvBytes, 0, width * height)
        } else {
            var outputOffset = 0
            for (row in 0 until height) {
                buffer.get(rowBuffer, 0, rowStride)
                System.arraycopy(rowBuffer, 0, yuvBytes, outputOffset, width)
                outputOffset += width
            }
        }

        handleLowLightAndGlare(image)

        val frameNumber = frameCounter.incrementAndGet()
        if (frameNumber % 4 != 0) {
            runMlKitScan(mediaImage, image)
        } else {
            runZxingScan(image)
        }
    }

    private fun updateCropRect(imageWidth: Int, imageHeight: Int) {
        val left = region.leftPixel(imageWidth)
        val top = region.topPixel(imageHeight)
        val right = region.rightPixel(imageWidth).coerceAtMost(imageWidth)
        val bottom = region.bottomPixel(imageHeight).coerceAtMost(imageHeight)
        cropRect.set(left, top, right, bottom)
        yuvSource.reset(imageWidth, imageHeight, left, top)
    }

    private fun handleLowLightAndGlare(image: ImageProxy) {
        val ambientLux = calculateAverageLuminance(image.planes[0].buffer, image.width, image.height)
        if (ambientLux < 10.0f || manualLowLightMode) {
            camera?.cameraInfo?.torchState?.value?.let { torchState ->
                if (torchState != androidx.camera.core.TorchState.ON) {
                    camera?.cameraControl?.enableTorch(true)
                }
            }
            camera?.cameraInfo?.exposureState?.let { exposureState ->
                if (exposureState.isExposureCompensationSupported) {
                    camera?.cameraControl?.setExposureCompensationIndex(-2)
                }
            }
        }
    }

    private fun calculateAverageLuminance(buffer: ByteBuffer, width: Int, height: Int): Float {
        val position = buffer.position()
        val remaining = buffer.remaining()
        var sum = 0L
        for (index in 0 until remaining) {
            sum += buffer.get(index).toInt() and 0xFF
        }
        buffer.position(position)
        return if (remaining == 0) 255f else sum.toFloat() / remaining
    }

    private fun runMlKitScan(mediaImage: android.media.Image, imageProxy: ImageProxy) {
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        mlKitScanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isNotEmpty()) {
                    processMlKitBarcode(barcodes.first(), imageProxy)
                }
            }
            .addOnFailureListener {
                imageProxy.close()
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun processMlKitBarcode(barcode: Barcode, imageProxy: ImageProxy) {
        val rawValue = barcode.rawValue
        val boundingBox = barcode.boundingBox
        if (!rawValue.isNullOrBlank()) {
            val cornerPoints = barcode.cornerPoints?.toList() ?: barcodeBoundingBoxToPoints(boundingBox)
            dispatchResult(rawValue, barcode.formatString(), "MLKIT", cornerPoints)
            return
        }
        if (boundingBox != null && boundingBox.width() < cropRect.width() * 0.15f) {
            applyAutoZoom()
        }
        imageProxy.close()
    }

    private fun runZxingScan(imageProxy: ImageProxy) {
        try {
            hybridBinarizer.setLuminanceSource(yuvSource)
            val result = zxingReader.decode(binaryBitmap)
            val cornerPoints = zxingResultToPoints(result)
            processZxingBarcode(result, cornerPoints)
        } catch (e: ReaderException) {
            imageProxy.close()
        } finally {
            zxingReader.reset()
        }
    }

    private fun processZxingBarcode(result: Result, points: List<Point>) {
        val text = result.text
        if (text.isNullOrBlank()) {
            if (result.resultPoints?.isNotEmpty() == true) {
                applyAutoZoom()
            }
            return
        }
        dispatchResult(text, result.barcodeFormat.name, "ZXING", points)
    }

    private fun applyAutoZoom() {
        val zoomState = camera?.cameraInfo?.zoomState?.value ?: return
        val currentZoom = zoomState.linearZoom
        camera?.cameraControl?.setLinearZoom((currentZoom + 0.10f).coerceAtMost(1.0f))
    }

    private fun dispatchResult(value: String, format: String, engineUsed: String, rawPoints: List<Point>) {
        val normalizedCorners = normalizeCornerPoints(rawPoints)
        val cornersArray = JSArray()
        normalizedCorners.forEach { point ->
            cornersArray.put(point)
        }

        val payload = JSObject().apply {
            put("value", value)
            put("format", format)
            put("engineUsed", engineUsed)
            put("cornerPoints", cornersArray)
        }
        plugin.notifyListeners(BarcodeScannerPlugin.BARCODE_SCANNED_EVENT, payload)
    }

    private fun normalizeCornerPoints(rawPoints: List<Point>): List<JSObject> {
        val normalizedPoints = mutableListOf<JSObject>()
        val candidatePoints = when (rawPoints.size) {
            4 -> rawPoints
            else -> rawPoints + rawPoints.lastOrNull()?.let { listOf(it) }?.let { it } ?: rawPoints
        }

        preparePointBuffer(candidatePoints)
        previewView.outputTransform.mapPoints(mappedPoints, floatPoints)

        val width = previewView.width.toFloat().coerceAtLeast(1f)
        val height = previewView.height.toFloat().coerceAtLeast(1f)

        for (index in 0 until 4) {
            val x = (mappedPoints[index * 2] / width).coerceIn(0f, 1f)
            val y = (mappedPoints[index * 2 + 1] / height).coerceIn(0f, 1f)
            normalizedPoints.add(JSObject().apply {
                put("x", x)
                put("y", y)
            })
        }

        return normalizedPoints
    }

    private fun preparePointBuffer(points: List<Point>) {
        val effectivePoints = if (points.size >= 4) points.take(4) else generateFallbackPoints(points)
        for (index in 0 until 4) {
            floatPoints[index * 2] = effectivePoints[index].x.toFloat()
            floatPoints[index * 2 + 1] = effectivePoints[index].y.toFloat()
        }
    }

    private fun generateFallbackPoints(points: List<Point>): List<Point> {
        if (points.isEmpty()) {
            return listOf(Point(0, 0), Point(cropRect.width(), 0), Point(cropRect.width(), cropRect.height()), Point(0, cropRect.height()))
        }
        val left = points.minOf { it.x }
        val right = points.maxOf { it.x }
        val top = points.minOf { it.y }
        val bottom = points.maxOf { it.y }
        return listOf(
            Point(left, top),
            Point(right, top),
            Point(right, bottom),
            Point(left, bottom)
        )
    }

    private fun barcodeBoundingBoxToPoints(boundingBox: android.graphics.Rect?): List<Point> {
        if (boundingBox == null) {
            return listOf(Point(0, 0), Point(cropRect.width(), 0), Point(cropRect.width(), cropRect.height()), Point(0, cropRect.height()))
        }
        return listOf(
            Point(boundingBox.left, boundingBox.top),
            Point(boundingBox.right, boundingBox.top),
            Point(boundingBox.right, boundingBox.bottom),
            Point(boundingBox.left, boundingBox.bottom)
        )
    }

    private fun zxingResultToPoints(result: Result): List<Point> {
        val resultPoints = result.resultPoints ?: return barcodeBoundingBoxToPoints(null)
        val offsetX = cropRect.left
        val offsetY = cropRect.top
        if (resultPoints.size >= 4) {
            return resultPoints.take(4).map { Point(it.x.toInt() + offsetX, it.y.toInt() + offsetY) }
        }
        val left = resultPoints.minOf { it.x.toInt() } + offsetX
        val right = resultPoints.maxOf { it.x.toInt() } + offsetX
        val top = resultPoints.minOf { it.y.toInt() } + offsetY
        val bottom = resultPoints.maxOf { it.y.toInt() } + offsetY
        return listOf(
            Point(left, top),
            Point(right, top),
            Point(right, bottom),
            Point(left, bottom)
        )
    }

    private fun Barcode.formatString(): String {
        return when (this) {
            Barcode.FORMAT_QR_CODE -> "QR_CODE"
            Barcode.FORMAT_CODE_128 -> "CODE_128"
            else -> "UNKNOWN"
        }
    }

    private fun createDecodeHints(): Map<DecodeHintType, Any> {
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
        hints[DecodeHintType.TRY_HARDER] = true
        hints[DecodeHintType.POSSIBLE_FORMATS] = listOf(BarcodeFormat.QR_CODE, BarcodeFormat.CODE_128)
        return hints
    }

    internal class ReusableYuvLuminanceSource(
        private val yuvData: ByteArray,
        private var dataWidth: Int,
        private var dataHeight: Int,
        private var left: Int,
        private var top: Int,
        width: Int,
        height: Int,
        reverseHorizontal: Boolean
    ) : LuminanceSource(width, height) {

        private val matrix = ByteArray(width * height)
        private val rowBuffer = ByteArray(width)

        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val output = row?.takeIf { it.size >= width } ?: rowBuffer
            val sourceOffset = (top + y) * dataWidth + left
            System.arraycopy(yuvData, sourceOffset, output, 0, width)
            return output
        }

        override fun getMatrix(): ByteArray {
            var outputOffset = 0
            var sourceRow = top * dataWidth + left
            for (row in 0 until height) {
                System.arraycopy(yuvData, sourceRow, matrix, outputOffset, width)
                outputOffset += width
                sourceRow += dataWidth
            }
            return matrix
        }

        fun reset(dataWidth: Int, dataHeight: Int, left: Int, top: Int) {
            this.dataWidth = dataWidth
            this.dataHeight = dataHeight
            this.left = left
            this.top = top
        }
    }

    internal data class ScanningRegion(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        fun leftPixel(width: Int) = (left * width).toInt().coerceAtLeast(0)
        fun topPixel(height: Int) = (top * height).toInt().coerceAtLeast(0)
        fun rightPixel(width: Int) = (right * width).toInt().coerceAtMost(width)
        fun bottomPixel(height: Int) = (bottom * height).toInt().coerceAtMost(height)
        fun widthPixel(width: Int) = (right - left).coerceAtLeast(0f).let { (it * width).toInt().coerceAtLeast(1) }
        fun heightPixel(height: Int) = (bottom - top).coerceAtLeast(0f).let { (it * height).toInt().coerceAtLeast(1) }

        companion object {
            fun fromJsObject(region: JSObject?): ScanningRegion {
                if (region == null) {
                    return ScanningRegion(0f, 0f, 1f, 1f)
                }
                return ScanningRegion(
                    (region.getDouble("left") ?: 0.0).toFloat().coerceIn(0f, 1f),
                    (region.getDouble("top") ?: 0.0).toFloat().coerceIn(0f, 1f),
                    (region.getDouble("right") ?: 1.0).toFloat().coerceIn(0f, 1f),
                    (region.getDouble("bottom") ?: 1.0).toFloat().coerceIn(0f, 1f)
                )
            }
        }
    }
}
