package com.tongji.knowpost.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 知文详情响应。
 */
public record KnowPostDetailResponse(
        /** 知文 ID（字符串形式的雪花 ID） */
        String id,
        /** 标题 */
        String title,
        /** 摘要/描述，最多 50 字 */
        String description,
        /** 正文访问地址（OSS URL）；正文不落库，由前端自行拉取 */
        String contentUrl,
        /** 配图 URL 列表（由 img_urls JSON 解析而来） */
        List<String> images,
        /** 标签列表（由 tags JSON 解析而来） */
        List<String> tags,
        /** 作者 ID */
        String authorId,
        /** 作者头像 URL，来自联表查询 */
        String authorAvatar,
        /** 作者昵称，来自联表查询 */
        String authorNickname,
        /** 作者的领域标签，JSON 字符串原样透传 */
        String authorTagJson,
        /** 点赞数（缓存命中时由计数服务实时刷新） */
        Long likeCount,
        /** 收藏数（同上，实时刷新） */
        Long favoriteCount,
        /** 当前用户是否已点赞；匿名用户为 null，由 enrich 阶段按登录态填充 */
        Boolean liked,
        /** 当前用户是否已收藏；匿名用户为 null，由 enrich 阶段填充 */
        Boolean faved,
        /** 是否置顶 */
        Boolean isTop,
        /** 可见范围：public / followers / school / private / unlisted */
        String visible,
        /** 内容类型，目前仅 "image_text" */
        String type,
        /** 发布时间 */
        Instant publishTime
) {}