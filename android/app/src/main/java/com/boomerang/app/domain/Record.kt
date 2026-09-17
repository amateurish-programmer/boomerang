package com.boomerang.app.domain

data class RecordContent(
    val recordType: String = "FLAG",
    val originalText: String = "",
    val subject: String = "我",
    val topic: String = "",
    val lifecycle: String = "ACTIVE",
    val confirmedStatus: String? = null,
    val saidAt: String? = null,
    val dueStart: String? = null,
    val dueEnd: String? = null,
    val dateText: String = "",
    val datePrecision: String = "UNKNOWN",
    val timezone: String = "Asia/Shanghai",
    val verificationCriteria: String = "",
    val notes: String = "",
    val capsuleLockedAt: String? = null,
    val capsuleUnlockAt: String? = null,
)

data class SourceInput(val title: String = "", val url: String = "")

val recordTypes = linkedMapOf("FLAG" to "目标", "PROMISE" to "承诺", "PREDICTION" to "预测", "STATEMENT" to "言论", "MILESTONE" to "里程碑")
val resultStatuses = linkedMapOf("FULFILLED" to "已达成", "BROKEN" to "未达成", "PARTIAL" to "部分达成", "DISPUTED" to "有争议", "UNVERIFIABLE" to "无法验证", "REVISED" to "已修正")
val datePrecisions = linkedMapOf("UNKNOWN" to "未知", "DAY" to "某一天", "MONTH" to "整月", "QUARTER" to "季度", "YEAR" to "全年", "RANGE" to "日期区间")
