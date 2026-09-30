package com.evops.asset;

/** 素材流水与多规格转档的状态口径。 */
public final class AssetEnums {
    private AssetEnums() {}

    /** 素材档案整体转档状态：转档中 / 全部就绪 / 部分失败 / 全部失败 */
    public static final String ASSET_CONVERTING = "CONVERTING";
    public static final String ASSET_READY = "READY";
    public static final String ASSET_PARTIAL = "PARTIAL";
    public static final String ASSET_FAILED = "FAILED";

    /** 单个转档版本状态：待转 / 转档中 / 已产出 / 转档失败 / 朝向不符跳过 */
    public static final String VERSION_PENDING = "PENDING";
    public static final String VERSION_CONVERTING = "CONVERTING";
    public static final String VERSION_READY = "READY";
    public static final String VERSION_FAILED = "FAILED";
    public static final String VERSION_SKIPPED = "SKIPPED";

    /** 原图朝向：横版 / 竖版 / 方图 */
    public static final String ORIENT_LANDSCAPE = "LANDSCAPE";
    public static final String ORIENT_PORTRAIT = "PORTRAIT";
    public static final String ORIENT_SQUARE = "SQUARE";
}
