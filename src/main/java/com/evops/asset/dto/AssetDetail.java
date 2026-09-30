package com.evops.asset.dto;

import com.evops.asset.entity.Asset;
import com.evops.asset.entity.AssetVersion;

import java.util.List;

/** 素材档案详情：档案 + 各转档版本（用途、规格、状态、失败原因一屏看清）。 */
public class AssetDetail {
    private final Asset asset;
    private final List<AssetVersion> versions;

    public AssetDetail(Asset asset, List<AssetVersion> versions) {
        this.asset = asset;
        this.versions = versions;
    }

    public Asset getAsset() { return asset; }
    public List<AssetVersion> getVersions() { return versions; }
}
