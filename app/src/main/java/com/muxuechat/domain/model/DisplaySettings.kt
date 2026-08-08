package com.muxuechat.domain.model

/**
 * 显示偏好。
 *
 * 与聊天/模型相关的 [ChatSettings] 解耦，不发送给 DeepSeek，仅影响 UI。
 * 当前只暴露一档"显示大小"，未来如需"行距""字体家族"等再扩展。
 */
data class DisplaySettings(
    val displayScale: DisplayScale = DisplayScale.DEFAULT,
)

/**
 * 显示大小档位。
 *
 * 倍率会乘到 Material 3 Typography 的 fontSize / lineHeight 上，
 * 由于 sp 本身已经跟随 Android 系统字体大小，App 层只是叠加偏好倍率。
 *
 * 不放大 dp 常量（图标尺寸、边框、圆角、44dp 触控目标），保持视觉骨架稳定。
 */
enum class DisplayScale(
    val multiplier: Float,
    val label: String,
    val storageKey: String,
) {
    COMPACT_XS(0.80f, "极紧凑", "compact_xs"),
    COMPACT(0.90f, "紧凑", "compact"),
    DEFAULT(1.00f, "默认", "default"),
    COMFORTABLE(1.12f, "舒适", "comfortable");

    companion object {
        fun fromStorageKey(value: String?): DisplayScale =
            entries.firstOrNull { it.storageKey == value } ?: DEFAULT
    }
}
