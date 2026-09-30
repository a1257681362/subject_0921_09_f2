package com.evops.ledger.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 点位档案：编号、尺寸、朝向、商圈。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_site")
public class Site extends BaseEntity {
    /** 点位编号，全局唯一，重复存不进去 */
    private String siteNo;
    /** 尺寸规格，例如 18m×6m */
    private String sizeSpec;
    /** 朝向，例如 朝北 / 东南 */
    private String orientation;
    /** 商圈 */
    private String businessDistrict;
    /** 状态：LISTED 挂牌 */
    private String status;
    /** 挂牌经手人 */
    private String operatorName;
    private String remark;
}
