package com.evops.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.asset.AssetEnums;
import com.evops.asset.dto.AssetDetail;
import com.evops.asset.dto.AssetUploadResult;
import com.evops.asset.entity.Asset;
import com.evops.asset.entity.AssetVersion;
import com.evops.asset.mapper.AssetMapper;
import com.evops.asset.mapper.AssetVersionMapper;
import com.evops.asset.support.AssetStorage;
import com.evops.asset.support.ImageConverter;
import com.evops.asset.support.ImageSpec;
import com.evops.common.BusinessException;
import com.evops.ledger.support.Operators;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

/**
 * 素材流水入口：原图上传 → 算指纹认档（重复不另开档）→ 存原图、建档案与待转版本 →
 * 事务提交后丢给后台异步转档。上传接口立即返回，美工不干等。
 */
@Service
public class AssetService {

    private final AssetMapper assetMapper;
    private final AssetVersionMapper versionMapper;
    private final AssetStorage storage;
    private final ImageConverter converter;
    private final AssetConvertService convertService;

    public AssetService(AssetMapper assetMapper, AssetVersionMapper versionMapper,
                        AssetStorage storage, ImageConverter converter,
                        AssetConvertService convertService) {
        this.assetMapper = assetMapper;
        this.versionMapper = versionMapper;
        this.storage = storage;
        this.converter = converter;
        this.convertService = convertService;
    }

    /**
     * 上传原图。
     * @return 建档结果；duplicated=true 表示按指纹认出是同一张图，复用原档案、不另开档
     */
    @Transactional(rollbackFor = Exception.class)
    public AssetUploadResult upload(byte[] bytes, String fileName, String contentType,
                                    String explicitUploader, String remark) {
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException("上传文件为空，无法建档");
        }
        String uploader = Operators.resolve(explicitUploader);
        String fingerprint = converter.sha256(bytes);

        // 先按指纹认档：同一张图重复上传，不另开档
        Asset existing = assetMapper.selectOne(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getFileFingerprint, fingerprint));
        if (existing != null) {
            return new AssetUploadResult(true, existing);
        }

        // 原图先落文件仓留底，再解码校验；解码失败当场告诉美工原因，不留半成品档案
        String originPath = storage.saveOriginal(fingerprint, fileName, bytes);
        BufferedImage image;
        try {
            image = converter.decode(bytes);
        } catch (IOException e) {
            throw new BusinessException(e.getMessage());
        }

        Asset asset = new Asset();
        asset.setAssetNo(generateAssetNo());
        asset.setFileName(fileName);
        asset.setContentType(contentType);
        asset.setFileFingerprint(fingerprint);
        asset.setWidth(image.getWidth());
        asset.setHeight(image.getHeight());
        asset.setOrientation(converter.orientationOf(image.getWidth(), image.getHeight()));
        asset.setOriginSize((long) bytes.length);
        asset.setStoragePath(originPath);
        asset.setStatus(AssetEnums.ASSET_CONVERTING);
        asset.setUploaderName(uploader);
        asset.setRemark(remark);
        try {
            assetMapper.insert(asset);
        } catch (DuplicateKeyException e) {
            // 并发重复上传：指纹唯一约束兜底，认回已建档案即可（同 SiteService 的口径）
            Asset raced = assetMapper.selectOne(new LambdaQueryWrapper<Asset>()
                    .eq(Asset::getFileFingerprint, fingerprint));
            return new AssetUploadResult(true, raced);
        }

        // 横竖两版常规规格各占一行待转，规格参数随档固化（日后改默认规格不影响老档）
        for (ImageSpec spec : ImageSpec.REGULAR_SPECS) {
            AssetVersion version = new AssetVersion();
            version.setAssetId(asset.getId());
            version.setUsageCode(spec.getUsageCode());
            version.setUsageName(spec.getUsageName());
            version.setSpecCode(spec.getSpecCode());
            version.setTargetOrientation(spec.getTargetOrientation());
            version.setMaxLongEdge(spec.getMaxLongEdge());
            version.setMaxSizeBytes(spec.getMaxSizeBytes());
            version.setQualityRound(0);
            version.setStatus(AssetEnums.VERSION_PENDING);
            versionMapper.insert(version);
        }

        Long assetId = asset.getId();
        // 档案事务提交后再触发后台转档，避免后台线程读不到未提交数据
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                convertService.enqueue(assetId);
            }
        });
        return new AssetUploadResult(false, asset);
    }

    /** 失败重跑：仅 PARTIAL / FAILED 可重跑，把失败版本重置为待转后重新入队。 */
    @Transactional(rollbackFor = Exception.class)
    public Asset retry(String assetNo, String explicitUploader) {
        Asset asset = requireByNo(assetNo);
        if (AssetEnums.ASSET_CONVERTING.equals(asset.getStatus())) {
            throw new BusinessException("素材 " + assetNo + " 正在转档中，无需重跑");
        }
        if (AssetEnums.ASSET_READY.equals(asset.getStatus())) {
            throw new BusinessException("素材 " + assetNo + " 各适用规格均已产出，无需重跑");
        }
        Operators.resolve(explicitUploader);
        asset.setStatus(AssetEnums.ASSET_CONVERTING);
        asset.setFailReason(null);
        asset.setFinishedTime(null);
        assetMapper.updateById(asset);

        List<AssetVersion> versions = listVersions(asset.getId());
        boolean any = false;
        for (AssetVersion v : versions) {
            if (AssetEnums.VERSION_FAILED.equals(v.getStatus())) {
                any = true;
                storage.deleteQuietly(v.getStoragePath());
                v.setStatus(AssetEnums.VERSION_PENDING);
                v.setFailReason(null);
                v.setStoragePath(null);
                v.setFileSize(null);
                v.setWidth(null);
                v.setHeight(null);
                v.setQualityRound(0);
                versionMapper.updateById(v);
            }
        }
        if (!any) {
            // 只有 SKIPPED 没有 FAILED（例如方图以外的不适用规格），无档可重跑
            throw new BusinessException("素材 " + assetNo + " 没有转档失败的版本，无需重跑");
        }

        Long assetId = asset.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                convertService.enqueue(assetId);
            }
        });
        return asset;
    }

    public Asset requireByNo(String assetNo) {
        Asset asset = assetMapper.selectOne(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getAssetNo, assetNo == null ? null : assetNo.trim()));
        if (asset == null) {
            throw new BusinessException("素材不存在: " + assetNo);
        }
        return asset;
    }

    public AssetDetail detail(String assetNo) {
        Asset asset = requireByNo(assetNo);
        return new AssetDetail(asset, listVersions(asset.getId()));
    }

    public List<AssetVersion> listVersions(Long assetId) {
        return versionMapper.selectList(new LambdaQueryWrapper<AssetVersion>()
                .eq(AssetVersion::getAssetId, assetId)
                .orderByAsc(AssetVersion::getId));
    }

    /** 翻查：素材号 / 文件名模糊 / 状态，空条件全量。 */
    public List<Asset> search(String assetNo, String fileName, String status) {
        LambdaQueryWrapper<Asset> wrapper = new LambdaQueryWrapper<>();
        if (assetNo != null && !assetNo.trim().isEmpty()) {
            wrapper.like(Asset::getAssetNo, assetNo.trim());
        }
        if (fileName != null && !fileName.trim().isEmpty()) {
            wrapper.like(Asset::getFileName, fileName.trim());
        }
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq(Asset::getStatus, status.trim());
        }
        wrapper.orderByDesc(Asset::getId);
        return assetMapper.selectList(wrapper);
    }

    /** 取可下载文件：usageCode 为某规格用途码，ORIGINAL 为原图；仅已产出版本可下载。 */
    public AssetStorage.DownloadFile download(String assetNo, String usageCode) {
        Asset asset = requireByNo(assetNo);
        if ("ORIGINAL".equalsIgnoreCase(usageCode)) {
            return new AssetStorage.DownloadFile(asset.getFileName(),
                    asset.getContentType(), storage.read(asset.getStoragePath()));
        }
        AssetVersion version = versionMapper.selectOne(new LambdaQueryWrapper<AssetVersion>()
                .eq(AssetVersion::getAssetId, asset.getId())
                .eq(AssetVersion::getUsageCode, usageCode));
        if (version == null) {
            throw new BusinessException("该素材没有用途为 " + usageCode + " 的版本");
        }
        if (!AssetEnums.VERSION_READY.equals(version.getStatus())) {
            throw new BusinessException("用途「" + version.getUsageName() + "」当前状态为 "
                    + version.getStatus() + "，尚无成品可下载"
                    + (version.getFailReason() != null ? "：" + version.getFailReason() : ""));
        }
        String downloadName = stripExt(asset.getFileName()) + "_" + version.getSpecCode() + ".jpg";
        return new AssetStorage.DownloadFile(downloadName, "image/jpeg",
                storage.read(version.getStoragePath()));
    }

    private String stripExt(String fileName) {
        if (fileName == null) {
            return "asset";
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private String generateAssetNo() {
        long seq = assetMapper.nextAssetSeq();
        return String.format("AS%010d", seq);
    }
}
