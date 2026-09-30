package com.evops.asset.service;

import com.evops.asset.AssetEnums;
import com.evops.asset.entity.Asset;
import com.evops.asset.entity.AssetVersion;
import com.evops.asset.mapper.AssetMapper;
import com.evops.asset.mapper.AssetVersionMapper;
import com.evops.asset.support.AssetStorage;
import com.evops.asset.support.ImageConverter;
import com.evops.asset.support.ImageSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 多规格转档编排：对一张素材依次产出横竖两版常规规格。
 * 每个版本独立状态、独立失败原因——一版失败不连累另一版；
 * 汇总档案状态（全部就绪 / 部分失败 / 全部失败），美工在列表与详情直接看得见。
 */
@Service
public class AssetConvertService {

    private static final Logger log = LoggerFactory.getLogger(AssetConvertService.class);

    private final AssetMapper assetMapper;
    private final AssetVersionMapper versionMapper;
    private final AssetStorage storage;
    private final ImageConverter converter;

    public AssetConvertService(AssetMapper assetMapper, AssetVersionMapper versionMapper,
                               AssetStorage storage, ImageConverter converter) {
        this.assetMapper = assetMapper;
        this.versionMapper = versionMapper;
        this.storage = storage;
        this.converter = converter;
    }

    /** 入队即返回，转档在 asset-convert 线程池后台跑。 */
    @Async
    public void enqueue(Long assetId) {
        try {
            processNow(assetId);
        } catch (Throwable t) {
            // 兜底：绝不让异常烂在后台线程里，状态与原因必须落库给美工看
            log.error("素材转档未预期失败 assetId={}", assetId, t);
            markJobFailed(assetId, "转档任务异常：" + t.getMessage());
        }
    }

    /** 同步执行一次转档（也供测试直接调用与失败重跑复用）。 */
    public void processNow(Long assetId) {
        Asset asset = assetMapper.selectById(assetId);
        if (asset == null) {
            log.warn("转档素材不存在 assetId={}", assetId);
            return;
        }
        List<AssetVersion> versions = versionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AssetVersion>()
                        .eq(AssetVersion::getAssetId, assetId)
                        .orderByAsc(AssetVersion::getId));

        byte[] originBytes;
        BufferedImage image;
        try {
            originBytes = storage.read(asset.getStoragePath());
            image = converter.decode(originBytes);
        } catch (Exception e) {
            // 原图读不出/解不开：所有待转版本都记失败原因
            markJobFailed(assetId, "原图无法读取或解码：" + e.getMessage());
            return;
        }

        for (AssetVersion version : versions) {
            // 重跑入队时只转待转版本；已产出/已跳过的保持不动
            if (!AssetEnums.VERSION_PENDING.equals(version.getStatus())) {
                continue;
            }
            convertOne(asset, image, version);
        }

        aggregateAssetStatus(assetId);
    }

    private void convertOne(Asset asset, BufferedImage image, AssetVersion version) {
        version.setStatus(AssetEnums.VERSION_CONVERTING);
        version.setFailReason(null);
        versionMapper.updateById(version);

        // 朝向不符不硬裁硬拉：直接跳过并写明原因，美工一看就知道这版为何没出
        if (!asset.getOrientation().equals(version.getTargetOrientation())) {
            String reason = skipReason(asset.getOrientation(), version.getTargetOrientation(),
                    version.getUsageName());
            version.setStatus(AssetEnums.VERSION_SKIPPED);
            version.setFailReason(reason);
            versionMapper.updateById(version);
            return;
        }

        try {
            ImageSpec spec = new ImageSpec(version.getUsageCode(), version.getUsageName(),
                    version.getSpecCode(), version.getTargetOrientation(),
                    version.getMaxLongEdge(), version.getMaxSizeBytes());
            ImageConverter.Converted result = converter.convert(image, spec);

            if (result.isOversize()) {
                // 质量压到最后一轮体积仍超标：记失败，各轮体积亮给美工，不干等
                version.setStatus(AssetEnums.VERSION_FAILED);
                version.setQualityRound(result.getQualityRound());
                version.setFailReason(buildOversizeReason(spec, result));
                versionMapper.updateById(version);
                return;
            }

            String path = storage.saveVersion(asset.getAssetNo(), spec.getSpecCode(),
                    result.getQualityRound(), result.getBytes());
            version.setStatus(AssetEnums.VERSION_READY);
            version.setQualityRound(result.getQualityRound());
            version.setWidth(result.getWidth());
            version.setHeight(result.getHeight());
            version.setFileSize((long) result.getBytes().length);
            version.setStoragePath(path);
            version.setFailReason(null);
            versionMapper.updateById(version);
        } catch (Exception e) {
            log.warn("素材 {} 用途 {} 转档失败", asset.getAssetNo(), version.getUsageCode(), e);
            version.setStatus(AssetEnums.VERSION_FAILED);
            version.setFailReason(truncate("转档处理异常：" + e.getMessage()));
            versionMapper.updateById(version);
        }
    }

    /** 汇总各版本状态，回写素材档案整体状态。 */
    private void aggregateAssetStatus(Long assetId) {
        Asset asset = assetMapper.selectById(assetId);
        List<AssetVersion> versions = versionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AssetVersion>()
                        .eq(AssetVersion::getAssetId, assetId));

        int ready = 0, failed = 0, skipped = 0;
        StringBuilder reasons = new StringBuilder();
        for (AssetVersion v : versions) {
            switch (v.getStatus()) {
                case AssetEnums.VERSION_READY:
                    ready++;
                    break;
                case AssetEnums.VERSION_FAILED:
                    failed++;
                    if (reasons.length() > 0) {
                        reasons.append("；");
                    }
                    reasons.append(v.getUsageName()).append("：")
                            .append(v.getFailReason() == null ? "转档失败" : v.getFailReason());
                    break;
                case AssetEnums.VERSION_SKIPPED:
                    skipped++;
                    break;
                default:
                    // 理论上入队处理完不该再有 PENDING/CONVERTING，兜底按未完成处理
                    failed++;
                    break;
            }
        }

        String status;
        String failReason = null;
        if (failed == 0 && ready > 0) {
            status = AssetEnums.ASSET_READY;
        } else if (failed == 0) {
            // 没有失败也没有产出：全部是 SKIPPED（如方图横竖规格均不适用）
            status = AssetEnums.ASSET_FAILED;
            failReason = versions.stream()
                    .filter(v -> AssetEnums.VERSION_SKIPPED.equals(v.getStatus()))
                    .map(v -> v.getUsageName() + "：" + v.getFailReason())
                    .findFirst().orElse("没有适用的常规规格");
        } else if (ready > 0) {
            status = AssetEnums.ASSET_PARTIAL;
            failReason = truncate(reasons.toString());
        } else {
            status = AssetEnums.ASSET_FAILED;
            failReason = truncate(reasons.toString());
        }

        asset.setStatus(status);
        asset.setFailReason(failReason);
        asset.setFinishedTime(LocalDateTime.now());
        assetMapper.updateById(asset);
    }

    private void markJobFailed(Long assetId, String reason) {
        Asset asset = assetMapper.selectById(assetId);
        if (asset == null) {
            return;
        }
        List<AssetVersion> versions = versionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AssetVersion>()
                        .eq(AssetVersion::getAssetId, assetId));
        for (AssetVersion v : versions) {
            if (!AssetEnums.VERSION_READY.equals(v.getStatus())
                    && !AssetEnums.VERSION_SKIPPED.equals(v.getStatus())) {
                v.setStatus(AssetEnums.VERSION_FAILED);
                v.setFailReason(truncate(reason));
                versionMapper.updateById(v);
            }
        }
        asset.setStatus(AssetEnums.ASSET_FAILED);
        asset.setFailReason(truncate(reason));
        asset.setFinishedTime(LocalDateTime.now());
        assetMapper.updateById(asset);
    }

    private String skipReason(String originOrientation, String targetOrientation, String usageName) {
        String originText;
        switch (originOrientation) {
            case AssetEnums.ORIENT_LANDSCAPE: originText = "横版"; break;
            case AssetEnums.ORIENT_PORTRAIT: originText = "竖版"; break;
            default: originText = "方图"; break;
        }
        String targetText = AssetEnums.ORIENT_LANDSCAPE.equals(targetOrientation) ? "横版" : "竖版";
        return "原图为" + originText + "，不产出" + usageName + "（目标" + targetText
                + "，不硬裁硬拉；如需该版本请用对应朝向的原图）";
    }

    private String buildOversizeReason(ImageSpec spec, ImageConverter.Converted result) {
        StringBuilder sb = new StringBuilder();
        sb.append("长边已压至 ").append(spec.getMaxLongEdge())
                .append("px，质量逐轮下压至最低（第 ").append(result.getQualityRound())
                .append(" 轮）后体积仍超标：上限 ").append(kb(spec.getMaxSizeBytes())).append("KB，各轮体积 ");
        long[] sizes = result.getRoundSizes();
        for (int i = 0; i < sizes.length; i++) {
            if (i > 0) {
                sb.append("/");
            }
            sb.append(kb(sizes[i])).append("KB");
        }
        sb.append("；请精简画面元素或换用更易压缩的原图");
        return truncate(sb.toString());
    }

    private long kb(long bytes) {
        return Math.round(bytes / 1024.0);
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 950 ? text : text.substring(0, 950) + "…";
    }
}
