package com.tongji.knowpost.service;

import com.tongji.knowpost.api.dto.KnowPostDetailResponse;

import java.util.List;

/**
 * 知文业务接口。
 */
public interface KnowPostService {

    /**
     * 创建草稿 并 返回文章ID
     * @param creatorId 创建者ID
     * @return
     */
    long createDraft(long creatorId);

    void confirmContent(long creatorId, long id, String objectKey, String etag, Long size, String sha256);

    void updateMetadata(long creatorId, long id, String title, Long tagId, List<String> tags, List<String> imgUrls, String visible, Boolean isTop, String description);

    /**
     * 发布知文
     * @param creatorId 创建者ID
     * @param id 文章ID
     */
    void publish(long creatorId, long id);

    /**
     * 设置置顶状态
     * @param creatorId 创建者ID
     * @param id 文章ID
     * @param isTop 是否置顶
     */
    void updateTop(long creatorId, long id, boolean isTop);

    /**
     * 设置可见性(权限)
     * @param creatorId 创建者ID
     * @param id 文章ID
     * @param visible 可见性
     */
    void updateVisibility(long creatorId, long id, String visible);

    void delete(long creatorId, long id);

    /**
     * 知文详情（公开：published+public；非公开需作者本人）。
     * @param id    知文ID
     * @param currentUserIdNullable 当前用户 ID（可空，用于判断权限与点赞状态）
     * @return
     */
    KnowPostDetailResponse getDetail(long id, Long currentUserIdNullable);
}