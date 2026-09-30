package com.evops.asset.controller;

import com.evops.asset.dto.AssetDetail;
import com.evops.asset.dto.AssetUploadResult;
import com.evops.asset.entity.Asset;
import com.evops.asset.service.AssetService;
import com.evops.asset.support.AssetStorage;
import com.evops.common.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 素材流水与多规格转档：上传原图、翻查档案与各版本状态、下载成品、失败重跑。 */
@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private final AssetService assetService;

    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    /**
     * 上传原图（multipart/form-data，字段 file）。
     * 传完即建档返回，横竖两版常规规格后台自动转；同图按指纹认档，不另开档。
     */
    @PostMapping("/upload")
    public ApiResponse<AssetUploadResult> upload(@RequestParam("file") MultipartFile file,
                                                 @RequestParam(required = false) String operatorName,
                                                 @RequestParam(required = false) String remark) throws IOException {
        if (file == null || file.isEmpty()) {
            return ApiResponse.fail("上传文件为空，无法建档");
        }
        AssetUploadResult result = assetService.upload(file.getBytes(),
                file.getOriginalFilename(), file.getContentType(), operatorName, remark);
        return ApiResponse.ok(result);
    }

    /** 翻查素材档案：?assetNo=&fileName=&status= */
    @GetMapping
    public ApiResponse<List<Asset>> search(@RequestParam(required = false) String assetNo,
                                           @RequestParam(required = false) String fileName,
                                           @RequestParam(required = false) String status) {
        return ApiResponse.ok(assetService.search(assetNo, fileName, status));
    }

    /** 素材档案详情：含各转档版本（用途、规格、状态、失败原因）。 */
    @GetMapping("/{assetNo}")
    public ApiResponse<AssetDetail> detail(@PathVariable String assetNo) {
        return ApiResponse.ok(assetService.detail(assetNo));
    }

    /** 失败重跑（仅 PARTIAL / FAILED 可重跑，只重转失败版本）。 */
    @PostMapping("/{assetNo}/retry")
    public ApiResponse<Asset> retry(@PathVariable String assetNo,
                                    @RequestParam(required = false) String operatorName) {
        return ApiResponse.ok(assetService.retry(assetNo, operatorName));
    }

    /** 下载：usage=ORIGINAL 原图，或用途码 FEED_HORIZONTAL / FEED_VERTICAL（仅 READY 可下载）。 */
    @GetMapping("/{assetNo}/download")
    public ResponseEntity<byte[]> download(@PathVariable String assetNo,
                                           @RequestParam(defaultValue = "ORIGINAL") String usage) {
        AssetStorage.DownloadFile file = assetService.download(assetNo, usage);
        String encodedName;
        try {
            encodedName = URLEncoder.encode(file.getFileName(), StandardCharsets.UTF_8.name())
                    .replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            encodedName = "asset";
        }
        MediaType mediaType = file.getContentType() != null
                ? MediaType.parseMediaType(file.getContentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodedName)
                .contentType(mediaType)
                .body(file.getBytes());
    }
}
