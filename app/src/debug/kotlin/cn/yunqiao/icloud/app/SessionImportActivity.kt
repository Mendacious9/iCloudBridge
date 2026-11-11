package cn.yunqiao.icloud.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import cn.yunqiao.icloud.tools.PocSessionImporter
import java.io.File

/**
 * 只在 debug 构建里存在的「会话灌入」入口。
 *
 * 存在的理由：云盘/相册的真实联调都要先登录，而登录必须过短信 2FA ——
 * 每次改一行下载逻辑就掏一次手机、等一条验证码，代价太高。这里把本机
 * `poc/poc_session` 里那份已经验证过的会话直接写进 App 的加密存储，
 * 就能在模拟器上跑真实的列目录、下载、打开全链路。
 *
 * ```
 * adb push poc/poc_session /sdcard/Android/data/cn.yunqiao.icloud.app/files/
 * adb shell am start -n cn.yunqiao.icloud.app/.SessionImportActivity \
 *     --es apple_id you@example.com
 * ```
 *
 * 安全边界：会话只写入本机该应用的私有目录，用的是和正式流程完全相同的
 * [EncryptedSessionStore]；本类不进 release 包。
 */
class SessionImportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appleId = intent.getStringExtra("apple_id")?.trim().orEmpty()
        if (appleId.isBlank()) {
            toast("缺少 apple_id 参数")
            finish()
            return
        }
        // 会话目录：优先外部私有目录（adb 可直接 push），其次内部私有目录
        val candidates = listOfNotNull(
            intent.getStringExtra("dir")?.let { File(it) },
            getExternalFilesDir(null)?.let { File(it, "poc_session") },
            File(filesDir, "poc_session"),
            File(getExternalFilesDir(null), "session"),
        )
        val dir = candidates.firstOrNull { hasSession(it, appleId) }
        if (dir == null) {
            toast("没找到会话文件，先 push 到 files/poc_session")
            finish()
            return
        }

        val imported = PocSessionImporter.read(dir, appleId)
        if (imported == null) {
            toast("会话文件解析失败：${dir.absolutePath}")
            finish()
            return
        }

        val store = EncryptedSessionStore(this, File(filesDir, "session"))
        store.writeSession(imported.session)
        store.writeCookies(appleId, imported.cookies)

        getSharedPreferences("icloud_bridge", Context.MODE_PRIVATE).edit()
            .putString("apple_id", appleId)
            .putString("region", "CHINA")
            .apply()

        toast("会话已导入（cookie ${imported.cookies.size} 条），正在进入主界面")
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }

    private fun dirName(appleId: String) =
        appleId.filter { it.isLetterOrDigit() }.ifEmpty { "account" } + ".session.json"

    private fun hasSession(dir: File, appleId: String) =
        File(dir, dirName(appleId)).exists()

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
}
