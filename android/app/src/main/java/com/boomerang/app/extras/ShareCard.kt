package com.boomerang.app.extras

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.boomerang.app.data.RecordDetail
import com.boomerang.app.domain.resultStatuses
import java.io.File
import java.util.UUID

object ShareCard {
    fun text(detail: RecordDetail): String {
        val c = detail.record.content
        val claim = c.originalText.take(700) + if (c.originalText.length > 700) "…（原话节选）" else ""
        val status = c.confirmedStatus?.let { "用户确认：${resultStatuses[it] ?: it}" } ?: "尚未由用户确认结果"
        val sources = detail.sources.take(3).joinToString("\n") { "${it.title.take(70)}\n${it.url.take(160)}" }.ifBlank { "尚未附来源" }
        // Notes are deliberately excluded from the share format.
        return "回旋镖\n\n$claim\n\n${c.subject}\n说出日期：${c.saidAt ?: "未知"}\n截止日期：${c.dueEnd ?: "未设期限"}\n\n$status\n本卡不包含 AI 建议\n\n来源简述\n$sources"
    }
    fun render(context: Context, detail: RecordDetail): File {
        val paint = TextPaint().apply { color = Color.rgb(28, 46, 44); textSize = 36f; isAntiAlias = true; typeface = Typeface.DEFAULT }
        val content = text(detail)
        val layout = StaticLayout.Builder.obtain(content, 0, content.length, paint, 880)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(10f, 1f).setIncludePad(true).build()
        val bitmap = Bitmap.createBitmap(1080, layout.height + 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.rgb(242, 246, 239)); canvas.translate(100f, 100f); layout.draw(canvas)
        val directory = File(context.cacheDir, "share_cards").apply { mkdirs() }
        val file = File(directory, "boomerang-${UUID.randomUUID()}.png")
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return file
    }
    fun intent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        return Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = android.content.ClipData.newRawUri("回旋卡", uri) }
    }
}
