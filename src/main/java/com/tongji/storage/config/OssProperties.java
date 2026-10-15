package com.tongji.storage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
// 这个注解使得当前配置类会自动去application.yml寻找相关配置
// 寻找oss.*的相关配置
@ConfigurationProperties(prefix = "oss")
public class OssProperties {
    private String endpoint;
    private String accessKeyId;
    private String accessKeySecret;
    private String bucket;
    private String publicDomain; // 可选：如自定义 CDN 域名
    private String folder = "avatars"; // 默认上传目录
}