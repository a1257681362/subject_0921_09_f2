package com.evops.asset.support;

import com.evops.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * 素材文件仓：原图与各转档版本落本地磁盘，数据库只存相对路径。
 * 原图按指纹存放（同一指纹天然只落一份），版本按素材号 + 规格 + 轮次存放。
 */
@Component
public class AssetStorage {

    private final Path baseDir;

    public AssetStorage(@Value("${evops.asset.storage-dir:./data/assets}") String storageDir) {
        this.baseDir = Paths.get(storageDir).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(baseDir);
    }

    /** 保存原图，返回相对路径；按指纹分桶，同图不重复落盘。 */
    public String saveOriginal(String fingerprint, String originalFileName, byte[] bytes) {
        String ext = extOf(originalFileName);
        String relative = "origin/" + fingerprint.substring(0, 2) + "/" + fingerprint + ext;
        writeIfAbsent(relative, bytes);
        return relative;
    }

    /** 保存某规格某质量轮次的产出（统一 JPEG），返回相对路径。 */
    public String saveVersion(String assetNo, String specCode, int qualityRound, byte[] bytes) {
        String relative = "versions/" + assetNo + "/" + specCode + "-r" + qualityRound + ".jpg";
        writeIfAbsent(relative, bytes);
        return relative;
    }

    public byte[] read(String relativePath) {
        try {
            return Files.readAllBytes(resolve(relativePath));
        } catch (IOException e) {
            throw new BusinessException("素材文件读取失败：" + relativePath);
        }
    }

    public Path resolve(String relativePath) {
        Path path = baseDir.resolve(relativePath).normalize();
        if (!path.startsWith(baseDir)) {
            throw new BusinessException("非法的素材文件路径");
        }
        return path;
    }

    public void deleteQuietly(String relativePath) {
        if (relativePath == null) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException ignored) {
            // 清理残留失败，不影响主流程
        }
    }

    /** 下载用：文件名 + MIME + 内容。 */
    public static class DownloadFile {
        private final String fileName;
        private final String contentType;
        private final byte[] bytes;

        public DownloadFile(String fileName, String contentType, byte[] bytes) {
            this.fileName = fileName;
            this.contentType = contentType;
            this.bytes = bytes;
        }

        public String getFileName() { return fileName; }
        public String getContentType() { return contentType; }
        public byte[] getBytes() { return bytes; }
    }

    private void writeIfAbsent(String relative, byte[] bytes) {
        Path target = resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            // 同指纹重跑时文件已存在，直接复用，不重复写
            if (!Files.exists(target)) {
                Files.write(target, bytes);
            }
        } catch (IOException e) {
            throw new BusinessException("素材文件保存失败：" + e.getMessage());
        }
    }

    private String extOf(String fileName) {
        if (fileName == null) {
            return ".img";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return ".img";
        }
        String ext = fileName.substring(dot).toLowerCase(Locale.ROOT);
        // 只留扩展名，挡住路径穿越字符
        return ext.matches("\\.[a-z0-9]{1,8}") ? ext : ".img";
    }
}
