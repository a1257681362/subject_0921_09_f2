package com.evops.asset.dto;

import com.evops.asset.entity.Asset;

/** 上传结果：duplicated=true 表示按文件指纹认出是同一张图，复用原档案、未另开档。 */
public class AssetUploadResult {
    private final boolean duplicated;
    private final Asset asset;

    public AssetUploadResult(boolean duplicated, Asset asset) {
        this.duplicated = duplicated;
        this.asset = asset;
    }

    public boolean isDuplicated() { return duplicated; }
    public Asset getAsset() { return asset; }
}
