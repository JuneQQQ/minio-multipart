## 介绍
springboot集成minio实现了分片上传功能

在这位老哥的基础上修改而成
https://github.com/WinterChenS/minio-multipart

- 💪🏻 前端分片直传Minio服务端，可以自定义上传/回显进度
- 💪🏻 同时实现了普通上传，此方式由Minio内部自动分片，默认分片大小5M
- 💪🏻 完全自动化了断点续传逻辑，只需要搭配使用对应的前端代码；
- 💪🏻 日志输出非常完整，有些地方夹带了一些时间测试；

以下是重写的四个核心逻辑
```java
createMultipartUpload //创建分片上传，返回uploadId
getPresignedObjectUrl //创建文件预上传地址
listParts //获取uploadId下的所有分片文件
completeMultipartUpload //合并分片文件
```

## 快速开始
- 前端测试上传文件在`src/test/html`目录下
- 需要配置Redis

### 后端

修改配置文件`application.yml`:
```yaml
minio:
  endpoint: 
  accessKey: 
  secretKey: 
  # bucketName 不用配置bucketName 由前端选择，不存在自动创建 
  downloadUri: #配置下载的ip和端口
  path: #如果生产环境配置nginx域名解析，这里可以配置分片上传的ip和端口或者域名
  
spring:  
  redis:
    password: ...
    host: ....

```

### 前端页面

两个核心接口
```javascript
'http://localhost:15005/minio/files'

'http://localhost:15005/minio/multipart/create'
```

