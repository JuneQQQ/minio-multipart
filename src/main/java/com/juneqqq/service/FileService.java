package com.juneqqq.service;



import cn.hutool.core.lang.Assert;
import com.juneqqq.config.PearlMinioClient;
import com.juneqqq.constant.FileStatusEnum;
import com.juneqqq.entity.base.ResultCode;
import com.juneqqq.entity.request.MultipartUploadRequest;
import com.juneqqq.entity.response.FileUploadResponse;
import com.juneqqq.entity.response.MultipartUploadCreateResponse;
import com.juneqqq.entity.vo.FileStatus;
import com.juneqqq.entity.vo.ListObjectVo;
import com.juneqqq.entity.vo.MultipartUploadCreate;
import com.juneqqq.exception.CustomException;
import com.juneqqq.support.MinioHelper;
import io.minio.*;
import io.minio.errors.InsufficientDataException;
import io.minio.http.Method;
import io.minio.messages.Item;
import io.minio.messages.Part;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

import static com.juneqqq.constant.FileStatusEnum.*;

@Service
@Slf4j
public class FileService {

    @Resource
    private MinioHelper minioHelper;

    @Resource
    private PearlMinioClient client;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    /**
     * 普通上传
     */
    public FileUploadResponse upload(MultipartFile file, String bucket, String hash) {
        Assert.notNull(file, "文件不能为空");
        log.debug("start file upload");

        //文件上传
        try {
            return minioHelper.uploadFile(file, bucket, hash);
        } catch (IOException e) {
            log.error("file upload error.", e);
            throw new CustomException(ResultCode.FILE_IO_ERROR.getCode(), ResultCode.FILE_IO_ERROR.getMessage());
        } catch (InsufficientDataException e) {
            log.error("insufficient data throw exception", e);
            throw new CustomException(ResultCode.MINIO_INSUFFICIENT_DATA.getCode(), ResultCode.MINIO_INSUFFICIENT_DATA.getMessage());
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            throw new CustomException(ResultCode.UNKNOWN_ERROR.getCode(), ResultCode.UNKNOWN_ERROR.getMessage());
        }
    }


    /**
     * 创建分片预上传信息并返回
     *
     * @return ↓
     * code = 2000  分片完全存在于服务器，秒传
     * code = 2001  分片部分存在服务器，续传/重传
     */
    public MultipartUploadCreateResponse createMultipartUpload(MultipartUploadRequest mur) {
        // 检验是否已经传过该文件
        FileStatus fs = checkPreMultipartUpload(mur);

        switch (fs.getStatus()) {
            case FILE_COMPLETELY_EXISTS -> {
                return MultipartUploadCreateResponse.builder()
                        .fileStatusEnum(FILE_COMPLETELY_EXISTS)
                        .build();
            }
            case FILE_NEED_MERGE -> {
                mergeMultipartUpload(mur);
                return MultipartUploadCreateResponse.builder()
                        .fileStatusEnum(FILE_COMPLETELY_EXISTS)   // 我直接合并合并，也不用前端再发请求合并了
                        .build();
            }
            case FILE_PARTLY_EXISTS -> {
                // 让前端上传缺失分片
                List<MultipartUploadCreateResponse.ChunkInfo> chunksInfo
                        = prepareChunksInfo(fs.getPartInfo().getNeedUploadPartNum(), mur);
                return MultipartUploadCreateResponse.builder()
                        .chunks(chunksInfo)
                        .uploadId(fs.getPartInfo().getUploadId())
                        .fileStatusEnum(FILE_PARTLY_EXISTS)
                        .build();
            }
            case FILE_NOT_EXISTS -> {
                // 前端上传完整分片
                String uploadId = getUploadId(mur);
                mur.setUploadId(uploadId);
                List<MultipartUploadCreateResponse.ChunkInfo> chunksInfo
                        = prepareChunksInfo(fs.getPartInfo().getNeedUploadPartNum(), mur);
                return MultipartUploadCreateResponse.builder()
                        .chunks(chunksInfo)
                        .uploadId(uploadId)
                        .fileStatusEnum(FileStatusEnum.FILE_NOT_EXISTS)
                        .build();
            }
        }
        throw new CustomException(ResultCode.UNKNOWN_ERROR.getCode(), ResultCode.UNKNOWN_ERROR.getMessage());
    }

    private String getUploadId(MultipartUploadRequest mur) {
        final MultipartUploadCreate muc = MultipartUploadCreate.builder()
                .bucketName(mur.getBucket())
                .objectName(mur.getFinalName())
                .build();
        final CreateMultipartUploadResponse cmur = minioHelper.uploadId(muc);
        return cmur.result().uploadId();
    }

    private List<MultipartUploadCreateResponse.ChunkInfo> prepareChunksInfo(
            List<Integer> needUploadPartNum,
            MultipartUploadRequest mur
    ) {
        log.debug("创建分片上传开始, createRequest: [{}]", mur);
        // 构建响应
        List<MultipartUploadCreateResponse.ChunkInfo> chunks = new ArrayList<>();
        stringRedisTemplate.opsForValue().set("file:hashToUploadId:" + mur.getHash(), mur.getUploadId());
        // 文件预上传请求参数 getPresignedObjectUrl
        Map<String, String> reqParams = new HashMap<>();
        reqParams.put("uploadId", mur.getUploadId());
        for (int i = 0; i < mur.getChunkSize(); i++) {
            if (!needUploadPartNum.contains(i + 1)) continue;
            // 为每个分片获取并设置 partNumber 分片号
            reqParams.put("partNumber", String.valueOf(i + 1));
            // 每个分片获取预上传地址
            String presignedObjectUrl = minioHelper.getPresignedObjectUrl(
                    mur.getBucket(),
                    mur.getFinalName(),
                    reqParams);
            // 兼容性处理
//            if (StringUtils.isNotBlank(minioHelper.minioProperties.getPath())) {//如果线上环境配置了域名解析，可以进行替换
//                presignedObjectUrl = presignedObjectUrl.replace(minioHelper.minioProperties.getEndpoint(), minioHelper.minioProperties.getPath());
//            }
            MultipartUploadCreateResponse.ChunkInfo item = new MultipartUploadCreateResponse.ChunkInfo();
            item.setPartNumber(i);
            item.setUploadUrl(presignedObjectUrl);
            chunks.add(item);
        }
        log.debug("创建/补偿分片上传结束, chunks: [{}]", chunks);
        return chunks;
    }

    @Resource
    private ThreadPoolExecutor executor;

    @SneakyThrows
    private FileStatus checkPreMultipartUpload(MultipartUploadRequest mur) {
        // NGY0ZDZkNmUtMjBhNy00NzYzLThjODQtOWM4OTQ2NWNmYWE1LjRiMzVjMGQ1LWEyZDgtNGJlMy05MDhhLTRlYjdkZTc2NjI1Nw==
        FileStatus fs = new FileStatus();

        CompletableFuture<Void> l1 = CompletableFuture.runAsync(() -> {
            String uploadId = stringRedisTemplate.opsForValue().get("file:hashToUploadId:" + mur.getHash());
            ListPartsResponse lpr = null;
            List<Integer> list = new ArrayList<>();
            for (int i = 1; i <= mur.getChunkSize(); i++) list.add(i);
            if (uploadId != null) {
                try {
                    lpr = minioHelper.listMultipart(MultipartUploadCreate.builder()
                            .bucketName(mur.getBucket())
                            .uploadId(uploadId)
                            .objectName(mur.getFinalName())
                            .build());
                } catch (Exception e) {
                    log.debug("分片不存在！");
                }
                if (lpr != null) {
                    // uploadId 有效
                    mur.setUploadId(uploadId);
                    // TODO 会不会有分片实际大小并不是期望的大小？
                    if (lpr.result().partList().size() == mur.getChunkSize()) {
                        // 分片完全存在，合并
                        fs.setStatus(FILE_NEED_MERGE);
                        fs.setPartInfo(new FileStatus.PartInfo(new ArrayList<>(), uploadId));
                    } else {
                        // 分片部分存在，需要补偿
                        fs.setStatus(FILE_PARTLY_EXISTS);
                        for (Part part : lpr.result().partList()) {
                            // 注意这个点！！！
                            list.remove(Integer.valueOf(part.partNumber()));
                        }
                        fs.setPartInfo(new FileStatus.PartInfo(list, uploadId));
                        // 查询都需要前端传来哪些分片
                    }
                } else {
                    // redis 有 uploadId 但实际查不到对应的分片  那就是无效uploadId
                    fs.setStatus(FileStatusEnum.FILE_NOT_EXISTS);
                    fs.setPartInfo(new FileStatus.PartInfo(list, null));
                }
            } else {
                // 没有 uploadId 必然没有分片
                fs.setStatus(FileStatusEnum.FILE_NOT_EXISTS);
                fs.setPartInfo(new FileStatus.PartInfo(list, null));
            }
        }, executor);

        Result<Item> item = CompletableFuture.supplyAsync(() -> {
            Iterator<Result<Item>> iterator = client.listObjects(ListObjectsArgs.builder().
                    bucket(mur.getBucket()).
                    prefix(mur.getHash()).build()).iterator();
            Result<Item> next = null;
            if (iterator.hasNext()) {
                next = iterator.next();
                if (iterator.hasNext())
                    throw new CustomException("为什么这里根据hash查出来了不止一个文件？bucket:" +
                            mur.getBucket() + ";hash:" +
                            mur.getHash());
                fs.setStatus(FILE_COMPLETELY_EXISTS);
                return next;
            } else {
                return null;
            }
        }, executor).get();

        if (item == null) {
            // upload有效，文件也存在？并发安全问题？
            // 实际测试显示：merge后minio会立即删除upload，使其变为无效
            // 不可能有以上状态存在
            // 所以到这里表示文件部分/完全不存在
            l1.get();
//            switch (fs.getStatus()) {
//                case FILE_COMPLETELY_EXISTS -> {
//                    return fs;
//                }
//                case FILE_PARTLY_EXISTS -> {
//                }
//            }
        }
        return fs;
    }


    /**
     * 分片合并
     */
    public FileUploadResponse mergeMultipartUpload(MultipartUploadRequest mur) {
        log.debug("文件合并开始, mur: [{}]", mur);

        // 改写fileName

        log.debug("final name：" + mur.getFinalName());

        try {
            final ListPartsResponse listMultipart = minioHelper.listMultipart(MultipartUploadCreate
                    .builder()
                    .bucketName(mur.getBucket())
                    .objectName(mur.getFinalName())
                    .maxParts(mur.getChunkSize() + 1)
                    .uploadId(mur.getUploadId())
                    .partNumberMarker(0)
                    .build()
            );
            final ObjectWriteResponse objectWriteResponse = minioHelper.completeMultipartUpload(MultipartUploadCreate.builder()
                    .bucketName(mur.getBucket())
                    .uploadId(mur.getUploadId())
                    .objectName(mur.getFinalName())
                    .parts(listMultipart.result().partList().toArray(new Part[]{}))
                    .build());

            return FileUploadResponse.builder()
                    .url(minioHelper.minioProperties.getDownloadUri() + "/" +
                            mur.getBucket() + "/" + mur.getFinalName())
                    .build();
        } catch (Exception e) {
            log.error("合并分片失败", e);
        }
        log.debug("文件合并结束, mur: [{}]", mur);
        return null;
    }


    public void remove(String fileName, String bucket) {
        if (StringUtils.isBlank(fileName)) return;
        log.debug("删除文件开始, fileName: [{}]", fileName);
        try {
            minioHelper.removeFile(fileName, bucket);
        } catch (Exception e) {
            log.error("删除文件失败", e);
        }
        log.debug("删除文件结束, fileName: [{}]", fileName);
    }


    /**
     * 查看存储bucket是否存在
     *
     * @return boolean
     */
    public Boolean bucketExists(String bucketName) {
        boolean found;
        try {
            found = client.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build()).get();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return found;
    }


    /**
     * 预览文件
     */
    public String preview(String fileName) {
        // 查看文件地址
        return preview(fileName, null);
    }

    public String preview(String fileName, String bucketName) {
        GetPresignedObjectUrlArgs build = GetPresignedObjectUrlArgs.builder().
                bucket(bucketName).
                object(fileName).
                method(Method.GET).
                build();
        try {
            return client.getPresignedObjectUrl(build);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 文件下载
     */
    public void download(String fileName, String bucketName, HttpServletResponse response) {
        GetObjectArgs objectArgs = GetObjectArgs.builder().
                bucket(bucketName)
                .object(fileName).build();
        response.setCharacterEncoding("utf-8");
        // 设置强制下载不打开
        // res.setContentType("application/force-download");
        response.addHeader("Content-Disposition", "attachment;fileName=" + fileName);
        byte[] buf = new byte[1024 * 16]; // 16kb
        int len;
        try (GetObjectResponse minioResponse = client.getObject(objectArgs).get();
             ServletOutputStream os = response.getOutputStream()) {
            while ((len = minioResponse.read(buf)) != -1) {
                os.write(buf, 0, len);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 查看文件对象
     *
     * @return 存储bucket内文件对象信息
     */
    public List<ListObjectVo> listObjects(String bucketName, String prefix) {
        Iterable<Result<Item>> results = client.listObjects(
                ListObjectsArgs.builder().
                        bucket(bucketName).
                        prefix(prefix).build());
        List<ListObjectVo> vos = new ArrayList<>();
        try {
            for (Result<Item> result : results) {
                log.debug("result：" + result.get().objectName());
                log.debug("result：" + result.get().storageClass());

                ListObjectVo l = new ListObjectVo(
                        result.get().objectName(),
                        result.get().etag()
                );
                vos.add(l);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return vos;
    }

//    /**
//     * 删除
//     */
//    public boolean remove(String fileName, String bucketName) {
//        try {
//            client.removeObject(RemoveObjectArgs.builder().
//                    bucket(bucketName).
//                    object(fileName).build());
//        } catch (Exception e) {
//            return false;
//        }
//        return true;
//    }
}
