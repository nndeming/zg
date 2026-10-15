package com.tongji.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.tongji.storage.config.OssProperties;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.net.URL;
import java.util.Date;

@Service
@RequiredArgsConstructor
public class OssStorageService {

    private final OssProperties props;

    /**
     * 上传头像, Avatar: 在互联网中有头像的含义
     * @param userId    用户id
     * @param file  头像文件
     * @return
     */
    public String uploadAvatar(long userId, MultipartFile file) {
        ensureConfigured();

        String original = file.getOriginalFilename();
        String ext = "";
        if (original != null && original.contains(".")) {
            // 拿到最后一个'.'之后的内容, 实际上就是获取文件拓展名
            ext = original.substring(original.lastIndexOf('.'));
        }
        // 拼接成存入OSS的Key
        String objectKey = props.getFolder() + "/" + userId + "-" + Instant.now().toEpochMilli() + ext;

        OSS client = new OSSClientBuilder().build(props.getEndpoint(), props.getAccessKeyId(), props.getAccessKeySecret());

        try {
            PutObjectRequest request = new PutObjectRequest(props.getBucket(), objectKey, file.getInputStream());
            client.putObject(request);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "头像文件读取失败");
        } finally {
            client.shutdown();
        }

        return publicUrl(objectKey);
    }

    /**
     * 拼接对象在 OSS 中的公开访问地址。
     * <p>
     * 优先使用配置的自定义域名（CDN / 加速域名）；未配置时回退到
     * OSS 默认的 bucket 域名（{@code https://bucket.endpoint/objectKey}）。
     * 注意：返回的地址可直接访问，前提是 bucket 或对象 ACL 为 public-read。
     *
     * @param objectKey OSS 对象键，如 avatars/123-1718000123456.png
     * @return 该对象的完整公开 URL
     */
    private String publicUrl(String objectKey) {
        // 分支 1：配置了自定义域名（CDN / 加速域名）时优先使用
        if (props.getPublicDomain() != null && !props.getPublicDomain().isBlank()) {
            // 去掉域名末尾多余的斜杠，避免拼接出双斜杠
            return props.getPublicDomain().replaceAll("/$", "") + "/" + objectKey;
        }
        // 分支 2：未配置自定义域名，回退到 OSS 默认的 bucket 域名
        return "https://" + props.getBucket() + "." + props.getEndpoint() + "/" + objectKey;
    }

    /**
     * 生成用于直传的 PUT 预签名 URL。
     * 客户端必须在上传时设置与签名一致的 Content-Type。
     * 注意服务端只生成URL, 后续是前端对这个URL发起PUT请求, 在Body中附带上文件的字节流
     *
     * @param objectKey 目标对象键
     * @param contentType 上传内容类型（如 text/markdown, image/png）
     * @param expiresInSeconds 有效期秒数（建议 300-900）
     * @return 可直接用于 PUT 上传的预签名 URL
     */
    public String generatePresignedPutUrl(String objectKey, String contentType, int expiresInSeconds) {
        ensureConfigured();
        // 用 AK/SK 创建 OSS 客户端，签名计算在服务端完成
        OSS client = new OSSClientBuilder().build(props.getEndpoint(), props.getAccessKeyId(), props.getAccessKeySecret());
        try {
            // 有效期 = 当前时间 + 传入的秒数，过期后 OSS 会拒绝该 URL 的上传请求
            Date expiration = new Date(System.currentTimeMillis() + expiresInSeconds * 1000L);
            // 构造 PUT 方式的预签名请求，针对指定的 bucket 和对象键
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(props.getBucket(), objectKey, HttpMethod.PUT);
            request.setExpiration(expiration);
            // Content-Type 会被纳入签名：客户端上传时必须携带完全相同的值，否则签名校验失败
            if (contentType != null && !contentType.isBlank()) {
                request.setContentType(contentType);
            }
            // 生成带签名参数的完整 URL，直接返回给前端使用
            URL url = client.generatePresignedUrl(request);
            return url.toString();
        } finally {
            client.shutdown();
        }
    }

    private void ensureConfigured() {
        if (props.getEndpoint() == null || props.getAccessKeyId() == null || props.getAccessKeySecret() == null || props.getBucket() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "对象存储未配置");
        }
    }
}
