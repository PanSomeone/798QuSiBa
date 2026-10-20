package com.killhuishenghuo.ui.settings

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.killhuishenghuo.core.log.AppLog
import java.io.File

/**
 * 把收款码存下来。
 *
 * 理由很具体:**一个人只有一台手机的时候,屏幕上的码自己扫不到**。先存进相册,
 * 再用另一台设备(或者网页端的「从相册选择」)读它 —— 两端的动作正好接上。
 *
 * Android 10 起写自己的媒体库条目不需要任何权限;更早的版本要么申请存储权限
 * (为一张图索要相册写权限不值),要么走分享面板 —— 那里面本来就有「保存到相册」。
 * 所以这里分两条路,返回一句话给用户看。
 */
object SponsorSave {

    /** 保存成功返回一句给用户看的话;任何失败都只降级,不抛。 */
    fun save(context: Context, drawableId: Int, fileName: String): String {
        val bitmap = BitmapFactory.decodeResource(context.resources, drawableId)
            ?: return "这张图读不出来"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToGallery(context, bitmap, fileName)
        } else {
            share(context, bitmap, fileName)
        }
    }

    private fun saveToGallery(context: Context, bitmap: Bitmap, fileName: String): String {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/惠生活")
            // 先占位再写完:不然相册可能在写一半时就把它索引进去,打开是半张图。
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = try {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            null
        } ?: return "相册没有接受这张图，可以截图保存"

        return try {
            val out = resolver.openOutputStream(uri) ?: return "写不进相册"
            out.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            AppLog.log("Sponsor", "saved to gallery: $fileName")
            "已保存到相册"
        } catch (e: Exception) {
            AppLog.log("Sponsor", "gallery save failed ${e.javaClass.simpleName}")
            "保存失败，可以截图"
        }
    }

    /** Android 9 及以下:写进自己的缓存目录,交给分享面板。 */
    private fun share(context: Context, bitmap: Bitmap, fileName: String): String = try {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, fileName)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }

        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "保存或发送收款码")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

        AppLog.log("Sponsor", "shared: $fileName")
        "已打开分享，可以存到相册"
    } catch (e: Exception) {
        AppLog.log("Sponsor", "share failed ${e.javaClass.simpleName}")
        "分享失败，可以截图"
    }
}
