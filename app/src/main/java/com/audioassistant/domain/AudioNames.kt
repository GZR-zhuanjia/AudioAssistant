package com.audioassistant.domain

/** Names entered by the user never need a container suffix. Actual file types
 * are determined by the completed media, not by anything typed in a name box. */
object AudioNames {
    private val suffix = Regex("(?i)(?:\\.(?:mp3|mp2|m4a|m4b|aac|ac3|eac3|wav|flac|ogg|opus|aiff|aif|caf|mp4|m4v|mov|mkv|mka|webm|avi|3gp|mpeg|mpg|ts|mts|m2ts|vob))+$")
    private val invalid = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    fun suggested(value: String): String {
        val clean = value.trim().replace(suffix, "").replace(invalid, "_").trim().trim('.')
        return StringBuilder().apply { clean.codePoints().limit(50).forEach { appendCodePoint(it) } }
            .toString().ifBlank { "我的音频" }
    }

    fun validate(value: String): String {
        val name = value.trim().replace(suffix, "").trim()
        require(name.isNotBlank() && name.any { it != '.' }) { "请输入一个名称" }
        require(!invalid.containsMatchIn(name)) { "名称中不能包含斜线、冒号等特殊符号" }
        require(name.codePointCount(0, name.length) <= 50) { "名称请控制在 50 个字以内" }
        return name
    }
}
