package com.juneqqq.entity.request;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * @author juneqqq
 * @version 1.0
 * @date 2022/4/21 10:57
 **/
@Data
@ApiModel("合并分片请求类")
public class MultipartUploadRequest {

    @ApiModelProperty("文件名称")
    private String fileName;

    @ApiModelProperty("文件hash")
    private String hash;

    @ApiModelProperty("存储名称")
    private String finalName;

    public String getFinalName() {
        return this.hash + "-" + this.fileName;
    }

    @ApiModelProperty("bucket")
    private String bucket;

    @ApiModelProperty("上传编号")
    private String uploadId;

    @ApiModelProperty("分片数量")
    private Integer chunkSize;


    @ApiModelProperty("文件大小")
    private Integer fileSize;

    @ApiModelProperty("文件类型")
    private String contentType;

    @ApiModelProperty("密码")
    private String pass;

    @ApiModelProperty("超时时间")
    private Integer expire;

    @ApiModelProperty("最大下载数")
    private Integer maxGetCount;


}