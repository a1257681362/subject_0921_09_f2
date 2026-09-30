package com.evops.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.asset.entity.Asset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AssetMapper extends BaseMapper<Asset> {

    /** 取全局唯一素材号序列下一个值，并发取号不重。 */
    @Select("SELECT NEXT VALUE FOR seq_asset_no")
    long nextAssetSeq();
}
