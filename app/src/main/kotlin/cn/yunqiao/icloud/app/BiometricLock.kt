package cn.yunqiao.icloud.app

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * 生物识别解锁。
 *
 * 定位要说清楚：它锁的是**本地已保存的会话**，不是替代苹果的双重认证。
 * 会话令牌已经在设备上，指纹/人脸只是防止「手机被人拿走就能看照片」。
 *
 * 能力选择 `BIOMETRIC_WEAK | DEVICE_CREDENTIAL`：
 * - WEAK 才能覆盖 2D 人脸（很多安卓机只有这一档，用 STRONG 会把人脸用户挡在门外）；
 * - 带上 DEVICE_CREDENTIAL 后，指纹坏了还能用锁屏密码进，不至于把自己锁死。
 *
 * 注意：一旦允许设备凭据，`setNegativeButtonText` 就不允许再设置，否则 authenticate 直接抛异常。
 */
object BiometricLock {

    private const val ALLOWED =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** 设备是否具备可用条件（已录指纹/人脸，或至少设了锁屏密码） */
    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(ALLOWED) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(
        activity: FragmentActivity,
        title: String,
        onSuccess: () -> Unit,
        onFailure: (message: String?) -> Unit,
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onFailure(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    // 单次指纹不匹配：系统会自己让用户重试，这里不打断，也不弹错误
                }
            },
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(ALLOWED)
            .build()

        prompt.authenticate(info)
    }
}
