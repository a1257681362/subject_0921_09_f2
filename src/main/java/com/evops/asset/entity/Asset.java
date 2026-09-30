package com.evops.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 素材档案：一张原图一条档案。
 * 文件指纹 sha256 唯一——同一张图重复上传按指纹认出来，不另开档。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_asset")
public class Asset extends BaseEntity {
    /** 素材号，AS+序列，全局唯一 */
    private String assetNo;
    /** 原始文件名 */
    private String fileName;
    /** 原图 MIME 类型 */
    private String contentType;
    /** 原图文件指纹（SHA-256 十六进制），重复上传据此认档 */
    private String fileFingerprint;
    /** 原图宽（px） */
    private Integer width;
    /** 原图高（px） */
    private Integer height;
    /** 原图朝向：LANDSCAPE / PORTRAIT / SQUARE */
    private String orientation;
    /** 原图体积（字节） */
    private Long originSize;
    /** 原图在文件仓中的相对路径 */
    private String storagePath;
    /** 整体转档状态：CONVERTING / READY / PARTIAL / FAILED */
    private String status;
    /** 整体失败原因（如原图无法解码；部分失败原因看各版本） */
    private String failReason;
    /** 上传经手人 */
    private String uploaderName;
    /** 转档全部处理完的时刻 */
    private LocalDateTime finishedTime;
    private String remark;
}
