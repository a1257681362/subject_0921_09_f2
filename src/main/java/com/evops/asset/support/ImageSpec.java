package com.evops.asset.support;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 常规转档规格：横竖两版。
 * 长边等比压进 maxLongEdge，体积压进 maxSizeBytes（超标再压质量）。
 * 朝向不符不硬裁硬拉——该版本由编排处记 SKIPPED。
 */
public final class ImageSpec {

    /** 横版主投放（信息流横图等）：长边 1920px，体积 ≤ 300KB */
    public static final ImageSpec FEED_HORIZONTAL = new ImageSpec(
            "FEED_HORIZONTAL", "横版主投放", "H1920", "LANDSCAPE", 1920, 300L * 1024);

    /** 竖版主投放（信息流竖图等）：长边 1080px，体积 ≤ 200KB */
    public static final ImageSpec FEED_VERTICAL = new ImageSpec(
            "FEED_VERTICAL", "竖版主投放", "V1080", "PORTRAIT", 1080, 200L * 1024);

    /** 常规规格清单：原图传上来后自动按这两版各出一份 */
    public static final List<ImageSpec> REGULAR_SPECS =
            Collections.unmodifiableList(Arrays.asList(FEED_HORIZONTAL, FEED_VERTICAL));

    private final String usageCode;
    private final String usageName;
    private final String specCode;
    private final String targetOrientation;
    private final int maxLongEdge;
    private final long maxSizeBytes;

    public ImageSpec(String usageCode, String usageName, String specCode,
                     String targetOrientation, int maxLongEdge, long maxSizeBytes) {
        this.usageCode = usageCode;
        this.usageName = usageName;
        this.specCode = specCode;
        this.targetOrientation = targetOrientation;
        this.maxLongEdge = maxLongEdge;
        this.maxSizeBytes = maxSizeBytes;
    }

    public String getUsageCode() { return usageCode; }
    public String getUsageName() { return usageName; }
    public String getSpecCode() { return specCode; }
    public String getTargetOrientation() { return targetOrientation; }
    public int getMaxLongEdge() { return maxLongEdge; }
    public long getMaxSizeBytes() { return maxSizeBytes; }
}
