package com.juneqqq.entity.response;


import com.juneqqq.constant.FileStatusEnum;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @author juneqqq
 * @version 1.0
 * @date 2022/4/21 9:40
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@ApiModel("分片上传创建响应类")
public class MultipartUploadCreateResponse {

    @ApiModelProperty("上传编号")
    private String uploadId;

    @ApiModelProperty("分片信息")
    private List<ChunkInfo> chunks;

    @ApiModelProperty("是否已存在，判断是否需要重传/断点续传/秒传")
    private FileStatusEnum fileStatusEnum;

    @Data
    public static class ChunkInfo {

        @ApiModelProperty("分片编号")
        private Integer partNumber;

        @ApiModelProperty("上传地址")
        private String uploadUrl;
    }

}