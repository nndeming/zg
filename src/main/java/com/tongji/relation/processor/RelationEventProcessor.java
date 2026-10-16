package com.tongji.relation.processor;

import com.tongji.relation.event.RelationEvent;
import com.tongji.relation.mapper.RelationMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.tongji.counter.service.UserCounterService;
import org.springframework.stereotype.Service;
import java.time.Duration;

/**
 * 关系事件处理器。
 * 职责：消费 FollowCreated/FollowCanceled 事件，落库更新粉丝表，维护关注/粉丝 ZSet 缓存与 TTL，并原子更新用户维度计数（SDS）。
 * 幂等策略：不依赖 Redis 去重键，而是由 follower 表唯一键 + rel_status 状态守卫保证——
 * 只有数据库关系真正发生“非激活 -> 激活 / 激活 -> 非激活”迁移时才刷缓存与计数，
 * Kafka at-least-once 重放、重复事件、乱序重试均安全收敛，且支持取消后重新关注的正确计数。
 */
@Service
public class RelationEventProcessor {
    private final RelationMapper mapper;
    private final StringRedisTemplate redis;
    private final UserCounterService userCounterService;

    public RelationEventProcessor(RelationMapper mapper, StringRedisTemplate redis, UserCounterService userCounterService) {
        this.mapper = mapper;
        this.redis = redis;
        this.userCounterService = userCounterService;
    }

    /**
     * 处理关系事件：先以幂等方式落库，仅当关系状态真实迁移时才维护缓存与计数。
     * @param evt 关系事件
     */
    public void process(RelationEvent evt) {
        if ("FollowCreated".equals(evt.type())) {
            // 1. 先尝试重新激活“已存在但已取消”的关系（取消后再次关注的场景）
            int reactivated = mapper.reactivateFollower(evt.toUserId(), evt.fromUserId());
            if (reactivated == 0) {
                // 2. 无失效关系可激活：尝试插入新行；唯一键冲突（重放/并发）时返回 0
                int inserted = mapper.insertFollower(evt.id(), evt.toUserId(), evt.fromUserId(), 1);
                if (inserted == 0) {
                    // 关系已处于激活态，本次为重复事件重放，缓存与计数此前已生效，直接返回
                    return;
                }
            }
            // 走到这里：关系本次由“非激活”转为“激活”，需要刷缓存与计数
            long now = System.currentTimeMillis();

            // 更新关注表与粉丝表缓存：ZSet 按时间分数维护最近项，设置短 TTL 减少陈旧数据
            redis.opsForZSet().add("uf:flws:" + evt.fromUserId(), String.valueOf(evt.toUserId()), now);
            redis.opsForZSet().add("uf:fans:" + evt.toUserId(), String.valueOf(evt.fromUserId()), now);
            redis.expire("uf:flws:" + evt.fromUserId(), Duration.ofHours(2));
            redis.expire("uf:fans:" + evt.toUserId(), Duration.ofHours(2));

            // 更新关注数与粉丝数
            userCounterService.incrementFollowings(evt.fromUserId(), 1);
            userCounterService.incrementFollowers(evt.toUserId(), 1);
        } else if ("FollowCanceled".equals(evt.type())) {
            // 仅“激活 -> 取消”计入影响行数：重复取消/重放返回 0，跳过缓存与计数
            int updated = mapper.cancelFollower(evt.toUserId(), evt.fromUserId());
            if (updated == 0) {
                return;
            }

            // 更新关注表与粉丝表缓存：移除 ZSet 项并刷新 TTL
            redis.opsForZSet().remove("uf:flws:" + evt.fromUserId(), String.valueOf(evt.toUserId()));
            redis.opsForZSet().remove("uf:fans:" + evt.toUserId(), String.valueOf(evt.fromUserId()));
            redis.expire("uf:flws:" + evt.fromUserId(), Duration.ofHours(2));
            redis.expire("uf:fans:" + evt.toUserId(), Duration.ofHours(2));

            // 更新关注数与粉丝数
            userCounterService.incrementFollowings(evt.fromUserId(), -1);
            userCounterService.incrementFollowers(evt.toUserId(), -1);
        }
    }
}
