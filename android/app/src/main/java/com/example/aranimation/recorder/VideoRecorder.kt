package com.example.aranimation.recorder

import android.content.ContentValues
import android.content.Context
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import io.github.sceneview.SceneView
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiện ích ghi hình video 3D / AR từ SceneView thành tệp định dạng MP4.
 *
 * Tính năng chính:
 * 1. Thu xuất bề mặt hiển thị trực tiếp từ GPU của SceneView thông qua `startMirroring` / `stopMirroring`.
 * 2. Cấu hình MediaRecorder chuẩn: MP4 (H.264, AAC), 30 fps, bitrate 6-10 Mbps.
 * 3. Hỗ trợ ghi âm kèm theo nếu được cấp quyền `RECORD_AUDIO`.
 * 4. Tự động lưu video vào MediaStore (Thư viện Gallery) theo chuẩn Scoped Storage của Android 10+.
 */
class VideoRecorder(
    private val context: Context,
    private val sceneView: SceneView
) {
    companion object {
        private const val TAG = "VideoRecorder"
        private const val VIDEO_MIME_TYPE = "video/mp4"
        private const val DEFAULT_BITRATE = 8_000_000 // 8 Mbps
        private const val DEFAULT_FPS = 30
    }

    private var mediaRecorder: MediaRecorder? = null
    private var recordingSurface: Surface? = null
    private var tempVideoFile: File? = null

    /** Trạng thái hiện tại của quá trình ghi hình */
    var isRecording: Boolean = false
        private set

    /** Kích thước quay video mặc định hoặc đồng bộ theo SceneView */
    var videoWidth: Int = 1080
    var videoHeight: Int = 1920

    /**
     * Bắt đầu ghi hình video từ SceneView.
     *
     * @param enableAudio Có thu âm micro hay không (cần quyền RECORD_AUDIO)
     * @return true nếu bắt đầu thành công, false nếu lỗi khởi tạo
     */
    fun startRecording(enableAudio: Boolean = false): Boolean {
        if (isRecording) {
            Log.w(TAG, "Quá trình quay video đang diễn ra.")
            return false
        }

        try {
            // Xác định độ phân giải video dựa trên kích thước thực tế của SceneView
            val viewWidth = sceneView.width.let { if (it > 0) it else 1080 }
            val viewHeight = sceneView.height.let { if (it > 0) it else 1920 }

            // Làm tròn chẵn kích thước (bắt buộc cho H.264 encoder)
            videoWidth = if (viewWidth % 2 == 0) viewWidth else viewWidth - 1
            videoHeight = if (viewHeight % 2 == 0) viewHeight else viewHeight - 1

            // Tạo file tạm trong bộ nhớ cache riêng của ứng dụng
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val tempFileName = "VID_TEMP_${timeStamp}.mp4"
            tempVideoFile = File(context.cacheDir, tempFileName)

            // Khởi tạo MediaRecorder tương thích mọi phiên bản Android
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

            // Lấy Input Surface từ MediaRecorder
            val surface = recorder.surface
            recordingSurface = surface

            // Gắn Surface vào SceneView thông qua API startMirroring của Filament engine
            sceneView.startMirroring(
                surface = surface,
                left = 0,
                bottom = 0,
                width = videoWidth,
                height = videoHeight
            )

            // Bắt đầu ghi
            recorder.start()
            isRecording = true
            Log.d(TAG, "Bắt đầu quay video thành công: ${videoWidth}x${videoHeight}")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi bắt đầu quay video: ${e.message}", e)
            releaseRecorder()
            return false
        }
    }

    /**
     * Dừng ghi hình và tự động lưu video vào MediaStore (Gallery).
     *
     * @param onVideoSaved Callback trả về Uri của video trong Thư viện ảnh (hoặc null nếu thất bại)
     */
    fun stopRecording(onVideoSaved: (Uri?) -> Unit) {
        if (!isRecording) {
            Log.w(TAG, "Không có tiến trình quay nào đang chạy.")
            onVideoSaved(null)
            return
        }

        try {
            // Ngừng mirror hình ảnh từ SceneView
            recordingSurface?.let { surface ->
                try {
                    sceneView.stopMirroring(surface)
                } catch (e: Exception) {
                    Log.w(TAG, "Lỗi khi gọi stopMirroring: ${e.message}")
                }
            }

            // Dừng MediaRecorder an toàn
            try {
                mediaRecorder?.stop()
            } catch (e: RuntimeException) {
                Log.w(TAG, "MediaRecorder dừng đột ngột hoặc thời lượng quá ngắn: ${e.message}")
            }
        } finally {
            releaseRecorder()
            isRecording = false
        }

        // Xuất file tạm sang Thư viện MediaStore công khai
        val targetFile = tempVideoFile
        if (targetFile != null && targetFile.exists() && targetFile.length() > 0) {
            val savedUri = exportToGallery(targetFile)
            // Xóa file tạm sau khi đã export
            try {
                targetFile.delete()
            } catch (_: Exception) {}
            onVideoSaved(savedUri)
        } else {
            Log.e(TAG, "Tệp video tạm không tồn tại hoặc có dung lượng 0 byte.")
            onVideoSaved(null)
        }
    }

    /**
     * Xuất tệp MP4 sang MediaStore.Video để hiển thị ngay trong Gallery.
     */
    private fun exportToGallery(videoFile: File): Uri? {
        val fileName = "AR_3D_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, VIDEO_MIME_TYPE)
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/3DAnimalViewer")
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

            // Gỡ cờ IS_PENDING khi ghi xong để ứng dụng khác thấy ngay video
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)
            }

            Log.d(TAG, "Đã lưu video thành công vào MediaStore: $itemUri")
            itemUri
        } catch (e: IOException) {
            Log.e(TAG, "Lỗi khi ghi dữ liệu sang MediaStore: ${e.message}", e)
            resolver.delete(itemUri, null, null)
            null
        }
    }

    /**
     * Dọn dẹp và giải phóng tài nguyên MediaRecorder
     */
    private fun releaseRecorder() {
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi giải phóng mediaRecorder: ${e.message}")
        } finally {
            mediaRecorder = null
            recordingSurface = null
        }
    }

    /**
     * Hủy bỏ quay khẩn cấp (dành cho onPause / onDestroy)
     */
    fun cancelRecording() {
        if (isRecording) {
            try {
                recordingSurface?.let { sceneView.stopMirroring(it) }
                mediaRecorder?.stop()
            } catch (_: Exception) {
            } finally {
                releaseRecorder()
                isRecording = false
                tempVideoFile?.delete()
                tempVideoFile = null
            }
        }
    }
}
