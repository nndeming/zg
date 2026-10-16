package com.tongji.relation.api;

import com.tongji.relation.service.RelationService;
import com.tongji.auth.token.JwtService;
import com.tongji.counter.schema.UserCounterKeys;
import com.tongji.counter.schema.UserCounterSchema;
import com.tongji.profile.api.dto.ProfileResponse;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 关系接口控制器。
 * 职责：关注/取消关注、关系三态查询、关注/粉丝列表（偏移与游标）、用户维度计数读取与采样自检。
 * 缓存：ZSet 存储关注/粉丝列表；用户计数采用 SDS 固定结构（5×4 字节，大端编码），提供采样一致性校验与按需重建。
 */
@RestController
@RequestMapping("/api/v1/relation")
public class RelationController {
    private final RelationService relationService;
    private final JwtService jwtService;
    private final StringRedisTemplate redis;
    private final com.tongji.counter.service.UserCounterService userCounterService;
    private final com.tongji.relation.mapper.RelationMapper relationMapper;

    public RelationController(RelationService relationService, JwtService jwtService, StringRedisTemplate redis, com.tongji.counter.service.UserCounterService userCounterService, com.tongji.relation.mapper.RelationMapper relationMapper) {
        this.relationService = relationService;
        this.jwtService = jwtService;
        this.redis = redis;
        this.userCounterService = userCounterService;
        this.relationMapper = relationMapper;
    }

    /**
     * 发起关注。
     * @param toUserId 被关注的用户ID
     * @param jwt 认证令牌
     * @return 是否关注成功
     */
    @PostMapping("/follow")
    public boolean follow(@RequestParam("toUserId") long toUserId, @AuthenticationPrincipal Jwt jwt) {
        long uid = jwtService.extractUserId(jwt);
        return relationService.follow(uid, toUserId);
    }

    /**
     * 取消关注。
     * @param toUserId 被取消关注的用户ID
     * @param jwt 认证令牌
     * @return 是否取消成功
     */
    @PostMapping("/unfollow")
    public boolean unfollow(@RequestParam("toUserId") long toUserId, @AuthenticationPrincipal Jwt jwt) {
        long uid = jwtService.extractUserId(jwt);
        return relationService.unfollow(uid, toUserId);
    }

    /**
     * 查询与目标用户的关系三态。
     * @param toUserId 目标用户ID
     * @param jwt 认证令牌
     * @return following/followedBy/mutual 三态
     */
    @GetMapping("/status")
    public Map<String, Boolean> status(@RequestParam("toUserId") long toUserId, @AuthenticationPrincipal Jwt jwt) {
        long uid = jwtService.extractUserId(jwt);
        return relationService.relationStatus(uid, toUserId);
    }

    /**
     * 获取关注列表，支持偏移或游标分页。
     * @param userId 用户ID
     * @param limit 返回数量上限
     * @param offset 偏移量（当 cursor 为空时生效）
     * @param cursor 游标（毫秒时间戳）
     * @return 关注用户ID列表
     */
    @GetMapping("/following")
    public List<ProfileResponse> following(@RequestParam("userId") long userId,
                                @RequestParam(value = "limit", defaultValue = "20") int limit,
                                @RequestParam(value = "offset", defaultValue = "0") int offset,
                                @RequestParam(value = "cursor", required = false) Long cursor) {
        int l = Math.min(Math.max(limit, 1), 100);
        return relationService.followingProfiles(userId, l, Math.max(offset, 0), cursor);
    }

    /**
     * 获取粉丝列表，支持偏移或游标分页。
     * @param userId 用户ID
     * @param limit 返回数量上限
     * @param offset 偏移量（当 cursor 为空时生效）
     * @param cursor 游标（毫秒时间戳）
     * @return 粉丝用户ID列表
     */
    @GetMapping("/followers")
    public List<ProfileResponse> followers(@RequestParam("userId") long userId,
                                          @RequestParam(value = "limit", defaultValue = "20") int limit,
                                          @RequestParam(value = "offset", defaultValue = "0") int offset,
                                          @RequestParam(value = "cursor", required = false) Long cursor) {
        int l = Math.min(Math.max(limit, 1), 100);
        return relationService.followersProfiles(userId, l, Math.max(offset, 0), cursor);
    }

    /**
     * 获取用户维度计数（SDS）。
     * 结构与一致性：SDS 由 5 个 4 字节段组成（关注/粉丝/发文/获赞/获藏），槽位定义见 UserCounterSchema；
     * 按需触发采样校验与重建，保证接口稳定可用。
     * @param userId 用户ID
     * @return 各计数指标的值
     */
    @GetMapping("/counter")
    public Map<String, Long> counter(@RequestParam("userId") long userId) {
        // 从 Redis 读取用户计数字符串（SDS，键：ucnt:{userId}）
        String sdsKey = UserCounterKeys.sdsKey(userId);
        byte[] raw = redis.execute((RedisCallback<byte[]>)
                c -> c.stringCommands().get(sdsKey.getBytes(StandardCharsets.UTF_8)));

        // 拼接计数结果
        Map<String, Long> m = new LinkedHashMap<>();

        // 缺失或结构异常（少于 5 段 × 每段 4 字节）时尝试重建
        if (raw == null || raw.length < UserCounterSchema.SCHEMA_LEN * UserCounterSchema.FIELD_SIZE) {
            try {
                userCounterService.rebuildAllCounters(userId);
            } catch (Exception ignored) {}

            // 重建后二次读取
            raw = redis.execute((RedisCallback<byte[]>)
                    c -> c.stringCommands().get(sdsKey.getBytes(StandardCharsets.UTF_8)));

            // 仍失败则返回 0，保证接口可用性
            if (raw == null || raw.length < UserCounterSchema.SCHEMA_LEN * UserCounterSchema.FIELD_SIZE) {
                m.put("followings", 0L);
                m.put("followers", 0L);
                m.put("posts", 0L);
                m.put("likedPosts", 0L);
                m.put("favedPosts", 0L);
                return m;
            }
        }

        final byte[] buf = raw;
        // 实际段数（每段 4 字节），用于发现 SDS 被写坏的场景
        final int seg = buf.length / UserCounterSchema.FIELD_SIZE;

        // 0 基槽位读取，越界返回 0
        long sdsFollowings = UserCounterSchema.read32be(buf, UserCounterSchema.IDX_FOLLOWINGS);
        long sdsFollowers = UserCounterSchema.read32be(buf, UserCounterSchema.IDX_FOLLOWERS);

        String chkKey = "ucnt:chk:" + userId;
        // 采样校验：使用 Redis 锁限流，每用户 300s 触发一次
        Boolean doCheck = redis.opsForValue().setIfAbsent(chkKey, "1", java.time.Duration.ofSeconds(300));

        if (Boolean.TRUE.equals(doCheck)) {
            int dbFollowings = 0;
            int dbFollowers = 0;

            // 仅校验关注/粉丝的有效关系计数，与 SDS 值对比
            try {
                dbFollowings = relationMapper.countFollowingActive(userId);
            } catch (Exception ignored) {}
            try {
                dbFollowers = relationMapper.countFollowerActive(userId);
            } catch (Exception ignored) {}

            // 段数异常或值不一致则触发全量重建
            if ((seg != UserCounterSchema.SCHEMA_LEN) || sdsFollowings != (long) dbFollowings || sdsFollowers != (long) dbFollowers) {
                try {
                    userCounterService.rebuildAllCounters(userId);
                } catch (Exception ignored) {}

                // 重建后读取并直接返回最新值
                byte[] raw2 = redis.execute((RedisCallback<byte[]>)
                        c -> c.stringCommands().get(sdsKey.getBytes(StandardCharsets.UTF_8)));
                if (raw2 != null && raw2.length >= UserCounterSchema.SCHEMA_LEN * UserCounterSchema.FIELD_SIZE) {
                    m.put("followings", UserCounterSchema.read32be(raw2, UserCounterSchema.IDX_FOLLOWINGS));
                    m.put("followers", UserCounterSchema.read32be(raw2, UserCounterSchema.IDX_FOLLOWERS));
                    m.put("posts", UserCounterSchema.read32be(raw2, UserCounterSchema.IDX_POSTS));
                    m.put("likedPosts", UserCounterSchema.read32be(raw2, UserCounterSchema.IDX_LIKES_RECEIVED));
                    m.put("favedPosts", UserCounterSchema.read32be(raw2, UserCounterSchema.IDX_FAVS_RECEIVED));
                    return m;
                }
            }
        }

        // 正常路径：直接返回 SDS 中的计数值
        m.put("followings", sdsFollowings);
        m.put("followers", sdsFollowers);
        m.put("posts", UserCounterSchema.read32be(buf, UserCounterSchema.IDX_POSTS));
        m.put("likedPosts", UserCounterSchema.read32be(buf, UserCounterSchema.IDX_LIKES_RECEIVED));
        m.put("favedPosts", UserCounterSchema.read32be(buf, UserCounterSchema.IDX_FAVS_RECEIVED));
        return m;
    }
}
