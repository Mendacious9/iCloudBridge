package cn.yunqiao.icloud.app

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/**
 * 界面语言。
 *
 * [SYSTEM] 是默认值：不写任何语言覆盖，让 Android 按系统语言自己挑 `values-*` 资源。
 * 这正是「默认看设备系统语言」的实现方式 —— 不要自己去读 Locale 再匹配，
 * 资源系统本来就做得更好（包含区域回退、RTL 等）。
 */
enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    EN("en"),
    ZH_CN("zh-CN"),
    ZH_TW("zh-TW"),
    JA("ja"),
    KO("ko"),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}

/**
 * 语言覆盖。
 *
 * 不用 AppCompatDelegate.setApplicationLocales：那需要引入 appcompat 并注册
 * `AppLocalesMetadataHolderService`，而本应用是纯 Compose。这里用最朴素也最可靠的做法 ——
 * 在 Activity 的 `attachBaseContext` 里换一份 Configuration，改完 `recreate()` 即可生效。
 */
object LocaleSupport {

    private const val PREFS = "icloud_bridge"
    private const val KEY_LANGUAGE = "app_language"

    fun current(base: Context): AppLanguage =
        AppLanguage.fromTag(prefs(base).getString(KEY_LANGUAGE, "") ?: "")

    fun set(base: Context, language: AppLanguage) {
        prefs(base).edit().putString(KEY_LANGUAGE, language.tag).apply()
    }

    /** 未选择语言时原样返回，交给系统资源匹配 */
    fun wrap(base: Context): Context {
        val tag = prefs(base).getString(KEY_LANGUAGE, "") ?: ""
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        // 同步默认 Locale：格式化日期/大小时不会和界面语言打架
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            config.setLayoutDirection(locale)
        }
        return base.createConfigurationContext(config)
    }

    private fun prefs(base: Context) =
        base.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
