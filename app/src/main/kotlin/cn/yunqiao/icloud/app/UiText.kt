package cn.yunqiao.icloud.app

import android.content.Context
import androidx.annotation.StringRes

/**
 * 可翻译的界面文案。
 *
 * 存在的理由很实际：错误信息原本是 ViewModel 里硬编码的中文字符串，
 * 一旦用户把语言切成英文，界面翻译了、错误提示还是中文，等于没做国际化。
 * 现在 ViewModel 只产出「资源 id + 参数」，真正取字符串交给 UI 层的 [resolve]。
 *
 * 协议层抛上来的原始消息（苹果返回的英文错误、HTTP 状态）保留为 [Raw]，
 * 作为参数拼进本地化模板，既不丢诊断信息，也不会整句英文怼到用户脸上。
 */
sealed interface UiText {

    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    data class Raw(val value: String) : UiText

    fun resolve(context: Context): String = when (this) {
        is Res -> if (args.isEmpty()) {
            context.getString(id)
        } else {
            context.getString(id, *args.toTypedArray())
        }

        is Raw -> value
    }
}

fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())
