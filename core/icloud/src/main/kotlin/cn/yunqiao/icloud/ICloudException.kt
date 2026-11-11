package cn.yunqiao.icloud

/**
 * iCloud 桥接层的统一异常体系。
 *
 * 设计目标：每一层抛出的异常都必须携带「用户能看懂的中文原因 + 开发者能定位的技术细节」，
 * 因为安卓端的错误提示直接来自这里，不希望 UI 层再去猜 401/409 的语义。
 */
sealed class ICloudException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** 网络层失败（DNS、超时、连接中断），可重试 */
class ICloudNetworkException(
    message: String,
    cause: Throwable? = null,
) : ICloudException(message, cause)

/** 认证失败：账号密码错误、验证码错误、会话失效 */
class ICloudAuthException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : ICloudException(message, cause)

/** 服务端返回了非 2xx，且不属于认证语义 */
class ICloudApiException(
    val statusCode: Int,
    val body: String,
    message: String = "iCloud 接口返回 HTTP $statusCode",
) : ICloudException(message)

/** 服务不可用：照片/云盘未开启、高级数据保护冲突等 */
class ICloudServiceException(message: String) : ICloudException(message)

/** 账户要求双重认证（需要 UI 跳转到验证码页） */
class ICloudTwoFactorRequiredException(
    message: String = "账户要求双重认证",
) : ICloudException(message)

/**
 * Apple 把账户迁到了另一个区域域（国际站 <-> 云上贵州），需要切换域名重试。
 */
class ICloudRegionMismatchException(
    val domainToUse: String,
    message: String = "Apple 要求使用域名 $domainToUse",
) : ICloudException(message)

/** 数据格式与预期不符（苹果改了协议结构） */
class ICloudProtocolException(message: String, cause: Throwable? = null) :
    ICloudException(message, cause)

/**
 * 苹果拒收这个媒体文件：类型不在白名单，或视频编码无法转码。
 *
 * 单独成一类是因为这不是 bug：用户的文件确实传不上去，重试一百次也一样。
 * UI 应该直接告诉他「换个格式/编码」，而不是给一个「上传失败，请重试」。
 */
class ICloudUnsupportedMediaException(
    message: String,
    val fileName: String? = null,
    val detail: String? = null,
) : ICloudException(message)

