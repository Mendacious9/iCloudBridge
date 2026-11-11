package cn.yunqiao.icloud.photos

/**
 * 照片 CloudKit 查询常量。
 *
 * [DESIRED_KEYS] 必须完整声明。苹果的 Hyperion 索引接口对字段列表是「白名单式校验」：
 * 漏掉任何一个历史上出现过的键，都会直接返回 `BAD_REQUEST: Unexpected input`，
 * 而且错误信息完全不提示是哪个字段的问题 —— 这是 M0 阶段踩过的最大坑。
 */
object PhotoQuery {

    /** 照片库与所有用户相册都在同一个 zone 里 */
    const val LIBRARY_ZONE = "PrimarySync"

    /** 照片库全量（不含隐藏/已删除） */
    const val RECORD_LIBRARY = "CPLAssetAndMasterByAssetDateWithoutHiddenOrDeleted"

    /**
     * 用户相册内的资产查询。
     *
     * 注意：用户相册**不是**独立的 CloudKit zone（实测按相册 recordName 查会返回
     * ZONE_NOT_FOUND），而是在 PrimarySync 里用「容器关系索引」过滤：
     * recordType 用本常量，并附加 `parentId == 相册 recordName` 的过滤条件。
     */
    const val RECORD_ALBUM_RELATION = "CPLContainerRelationLiveByAssetDate"

    /** 相册列表 */
    const val RECORD_ALBUMS = "CPLAlbumByPositionLive"

    /** 索引状态（刚开启 iCloud 照片时库还在构建，查询会返回空） */
    const val RECORD_INDEXING = "CheckIndexingState"

    /** 总数统计（内部接口） */
    const val RECORD_COUNT = "HyperionIndexCountLookup"

    val DESIRED_KEYS: List<String> = listOf(
        "resJPEGFullWidth", "resJPEGFullHeight", "resJPEGFullFileType",
        "resJPEGFullFingerprint", "resJPEGFullRes",
        "resJPEGLargeWidth", "resJPEGLargeHeight", "resJPEGLargeFileType",
        "resJPEGLargeFingerprint", "resJPEGLargeRes",
        "resJPEGMedWidth", "resJPEGMedHeight", "resJPEGMedFileType",
        "resJPEGMedFingerprint", "resJPEGMedRes",
        "resJPEGThumbWidth", "resJPEGThumbHeight", "resJPEGThumbFileType",
        "resJPEGThumbFingerprint", "resJPEGThumbRes",
        "resVidFullWidth", "resVidFullHeight", "resVidFullFileType",
        "resVidFullFingerprint", "resVidFullRes",
        "resVidMedWidth", "resVidMedHeight", "resVidMedFileType",
        "resVidMedFingerprint", "resVidMedRes",
        "resVidSmallWidth", "resVidSmallHeight", "resVidSmallFileType",
        "resVidSmallFingerprint", "resVidSmallRes",
        "resSidecarWidth", "resSidecarHeight", "resSidecarFileType",
        "resSidecarFingerprint", "resSidecarRes",
        "itemType", "dataClassType", "filenameEnc", "originalOrientation",
        "resOriginalWidth", "resOriginalHeight", "resOriginalFileType",
        "resOriginalFingerprint", "resOriginalRes",
        "resOriginalAltWidth", "resOriginalAltHeight", "resOriginalAltFileType",
        "resOriginalAltFingerprint", "resOriginalAltRes",
        "resOriginalVidComplWidth", "resOriginalVidComplHeight",
        "resOriginalVidComplFileType", "resOriginalVidComplFingerprint",
        "resOriginalVidComplRes",
        "isDeleted", "isExpunged", "dateExpunged", "remappedRef",
        "recordName", "recordType", "recordChangeTag", "masterRef",
        "adjustmentRenderType", "assetDate", "addedDate", "isFavorite",
        "isHidden", "orientation", "duration", "assetSubtype", "assetSubtypeV2",
        "assetHDRType", "burstFlags", "burstFlagsExt", "burstId", "captionEnc",
        "locationEnc", "locationV2Enc", "locationLatitude", "locationLongitude",
        "adjustmentType", "timeZoneOffset", "vidComplDurValue",
        "vidComplDurScale", "vidComplDispValue", "vidComplDispScale",
        "keywordsEnc", "extendedDescEnc", "adjustedMediaMetaDataEnc",
        "adjustmentSimpleDataEnc", "vidComplVisibilityState",
        "customRenderedValue", "containerId", "itemId", "position", "isKeyAsset",
    )
}
