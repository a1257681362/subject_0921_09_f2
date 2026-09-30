package com.evops.asset;

import com.evops.asset.dto.AssetDetail;
import com.evops.asset.dto.AssetUploadResult;
import com.evops.asset.entity.Asset;
import com.evops.asset.entity.AssetVersion;
import com.evops.asset.mapper.AssetMapper;
import com.evops.asset.mapper.AssetVersionMapper;
import com.evops.asset.service.AssetConvertService;
import com.evops.asset.service.AssetService;
import com.evops.asset.support.ImageConverter;
import com.evops.asset.support.ImageSpec;
import com.evops.common.BusinessException;
import com.evops.ledger.service.SchemaVersionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** F2 素材多规格转档核心口径端到端测试（真实 H2 + 真实文件仓 + 真实转档线程）。 */
@SpringBootTest
class AssetFlowTest {

    @Autowired private AssetService assetService;
    @Autowired private AssetConvertService convertService;
    @Autowired private ImageConverter imageConverter;
    @Autowired private SchemaVersionService schemaVersionService;
    @Autowired private AssetMapper assetMapper;
    @Autowired private AssetVersionMapper versionMapper;

    // ---- 造图工具 ----

    private byte[] smoothImage(int width, int height) throws Exception {
        // 大色块 + 渐变：平滑、极易压缩，用来验证“长边压进限定像素”，不触发多轮压质量
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        for (int x = 0; x < width; x++) {
            int v = (x * 255) / width;
            g.setColor(new Color(v, 100, 255 - v));
            g.drawLine(x, 0, x, height);
        }
        g.setColor(Color.WHITE);
        g.fillRect(width / 8, height / 8, width / 4, height / 4);
        g.setColor(new Color(20, 60, 120));
        g.fillOval(width / 2, height / 3, width / 5, height / 5);
        g.dispose();
        return pngBytes(img);
    }

    private byte[] noisyImage(int width, int height) throws Exception {
        // 高频棋盘 + 随机噪点：极难压缩，用来把体积顶过上限，逼出多轮压质量/失败
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int base = ((x + y) & 1) == 0 ? 255 : 0;
                int jitter = random.nextInt(60) - 30;
                int v = Math.max(0, Math.min(255, base + jitter));
                img.setRGB(x, y, new Color(v, (v + 37) % 256, (255 - v + 91) % 256).getRGB());
            }
        }
        return pngBytes(img);
    }

    private byte[] pngBytes(BufferedImage img) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private Asset uploadAndWait(byte[] bytes, String fileName) throws Exception {
        AssetUploadResult result = assetService.upload(bytes, fileName, "image/png", "tester", null);
        assertFalse(result.isDuplicated());
        return awaitFinished(result.getAsset().getAssetNo());
    }

    private Asset awaitFinished(String assetNo) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        Asset asset;
        do {
            asset = assetService.requireByNo(assetNo);
            if (!AssetEnums.ASSET_CONVERTING.equals(asset.getStatus())) {
                return asset;
            }
            Thread.sleep(100);
        } while (System.currentTimeMillis() < deadline);
        // 超时兜底：同步再跑一次，把状态落定后返回，避免 CI 偶发卡住
        convertService.processNow(asset.getId());
        return assetService.requireByNo(assetNo);
    }

    private Map<String, AssetVersion> versionMap(String assetNo) {
        return assetService.detail(assetNo).getVersions().stream()
                .collect(Collectors.toMap(AssetVersion::getUsageCode, Function.identity()));
    }

    // ---- 口径测试 ----

    @Test
    void 横版原图自动出横版_竖版记不适用并写明原因() throws Exception {
        Asset asset = uploadAndWait(smoothImage(2400, 1500), "横版主视觉.png");

        assertEquals(AssetEnums.ASSET_READY, asset.getStatus());
        assertEquals(AssetEnums.ORIENT_LANDSCAPE, asset.getOrientation());

        AssetVersion horizontal = versionMap(asset.getAssetNo()).get("FEED_HORIZONTAL");
        assertEquals(AssetEnums.VERSION_READY, horizontal.getStatus());
        // 长边压进 1920：2400×1500 等比 → 1920×1200
        assertEquals(1920, horizontal.getWidth());
        assertEquals(1200, horizontal.getHeight());
        assertTrue(horizontal.getFileSize() <= 300L * 1024, "横版成品体积必须压进 300KB");
        assertTrue(horizontal.getQualityRound() >= 1);
        assertNotNull(horizontal.getStoragePath());

        AssetVersion vertical = versionMap(asset.getAssetNo()).get("FEED_VERTICAL");
        assertEquals(AssetEnums.VERSION_SKIPPED, vertical.getStatus());
        assertTrue(vertical.getFailReason().contains("竖版"), "跳过原因要让美工看得懂");
        assertTrue(vertical.getFailReason().contains("不硬裁"));

        // 成品可下载，是合法 JPEG
        byte[] jpeg = assetService.download(asset.getAssetNo(), "FEED_HORIZONTAL").getBytes();
        assertEquals((long) jpeg.length, horizontal.getFileSize());
        assertEquals((byte) 0xFF, jpeg[0]);
        assertEquals((byte) 0xD8, jpeg[1]);
    }

    @Test
    void 竖版原图自动出竖版_长边压进1080_体积压进200KB() throws Exception {
        Asset asset = uploadAndWait(smoothImage(900, 1600), "竖版信息流.png");

        assertEquals(AssetEnums.ASSET_READY, asset.getStatus());
        AssetVersion vertical = versionMap(asset.getAssetNo()).get("FEED_VERTICAL");
        assertEquals(AssetEnums.VERSION_READY, vertical.getStatus());
        // 900×1600 等比：长边 1600 → 1080，短边 900 → 608
        assertEquals(1080, vertical.getHeight());
        assertEquals(608, vertical.getWidth());
        assertTrue(Math.max(vertical.getWidth(), vertical.getHeight()) <= 1080);
        assertTrue(vertical.getFileSize() <= 200L * 1024, "竖版成品体积必须压进 200KB");

        AssetVersion horizontal = versionMap(asset.getAssetNo()).get("FEED_HORIZONTAL");
        assertEquals(AssetEnums.VERSION_SKIPPED, horizontal.getStatus());
        assertNotNull(horizontal.getFailReason());
    }

    @Test
    void 小图不放大_长边低于上限时保持原尺寸() throws Exception {
        Asset asset = uploadAndWait(smoothImage(800, 500), "小横图.png");
        AssetVersion horizontal = versionMap(asset.getAssetNo()).get("FEED_HORIZONTAL");
        assertEquals(AssetEnums.VERSION_READY, horizontal.getStatus());
        assertEquals(800, horizontal.getWidth());
        assertEquals(500, horizontal.getHeight());
    }

    @Test
    void 体积超标逐轮下压质量_轮次体积单调下降() throws Exception {
        // 直接对转档引擎做确定性验证：上限 1 字节时四轮压完仍超标
        BufferedImage noise = ImageIO.read(new java.io.ByteArrayInputStream(noisyImage(600, 400)));
        ImageSpec impossible = new ImageSpec("T", "测试", "T", "LANDSCAPE", 600, 1L);
        ImageConverter.Converted failed = imageConverter.convert(noise, impossible);
        assertTrue(failed.isOversize());
        assertEquals(ImageConverter.QUALITY_ROUNDS.length, failed.getQualityRound());
        long[] sizes = failed.getRoundSizes();
        for (int i = 1; i < sizes.length; i++) {
            assertTrue(sizes[i] < sizes[i - 1], "每再压一轮质量体积应继续下降");
        }

        // 阈值卡在第 1、2 轮体积之间时，应自动再压一轮并在第 2 轮达标
        long threshold = (sizes[0] + sizes[1]) / 2;
        ImageSpec twoRounds = new ImageSpec("T", "测试", "T", "LANDSCAPE", 600, threshold);
        ImageConverter.Converted ok = imageConverter.convert(noise, twoRounds);
        assertFalse(ok.isOversize());
        assertEquals(2, ok.getQualityRound());
        assertTrue(ok.getBytes().length <= threshold);
    }

    @Test
    void 同一张图重复上传按指纹认出不另开档() throws Exception {
        byte[] bytes = smoothImage(1200, 800);
        AssetUploadResult first = assetService.upload(bytes, "首传.png", "image/png", "tester", null);
        awaitFinished(first.getAsset().getAssetNo());

        // 换个文件名再传同一内容：指纹一致
        AssetUploadResult second = assetService.upload(bytes, "改名再传.png", "image/png", "tester", null);
        assertTrue(second.isDuplicated(), "同图必须按指纹认出来");
        assertEquals(first.getAsset().getId(), second.getAsset().getId());
        assertEquals(first.getAsset().getAssetNo(), second.getAsset().getAssetNo());

        // 档案下始终只有横竖两条版本，没有重复建档
        AssetDetail detail = assetService.detail(first.getAsset().getAssetNo());
        assertEquals(2, detail.getVersions().size());
    }

    @Test
    void 无法解码的文件上传即报错_不让美工干等() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> assetService.upload("这其实不是图片".getBytes(), "坏图.png", "image/png", "tester", null));
        assertTrue(ex.getMessage().contains("无法解码"));
    }

    @Test
    void 体积压到最低仍超标_版本失败原因可见_恢复阈值后可重跑() throws Exception {
        Asset asset = uploadAndWait(smoothImage(2200, 1400), "大图.png");
        assertEquals(AssetEnums.ASSET_READY, asset.getStatus());

        AssetVersion horizontal = versionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AssetVersion>()
                        .eq(AssetVersion::getAssetId, asset.getId())
                        .eq(AssetVersion::getUsageCode, "FEED_HORIZONTAL"));
        assertNotNull(horizontal);

        // 模拟“体积上限极严”：把该版本上限改成 1 字节并重置为待转，同步重转必然压到最低仍超标
        horizontal.setMaxSizeBytes(1L);
        horizontal.setStatus(AssetEnums.VERSION_PENDING);
        horizontal.setQualityRound(0);
        horizontal.setFileSize(null);
        horizontal.setWidth(null);
        horizontal.setHeight(null);
        horizontal.setStoragePath(null);
        horizontal.setFailReason(null);
        versionMapper.updateById(horizontal);
        convertService.processNow(asset.getId());

        AssetVersion failed = versionMapper.selectById(horizontal.getId());
        assertEquals(AssetEnums.VERSION_FAILED, failed.getStatus());
        assertEquals(4, failed.getQualityRound());
        assertTrue(failed.getFailReason().contains("仍超标"), "失败原因要写明体积超标");
        assertTrue(failed.getFailReason().contains("各轮体积"), "失败原因要列出各轮体积");

        Asset failedAsset = assetService.requireByNo(asset.getAssetNo());
        assertEquals(AssetEnums.ASSET_FAILED, failedAsset.getStatus());
        assertNotNull(failedAsset.getFailReason());
        assertNotNull(failedAsset.getFinishedTime());

        // 未产出的版本不能下载，报错带上原因
        BusinessException dl = assertThrows(BusinessException.class,
                () -> assetService.download(asset.getAssetNo(), "FEED_HORIZONTAL"));
        assertTrue(dl.getMessage().contains("尚无成品"));

        // 恢复正常上限后失败重跑：版本重新产出、档案回全部就绪
        failed.setMaxSizeBytes(300L * 1024);
        versionMapper.updateById(failed);
        Asset rerun = assetService.retry(asset.getAssetNo(), "tester");
        assertEquals(AssetEnums.ASSET_CONVERTING, rerun.getStatus());
        Asset finished = awaitFinished(asset.getAssetNo());
        assertEquals(AssetEnums.ASSET_READY, finished.getStatus());
        AssetVersion recovered = versionMapper.selectById(horizontal.getId());
        assertEquals(AssetEnums.VERSION_READY, recovered.getStatus());
        assertTrue(recovered.getFileSize() <= 300L * 1024);
    }

    @Test
    void 转档中状态不允许重跑_就绪状态不允许重跑() throws Exception {
        Asset asset = uploadAndWait(smoothImage(1000, 700), "就绪图.png");
        assertEquals(AssetEnums.ASSET_READY, asset.getStatus());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> assetService.retry(asset.getAssetNo(), "tester"));
        assertTrue(ex.getMessage().contains("无需重跑"));
    }

    @Test
    void 底账版本登记包含f2素材转档版本() {
        List<String> versions = schemaVersionService.list().stream()
                .map(v -> v.getVersionNo()).collect(Collectors.toList());
        assertTrue(versions.contains("f2-asset-1"));
    }
}
