package com.tongji.knowpost.model;

import lombok.Data;

import java.time.Instant;

/**
 * 知文详情查询的行映射（含作者信息）。
 */
@Data
public class KnowPostDetailRow {
    private Long id;
    private Long creatorId;
    /** 标题 */
    private String title;
    /** 摘要/描述，最多 50 字 */
    private String description;
    /** 标签数组，JSON 字符串，示例：["java","编程"]，需由调用方解析 */
    private String tags;
    /** 配图数组，JSON 字符串，需由调用方解析 */
    private String imgUrls;
    /** 正文访问地址（OSS URL）；正文不落库 */
    private String contentUrl;
    /** OSS ETag，校验对象是否被改动 */
    private String contentEtag;
    /** 正文 SHA-256 哈希（hex），防损坏/篡改 */
    private String contentSha256;
    /** 作者头像，来自 SQL 联表查询（users 表） */
    private String authorAvatar;
    /** 作者昵称，来自联表查询 */
    private String authorNickname;
    /** 作者的领域标签，JSON 字符串，需由调用方解析 */
    private String authorTagJson;
    /** 发布时间，Feed/详情展示与排序依据 */
    private Instant publishTime;
    /** 是否置顶（0/1） */
    private Boolean isTop;
    /** 可见范围：public / followers / school / private / unlisted */
    private String visible;
    /** 内容类型，目前仅 "image_text" */
    private String type;
    /** 生命周期状态：draft / review / published / deleted，预留 rejected */
    private String status;
}