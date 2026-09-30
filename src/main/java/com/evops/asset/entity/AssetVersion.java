package com.evops.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 多规格转档版本：一张原图的每个常规规格一行。
 * 横竖两版常规规格各自独立状态；朝向不符不硬裁，记 SKIPPED 并写明原因。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_asset_version")
public class AssetVersion extends BaseEntity {
    /** 所属素材档案 id */
    private Long assetId;
    /** 用途码，例如 FEED_HORIZONTAL / FEED_VERTICAL */
    private String usageCode;
    /** 用途名称（标清用途给美工看），例如 横版主投放 */
    private String usageName;
    /** 规格码，例如 H1920 / V1080 */
    private String specCode;
    /** 目标朝向：LANDSCAPE / PORTRAIT */
    private String targetOrientation;
    /** 长边像素上限：长边等比压进该值 */
    private Integer maxLongEdge;
    /** 体积上限（字节）：超标再压一轮质量 */
    private Long maxSizeBytes;
    /** 实际生效的压缩质量轮次（1 起；0 表示未产出） */
    private Integer qualityRound;
    /** 产出图宽（px） */
    private Integer width;
    /** 产出图高（px） */
    private Integer height;
    /** 产出文件体积（字节） */
    private Long fileSize;
    /** 产出文件在文件仓中的相对路径 */
    private String storagePath;
    /** 状态：PENDING / CONVERTING / READY / FAILED / SKIPPED */
    private String status;
    /** 失败原因：转档失败时美工直接看得见，不干等 */
    private String failReason;
}
