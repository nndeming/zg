package com.tongji.knowpost.model;

import lombok.Data;

import java.time.Instant;

/**
 * Mapper 原始行映射（从 DB 读取）。
 */
@Data
public class KnowPostFeedRow {
    private Long id;
    /** 标题 */
    private String title;
    /** 摘要/描述，最多 50 字 */
    private String description;
    /** 标签数组，JSON 字符串，需由调用方解析 */
    private String tags;
    /** 配图数组，JSON 字符串，需由调用方解析 */
    private String imgUrls;
    /** 作者头像，来自 SQL 联表查询（users 表） */
    private String authorAvatar;
    /** 作者昵称，来自联表查询 */
    private String authorNickname;
    /** 作者的领域标签，JSON 字符串，需由调用方解析 */
    private String authorTagJson;
    /** 发布时间，Feed 卡片排序依据 */
    private Instant publishTime;
    /** 是否置顶（0/1），用于置顶优先排序 */
    private Boolean isTop;
}