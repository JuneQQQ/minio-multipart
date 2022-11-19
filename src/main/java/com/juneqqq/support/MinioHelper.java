package com.juneqqq.support;


import com.juneqqq.config.PearlMinioClient;
import com.juneqqq.entity.base.ResultCode;
import com.juneqqq.entity.response.FileUploadResponse;
import com.juneqqq.entity.vo.MultipartUploadCreate;
import com.juneqqq.exception.CustomException;
import io.minio.*;
import io.minio.errors.*;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.utils.IOUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriUtils;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import com.juneqqq.config.MinioProperties;

/**
 * @author juneqqq
 * @version 1.0
 * @date 2022/4/12 10:21
 * @description minio 操作类
 **/
@Slf4j
@Component
public class MinioHelper {

    @Resource
    private PearlMinioClient client;

    @Resource
    public MinioProperties minioProperties;


//    public List<ListObjectVo> listObjects(String bucketName, String prefix) {
//        Iterable<Result<Item>> results = minioAsyncClient.listObjects(
//                ListObjectsArgs.builder().
//                        bucket(bucketName).
//                        prefix(prefix).build());
//        List<ListObjectVo> vos = new ArrayList<>();
//        try {
//            for (Result<Item> result : results) {
//                log.debug("result：" + result.get().objectName());
//                log.debug("result：" + result.get().storageClass());
//
//                ListObjectVo l = new ListObjectVo(
//                        result.get().objectName(),
//                        result.get().etag()
//                );
//                vos.add(l);
//            }
//        } catch (Exception e) {
//            e.printStackTrace();
//            return null;
//        }
//        return vos;
//    }


    /**
     * 桶存在与否
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
     * 上传单个文件
     */
    public FileUploadResponse uploadFile(MultipartFile multipartFile, String bucket, String hash) throws InsufficientDataException, IOException, NoSuchAlgorithmException, InvalidKeyException, XmlParserException, InternalException, InvalidKeyException, InternalException {

        boolean found = bucketExists(bucket);
        if (!found) {
            log.debug("create bucket: [{}]", bucket);
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        } else {
            log.debug("bucket '{}' already exists.", bucket);
        }

        try (InputStream inputStream = multipartFile.getInputStream()) {

            // 上传文件的名称
            // asdsad-xx.json
            String uploadName = hash + "-" + multipartFile.getOriginalFilename();

            // PutObjectOptions，上传配置(文件大小，内存中文件分片大小)
            PutObjectArgs putObjectOptions = PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(uploadName)
                    .contentType(multipartFile.getContentType())
                    .stream(inputStream, multipartFile.getSize(), -1)
                    .build();
            log.debug("prepare upload！");
            long l1 = System.currentTimeMillis();
            client.putObject(putObjectOptions).get();
            long l2 = System.currentTimeMillis();
            log.debug("file-size：" + multipartFile.getSize());
            log.debug("服务端->Minio端【上传耗时】：" + (l2 - l1));

            final String url = minioProperties.getEndpoint() + "/" + bucket + "/" + UriUtils.encode(uploadName, StandardCharsets.UTF_8);

            // 返回访问路径
            return FileUploadResponse.builder()
                    .uploadName(uploadName)
                    .url(url)
                    .realName(multipartFile.getOriginalFilename())
                    .size(multipartFile.getSize())
                    .bucket(bucket)
                    .build();
        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void removeFile(String fileName, String bucket) throws ServerException, InsufficientDataException, ErrorResponseException, IOException, NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException, InternalException {
        client.removeObject(RemoveObjectArgs.builder()
                .bucket(bucket)
                .object(fileName)
                .build());
    }

    /**
     * 文件下载
     * <p>
     * TODO
     */
    public void download(HttpServletResponse response, String hash, String bucket) throws Exception {
        InputStream in = null;
        try {
            //获取文件对象 stat原信息
            StatObjectResponse stat = client.statObject(StatObjectArgs.builder().bucket(bucket).object(hash).build()).get();
            response.setContentType(stat.contentType());
            response.setHeader("Content-disposition", "attachment;filename=down");
            in = client.getObject(GetObjectArgs.builder().bucket(bucket).object(hash).build()).get();
            IOUtils.copy(in, response.getOutputStream());
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    log.error(e.getMessage(), e);
                }
            }
        }
    }


    /**
     * @param multipartUploadCreate 自建参数
     * @return
     */
    public CreateMultipartUploadResponse uploadId(MultipartUploadCreate multipartUploadCreate) {
        try {
            return client.createMultipartUpload(
                    multipartUploadCreate.getBucketName(),
                    multipartUploadCreate.getRegion(),
                    multipartUploadCreate.getObjectName(),
                    multipartUploadCreate.getHeaders(),
                    multipartUploadCreate.getExtraQueryParams());
        } catch (Exception e) {
            log.error("获取上传编号失败", e);
            throw new CustomException(ResultCode.UNKNOWN_ERROR.getCode(), e.getMessage());
        }
    }


    /**
     * 获取分片上传的信息
     *
     * @param multipartUploadCreate
     * @return
     */
    public CreateMultipartUploadResponse createMultipartUpload(MultipartUploadCreate multipartUploadCreate) {
        try {
            return client.createMultipartUpload(
                    multipartUploadCreate.getBucketName(),
                    multipartUploadCreate.getRegion(),
                    multipartUploadCreate.getObjectName(),
                    multipartUploadCreate.getHeaders(),
                    multipartUploadCreate.getExtraQueryParams());
        } catch (Exception e) {
            log.error("创建分片上传失败", e);
            throw new CustomException(ResultCode.UNKNOWN_ERROR.getCode(), e.getMessage());
        }
    }

    /**
     * 合并分片
     *
     * @param multipartUploadCreate
     * @return
     */
    public ObjectWriteResponse completeMultipartUpload(MultipartUploadCreate multipartUploadCreate) {
        try {
            return client.completeMultipartUpload(
                    multipartUploadCreate.getBucketName(),
                    multipartUploadCreate.getRegion(),
                    multipartUploadCreate.getObjectName(),  // 这里采用hash作为文件名！
                    multipartUploadCreate.getUploadId(),
                    multipartUploadCreate.getParts(),
                    multipartUploadCreate.getHeaders(),
                    multipartUploadCreate.getExtraQueryParams());
        } catch (Exception e) {
            log.error("合并分片失败", e);
            throw new CustomException(ResultCode.UNKNOWN_ERROR.getCode(), e.getMessage());
        }
    }


    /**
     *
     */
    public ListPartsResponse listMultipart(MultipartUploadCreate multipartUploadCreate) throws InsufficientDataException, IOException, NoSuchAlgorithmException, InvalidKeyException, ExecutionException, XmlParserException, InterruptedException, InternalException {
            return client.listMultipart(
                    multipartUploadCreate.getBucketName(),
                    multipartUploadCreate.getRegion(),
                    multipartUploadCreate.getObjectName(),
                    multipartUploadCreate.getMaxParts(),
                    multipartUploadCreate.getPartNumberMarker(),
                    multipartUploadCreate.getUploadId(),
                    multipartUploadCreate.getHeaders(),
                    multipartUploadCreate.getExtraQueryParams());
    }


    /**
     * 获取单个分片预上传地址
     */
    public String getPresignedObjectUrl(String bucketName, String objectName, Map<String, String> queryParams) {
        try {
            return client.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT)
                            .bucket(bucketName)
                            .object(objectName)
                            .expiry(60 * 60 * 24)
                            .extraQueryParams(queryParams)
                            .build());
        } catch (Exception e) {
            log.error("查询分片失败", e);
            throw new CustomException(ResultCode.UNKNOWN_ERROR.getCode(), e.getMessage());
        }
    }


}