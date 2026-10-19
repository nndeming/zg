package com.tongji.knowpost.api.dto;

import java.util.List;

/**
 * 首页 Feed 单条记录。
 */
public record FeedItemResponse(
        /** 知文ID */
        String id,
        /** 标题 */
        String title,
        /** 描述/摘要, 最多50字 */
        String description,
        /** 封面图url */
        String coverImage,
        /** 内容标签列表 */
        List<String> tags,
        /** 作者头像url */
        String authorAvatar,
        /** 作者昵称 */
        String authorNickname,
        /** 作者的领域标签(注意这是作者个人资料的标签) */
        String tagJson,
        /** 点赞数 */
        Long likeCount,
        /** 收藏数 */
        Long favoriteCount,
        /** 当前登录用户是否已点赞; 匿名用户为null/false */
        Boolean liked,
        /** 当前登录用户是否已收藏; 同上 */
        Boolean faved,
        /** 是否置顶 */
        Boolean isTop
) {}