package com.example.aranimation.recorder

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import io.github.sceneview.SceneView
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bộ ghi hình Native Camera 3D Video Recorder (Camera3DRecorder):
 * - Hoàn toàn KHÔNG dùng MediaProjection, KHÔNG pop-up quyền hệ thống "Bắt đầu ghi màn hình?".
 * - Bấm quay là quay ngay lập tức giống TikTok / Instagram.
 * - Video MP4 xuất ra chỉ gồm hình ảnh Camera thực tế + Mô hình 3D cử động từ captureContainer.
 * - TUYỆT ĐỐI KHÔNG DÍNH bất kỳ thành phần UI nào (không dính buttons, timer, status bar).
 * - Thu âm môi trường qua Microphone.
 * - Xuất file trực tiếp vào MediaStore Gallery (thư mục DCIM/ARVideo) kèm MediaScannerConnection.
 */
class Camera3DRecorder(
    private val context: Context,
    private val captureContainer: ViewGroup,
    private val cameraPreview: PreviewView,
    private val sceneView: SceneView
) {
    companion object {
        private const val TAG = "Camera3DRecorder"
        private const val VIDEO_MIME_TYPE = "video/mp4"
        private const val DEFAULT_BITRATE = 8_000_000 // 8 Mbps
        private const val DEFAULT_FPS = 30
        private const val FRAME_INTERVAL_MS = 1000L / DEFAULT_FPS // ~33ms
    }

    private var mediaRecorder: MediaRecorder? = null
    private var recorderSurface: Surface? = null
    private var tempVideoFile: File? = null

    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null

    /** Trạng thái ghi hình */
    var isRecording: Boolean = false
        private set

    var videoWidth: Int = 1080
    var videoHeight: Int = 1920

    private var cameraBitmap: Bitmap? = null
    private var sceneBitmap: Bitmap? = null
    private var compositeBitmap: Bitmap? = null

    /**
     * Bắt đầu ghi hình Native
     */
    fun start(enableAudio: Boolean = true): Boolean {
        if (isRecording) {
            Log.w(TAG, "Đang trong tiến trình quay.")
            return false
        }

        try {
            // Xác định độ phân giải video theo tỷ lệ kích thước thực tế của khung quay
            val viewWidth = captureContainer.width.let { if (it > 0) it else 1080 }
            val viewHeight = captureContainer.height.let { if (it > 0) it else 1920 }

            videoWidth = if (viewWidth % 2 == 0) viewWidth else viewWidth - 1
            videoHeight = if (viewHeight % 2 == 0) viewHeight else viewHeight - 1

            // Tạo file MP4 tạm trong cache của ứng dụng
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val tempFileName = "NATIVE_REC_${timeStamp}.mp4"
            tempVideoFile = File(context.cacheDir, tempFileName)

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            if (enableAudio) {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

            recorder.setOutputFile(tempVideoFile!!.absolutePath)
            recorder.setVideoEncodingBitRate(DEFAULT_BITRATE)
            recorder.setVideoFrameRate(DEFAULT_FPS)
            recorder.setVideoSize(videoWidth, videoHeight)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)

            if (enableAudio) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioEncodingBitRate(128_000)
                recorder.setAudioSamplingRate(44100)
            }

            recorder.prepare()
            mediaRecorder = recorder

            // Lấy Surface đầu vào từ MediaRecorder
            recorderSurface = recorder.surface

            recorder.start()
            isRecording = true

            // Khởi tạo các Bitmap buffer
            cameraBitmap = Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888)
            sceneBitmap = Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888)
            compositeBitmap = Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888)

            // Khởi chạy HandlerThread chạy vòng lặp bắt khung hình 30fps
            renderThread = HandlerThread("Camera3DRenderThread").apply {
                start()
                renderHandler = Handler(looper)
            }

            renderHandler?.post(frameCaptureRunnable)
            Log.d(TAG, "Camera3DRecorder khởi chạy thành công: ${videoWidth}x${videoHeight}")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi start Camera3DRecorder: ${e.message}", e)
            releaseResources()
            return false
        }
    }

    /**
     * Vòng lặp lấy khung hình từ cameraPreview và sceneView kết hợp vẽ vào recorderSurface
     */
    private val frameCaptureRunnable = object : Runnable {
        override fun run() {
            if (!isRecording) return

            val surface = recorderSurface
            val handler = renderHandler
            if (surface != null && surface.isValid && handler != null) {
                captureAndRenderCompositeFrame(surface) {
                    if (isRecording) {
                        handler.postDelayed(this, FRAME_INTERVAL_MS)
                    }
                }
            } else if (isRecording && handler != null) {
                handler.postDelayed(this, FRAME_INTERVAL_MS)
            }
        }
    }

    /**
     * Thu thập hình ảnh từ Camera Preview (TextureView/SurfaceView) và SceneView (SurfaceView)
     * rồi hòa trộn và vẽ trực tiếp vào recorderSurface
     */
    private fun captureAndRenderCompositeFrame(targetSurface: Surface, onDone: () -> Unit) {
        val camBm = cameraBitmap ?: return onDone()
        val scBm = sceneBitmap ?: return onDone()
        val compBm = compositeBitmap ?: return onDone()

        // 1. Lấy khung hình Camera Preview
        val previewChild = findCameraSurfaceOrTextureView(cameraPreview)
        if (previewChild is TextureView) {
            previewChild.getBitmap(camBm)
            // 2. Tiếp tục lấy khung hình 3D từ SceneView
            captureSceneViewFrame(scBm) {
                renderBitmapsToSurface(camBm, scBm, compBm, targetSurface)
                onDone()
            }
        } else if (previewChild is SurfaceView && previewChild.holder.surface.isValid) {
            try {
                PixelCopy.request(
                    previewChild,
                    camBm,
                    { copyResult ->
                        if (copyResult == PixelCopy.SUCCESS) {
                            captureSceneViewFrame(scBm) {
                                renderBitmapsToSurface(camBm, scBm, compBm, targetSurface)
                                onDone()
                            }
                        } else {
                            onDone()
                        }
                    },
                    renderHandler ?: Handler(Looper.getMainLooper())
                )
            } catch (e: Exception) {
                onDone()
            }
        } else {
            // Trường hợp fallback: chụp SceneView trực tiếp
            captureSceneViewFrame(scBm) {
                renderBitmapsToSurface(null, scBm, compBm, targetSurface)
                onDone()
            }
        }
    }

    /**
     * Lấy khung hình của SceneView bằng PixelCopy
     */
    private fun captureSceneViewFrame(destBitmap: Bitmap, onComplete: () -> Unit) {
        val svSurfaceView = findSurfaceView(sceneView)
        if (svSurfaceView != null && svSurfaceView.holder.surface.isValid) {
            try {
                PixelCopy.request(
                    svSurfaceView,
                    destBitmap,
                    {
                        onComplete()
                    },
                    renderHandler ?: Handler(Looper.getMainLooper())
                )
            } catch (e: Exception) {
                onComplete()
            }
        } else {
            onComplete()
        }
    }

    /**
     * Hòa trộn Camera + Mô hình 3D và khóa Canvas của recorderSurface để vẽ
     */
    private fun renderBitmapsToSurface(
        camBitmap: Bitmap?,
        modelBitmap: Bitmap?,
        compBitmap: Bitmap,
        surface: Surface
    ) {
        try {
            // Vẽ hòa trộn vào compositeBitmap
            val compCanvas = Canvas(compBitmap)

            // Vẽ Camera làm nền
            if (camBitmap != null) {
                compCanvas.drawBitmap(camBitmap, 0f, 0f, null)
            } else {
                compCanvas.drawARGB(255, 0, 0, 0)
            }

            // Vẽ Mô hình 3D đè lên
            if (modelBitmap != null) {
                compCanvas.drawBitmap(modelBitmap, 0f, 0f, null)
            }

            // Khóa Surface của MediaRecorder để ghi hình vào video stream
            val surfaceCanvas = surface.lockHardwareCanvas()
            surfaceCanvas.drawBitmap(compBitmap, 0f, 0f, null)
            surface.unlockCanvasAndPost(surfaceCanvas)

        } catch (e: Exception) {
            Log.w(TAG, "Lỗi vẽ khung hình vào recorderSurface: ${e.message}")
        }
    }

    private fun findCameraSurfaceOrTextureView(view: View): View? {
        if (view is TextureView || view is SurfaceView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findCameraSurfaceOrTextureView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun findSurfaceView(view: View): SurfaceView? {
        if (view is SurfaceView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                val found = findSurfaceView(child)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Dừng ghi hình và lưu video vào MediaStore Gallery (DCIM/ARVideo)
     */
    fun stop(onVideoSaved: (Uri?) -> Unit) {
        if (!isRecording) {
            onVideoSaved(null)
            return
        }

        isRecording = false

        renderHandler?.removeCallbacks(frameCaptureRunnable)
        renderThread?.quitSafely()
        renderThread = null
        renderHandler = null

        try {
            mediaRecorder?.stop()
        } catch (e: RuntimeException) {
            Log.w(TAG, "MediaRecorder dừng sớm hoặc lỗi: ${e.message}")
        } finally {
            releaseResources()
        }

        val targetFile = tempVideoFile
        if (targetFile != null && targetFile.exists() && targetFile.length() > 0) {
            val savedUri = exportToGallery(targetFile)
            try {
                targetFile.delete()
            } catch (_: Exception) {}
            onVideoSaved(savedUri)
        } else {
            onVideoSaved(null)
        }
    }

    /**
     * Xuất file sang MediaStore.Video.Media.EXTERNAL_CONTENT_URI (DCIM/ARVideo)
     */
    private fun exportToGallery(videoFile: File): Uri? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "AR_NATIVE_${timeStamp}.mp4"

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, VIDEO_MIME_TYPE)
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/ARVideo")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val itemUri = resolver.insert(collectionUri, contentValues) ?: return null

        return try {
            resolver.openOutputStream(itemUri)?.use { outputStream ->
                FileInputStream(videoFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)
            }

            // Kích hoạt MediaScanner để video xuất hiện ngay trong Gallery
            MediaScannerConnection.scanFile(
                context,
                arrayOf(videoFile.absolutePath),
                arrayOf(VIDEO_MIME_TYPE)
            ) { _, _ -> }

            Log.d(TAG, "Đã lưu video AR thành công: $itemUri")
            itemUri
        } catch (e: IOException) {
            Log.e(TAG, "Lỗi khi ghi file vào MediaStore: ${e.message}", e)
            resolver.delete(itemUri, null, null)
            null
        }
    }

    private fun releaseResources() {
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (_: Exception) {}

        mediaRecorder = null
        recorderSurface = null

        cameraBitmap?.recycle()
        cameraBitmap = null
        sceneBitmap?.recycle()
        sceneBitmap = null
        compositeBitmap?.recycle()
        compositeBitmap = null
    }

    /**
     * Hủy bỏ quay khẩn cấp khi onPause/onDestroy
     */
    fun cancel() {
        if (isRecording) {
            isRecording = false
            renderHandler?.removeCallbacks(frameCaptureRunnable)
            renderThread?.quitSafely()
            renderThread = null
            renderHandler = null

            try {
                mediaRecorder?.stop()
            } catch (_: Exception) {}

            releaseResources()
            tempVideoFile?.delete()
            tempVideoFile = null
        }
    }
}
