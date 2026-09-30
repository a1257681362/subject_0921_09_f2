package com.evops.asset.support;

import com.evops.asset.AssetEnums;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;

/**
 * 图片转档引擎（JDK ImageIO，不引三方依赖）：
 * 文件指纹 SHA-256；长边等比压进限定像素（不放大原图）；体积超标按轮次下压 JPEG 质量。
 */
@Component
public class ImageConverter {

    /** 体积超标时逐轮下压的 JPEG 质量：第 1 轮 0.85，超标再压，依次 0.70 / 0.55 / 0.40。 */
    public static final float[] QUALITY_ROUNDS = {0.85f, 0.70f, 0.55f, 0.40f};

    /** 文件指纹：重复上传据此认档。 */
    public String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }

    /** 解码原图；无法解码抛 IOException，由上层把原因亮给美工。 */
    public BufferedImage decode(byte[] bytes) throws IOException {
        ImageIO.setUseCache(false);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            throw new IOException("图片无法解码：不是受支持的图片格式，或文件已损坏（支持 JPG / PNG / GIF / BMP）");
        }
        return image;
    }

    /** 按宽高判朝向：横版 / 竖版 / 方图。 */
    public String orientationOf(int width, int height) {
        if (width == height) {
            return AssetEnums.ORIENT_SQUARE;
        }
        return width > height ? AssetEnums.ORIENT_LANDSCAPE : AssetEnums.ORIENT_PORTRAIT;
    }

    /** 一次规格转档的产出。 */
    public static class Converted {
        private final byte[] bytes;
        private final int width;
        private final int height;
        /** 实际生效的质量轮次（1 起） */
        private final int qualityRound;
        /** 各质量轮次产出的体积（字节），失败时写进原因给美工看 */
        private final long[] roundSizes;
        /** 最后一轮（最低质量）体积是否仍超标 */
        private final boolean oversize;

        Converted(byte[] bytes, int width, int height, int qualityRound, long[] roundSizes, boolean oversize) {
            this.bytes = bytes;
            this.width = width;
            this.height = height;
            this.qualityRound = qualityRound;
            this.roundSizes = roundSizes;
            this.oversize = oversize;
        }

        public byte[] getBytes() { return bytes; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public int getQualityRound() { return qualityRound; }
        public long[] getRoundSizes() { return roundSizes; }
        public boolean isOversize() { return oversize; }
    }

    /**
     * 按规格转档：长边等比压进限定像素（原图更小则保持原尺寸不放大），
     * 然后逐轮压质量，第一份体积达标即采用；轮次用尽仍超标时 oversize=true 返回，
     * 由上层记 FAILED（各轮体积写进失败原因）。
     */
    public Converted convert(BufferedImage source, ImageSpec spec) throws IOException {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        double scale = Math.min(1.0, (double) spec.getMaxLongEdge() / Math.max(sourceWidth, sourceHeight));
        int targetWidth = Math.max(1, (int) Math.round(sourceWidth * scale));
        int targetHeight = Math.max(1, (int) Math.round(sourceHeight * scale));

        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        g.dispose();

        byte[] last = null;
        long[] roundSizes = new long[QUALITY_ROUNDS.length];
        for (int round = 0; round < QUALITY_ROUNDS.length; round++) {
            byte[] out = writeJpeg(scaled, QUALITY_ROUNDS[round]);
            roundSizes[round] = out.length;
            last = out;
            if (out.length <= spec.getMaxSizeBytes()) {
                return new Converted(out, targetWidth, targetHeight, round + 1, roundSizes, false);
            }
        }
        return new Converted(last, targetWidth, targetHeight, QUALITY_ROUNDS.length, roundSizes, true);
    }

    private byte[] writeJpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("当前 JDK 没有可用的 JPEG 编码器");
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionType("JPEG");
        param.setCompressionQuality(quality);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }
}
