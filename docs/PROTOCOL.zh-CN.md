# 协议笔记

协议分析过程中踩出来的结论，按「照做就能复现」的粒度写。换实现（或移植到别的语言）时，
这部分比代码更有价值。

## 上传

照片与云盘是**两套完全不同的协议**，入口也分开，不要混用。

**照片 → iCloud 照片图库**（`photosupload` 服务，三步）：

1. `POST {photosupload}/photosupload/createUploadUrl`
   body `{zoneName:"PrimarySync", assets:{<uuid>:<bytes>}}` → `{uploadUrls:{<uuid>:<url>}}`
2. `POST <url>` 原样写字节。该地址在 `cws.icloud-content.com.cn` 上，自带 token，
   **不能带 `Content-Type: application/json`**
3. `POST {photosupload}/photosupload/putAsset`
   `{zoneName, files:[{fileName, lastModDate, timeZoneOffset, singleFileUploadRequest:<receipt>}],
     localTimeZoneId, importGroup}` → `[{uploadJobId, cplMaster, cplAsset, response:{status}}]`

坑：

- `timeZoneOffset` 用 JS 的 `getTimezoneOffset` 约定（UTC 减本地），东八区发 **-480**；
- **登记失败不会体现在 HTTP 状态上**，外层永远 200，结果在数组元素的 `response.status` 里。
  不校验它，视频失败会被静默吞掉（本工程踩过：415 `uploaded media is unsupported for transcoding`）；
- 415 = 苹果拒收：照片要 JPEG/HEIC/PNG，视频编码必须可转码（H.264/HEVC），
  换成 `ICloudUnsupportedMediaException` 让 UI 提示「换格式」而不是「重试」；
- 登记成功 ≠ 立刻可查，CloudKit 还要索引几秒，所以上传完会 `delay(2500)` 再刷新。

**云盘 → iCloud 云盘**（`docws` 服务，三步）：

1. `POST {docws}/ws/com.apple.CloudDocs/upload/web` `{filename,type:"FILE",content_type,size}`
   → `[{document_id, url}]`
2. `POST <url>` 写字节 → `{singleFile:{fileChecksum, wrappingKey, referenceChecksum, receipt, size}}`
3. `POST {docws}/ws/com.apple.CloudDocs/update/documents`，`command:"add_file"`，
   `path.starting_document_id` = 目标文件夹 docwsid（根目录就是字面量 `root`）

坑：

- 少了 `command:"add_file"` 只会回 `Unexpected input at column:56`，文件成为孤儿；
- 列表接口把扩展名单独放在 `extension` 字段，`name` 只有主名 —— 不拼回去，列表少后缀、
  下载存盘也少后缀，文件双击打不开（`DriveApi.toEntry` 已处理，并有单测）；
- 删除走 `update/documents` + `command:"delete"`，不是 `docws/delete`（那个返回 405）。

## 实况照片的识别

`itemType` 是 `public.heic`，同时带 `resVidSmallRes` / `resVidMedRes`，
且 `duration = 0` —— 真实动态时长在 `vidComplDurValue / vidComplDurScale`
（例如 1720/600 ≈ 2.9 秒）。所以判定顺序必须是：

1. `itemType` 含 `movie` / `mpeg` / `video` / `quicktime` / `avi` → 视频；
2. `itemType` 以 `public.` / `com.adobe.` 开头 → 照片（可能是实况）；
3. `itemType` 缺失时才退回「有没有视频资源」这种猜测。

只按「有没有 `resVid*`」判断，会把实况照片当成 `duration=0` 的视频，
网格上就会出现「有的视频有 00:00 时长角标、有的没有」这种怪现象。

## 播放器

- **不要用 `SurfaceView`**：放进 `graphicsLayer` / 离屏层会黑屏；铺满窗口会在图层上
  「挖洞」，同层的 Compose 控件被盖住；`screencap` 也只能拍到黑（要用 `screenrecord` 抽帧）。
- 用 **`TextureView`**：普通 View，参与正常合成，可以和 Compose 叠、可以截屏。
- 系统 `MediaController` 塞进 `AndroidView` 会定位错乱并吞掉整块触摸，所以控制条是自绘的。
- 播放结束后 `onCompletion` 要把按钮切回三角、并回退一帧，否则停在黑屏；
  拖到末尾要夹到 `durationMs - 50`。

## 会话恢复

`POST {setup}/validate` 即使返回 401，`X-APPLE-WEBAUTH-TOKEN` Cookie 往往还活着
（有效期约 30 天），可以直接拿 Cookie 重新 `accountLogin` 恢复会话 ——
这是「重启 App 不用重新登录」的关键。
