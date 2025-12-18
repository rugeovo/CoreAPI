package org.ruge.coreapi.lang

import taboolib.module.configuration.Config
import taboolib.module.configuration.Configuration
import taboolib.common.platform.function.warning

/**
 * 语言文件管理器
 * 负责加载和管理所有本地化消息
 */
object LanguageManager {

    @Config("lang.yml", autoReload = true)
    lateinit var lang: Configuration
        private set

    /**
     * 获取本地化消息
     * @param key 消息键，使用点号分隔（如 "startup.loading"）
     * @param args 参数列表，将替换消息中的 {0}, {1}, {2} 等占位符
     * @return 格式化后的本地化消息，如果键不存在则返回键名本身
     */
    fun getMessage(key: String, vararg args: Any): String {
        val template = lang.getString(key) ?: run {
            warning("语言文件缺少消息键: $key")
            return key
        }

        if (args.isEmpty()) return template

        return args.foldIndexed(template) { index, acc, arg ->
            acc.replace("{$index}", arg.toString())
        }
    }
}
