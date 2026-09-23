package com.muxuechat.domain.rp

/**
 * 词条名的归一化与数量上限，Repository 落库与编辑器内联校验共用同一口径。
 *
 * 编辑器若按自己的规则判重，会出现「界面上不重名、保存时却因归一化后重名而报错」
 * （例如两条只差首尾空格或换行），因此判重必须先走 [normalizeName]。
 */
object RpEntryRules {
    const val MAX_NAME_LENGTH = 80

    /** 每份档案最多的数值词条数。超出部分 Repository 会丢弃，编辑器在达到上限时禁用添加。 */
    const val MAX_NUMERIC_ENTRIES = 12

    private val NEWLINE_REGEX = Regex("[\\r\\n]+")

    fun normalizeName(raw: String): String =
        raw.trim().replace(NEWLINE_REGEX, " ").take(MAX_NAME_LENGTH)
}
