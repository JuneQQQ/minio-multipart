package com.juneqqq.controller;


import com.juneqqq.entity.base.R;
import com.juneqqq.entity.request.MultipartUploadRequest;
import com.juneqqq.entity.response.FileUploadResponse;
import com.juneqqq.entity.response.MultipartUploadCreateResponse;
import com.juneqqq.entity.vo.ListObjectVo;
import com.juneqqq.service.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/minio")
public class FileApi {
    @Resource
    private FileService fileService;

    @GetMapping("/files-info")
    public R<List<ListObjectVo>> getFileInfo(String bucket, String prefix) {
        List<ListObjectVo> items = fileService.listObjects(bucket, prefix);
        return new R<>(items);
    }

    @GetMapping("/preview-files")
    public R<String> getPreviewURL(String bucket, String fileName) {
        String url = fileService.preview(fileName, bucket);
        return new R<>(url);
    }

    /**
     * 普通上传
     */
    @PostMapping("/files")
    public R<String> uploadMinioFile(
            MultipartFile file,
            String bucket,
            String hash
    ) {
        log.debug("file：" + file.getOriginalFilename());
        fileService.upload(file, bucket, hash);
        return R.success();
    }

    @PostMapping("/multipart/create")
    public R<MultipartUploadCreateResponse> createMultipartUpload(
            @RequestBody
            MultipartUploadRequest mur
    ) {
        MultipartUploadCreateResponse multipartUpload =
                fileService.createMultipartUpload(mur);
        switch (multipartUpload.getFileStatusEnum()) {
            case FILE_COMPLETELY_EXISTS -> {
                return new R<>(2000, multipartUpload);
            }
            case FILE_NEED_MERGE -> {
                return new R<>(2001, multipartUpload);
            }
            case FILE_PARTLY_EXISTS -> {
                return new R<>(2002, multipartUpload);
            }
            case FILE_NOT_EXISTS -> {
                return new R<>(2003, multipartUpload);
            }
        }
        return new R<>(500, "文件状态码一个都不匹配吗？");
    }

    @CrossOrigin
    @PostMapping("/multipart/merge")
    public R<FileUploadResponse> completeMultipartUpload(
            @RequestBody
            @Validated
            MultipartUploadRequest uploadRequest
    ) {
        return new R<>(fileService.mergeMultipartUpload(uploadRequest));
    }


}
