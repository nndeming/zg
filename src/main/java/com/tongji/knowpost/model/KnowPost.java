package com.tongji.knowpost.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/**
 * 知文实体类
 */
public class KnowPost {
    /** 主键，雪花算法在业务层生成（非自增） */
    private Long id;
    /** 主分类 ID，指向分类表 */
    private Long tagId;
    /** JSON 字符串，示例：["java","编程"]。存标签名而非 ID，比 tagId 灵活但没有外键约束 */
    private String tags;
    /** 标题，最长 256 字符 */
    private String title;
    /** 摘要/描述，最多 50 字，用于列表页展示 */
    private String description;
    /** 正文访问地址（OSS URL 或签名 URL）；正文不落库，只存 OSS，以下 content_* 为指针与校验信息 */
    private String contentUrl;
    /** 正文在 OSS 中的对象 Key，删除/刷新时定位对象的依据 */
    private String contentObjectKey;
    /** OSS ETag，用于校验对象是否被改动 */
    private String contentEtag;
    /** 正文字节大小 */
    private Long contentSize;
    /** 正文 SHA-256 哈希（hex），内容指纹，防损坏/篡改 */
    private String contentSha256;
    /** 作者 ID，外键 → users(id) */
    private Long creatorId;
    /** 是否置顶（0/1），配合索引支持置顶排序 */
    private Boolean isTop;
    /** 内容类型，目前仅 "image_text"（图文），预留扩展 */
    private String type;
    /** 可见范围：public / followers / school / private（仅自己）/ unlisted（不公开列出），默认 public */
    private String visible;
    /** JSON 字符串，示例：["https://...","https://..."] */
    private String imgUrls;
    /** 视频 URL，一期不使用，预留字段 */
    private String videoUrl;
    /** 生命周期状态：draft 草稿 / review 审核中 / published 已发布 / deleted 已删除；预留 rejected 驳回 */
    private String status;
    /** 创建时间，数据库 DEFAULT CURRENT_TIMESTAMP 自动维护 */
    private Instant createTime;
    /** 更新时间，数据库 ON UPDATE CURRENT_TIMESTAMP 自动维护 */
    private Instant updateTime;
    /** 发布时间，业务代码在发布时显式写入，草稿为 NULL；Feed 排序依据 */
    private Instant publishTime;
}