package com.tongji.counter.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.counter.schema.CounterKeys;
import com.tongji.counter.schema.CounterSchema;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计数事件聚合与刷写消费者。
 *
 * <p>职责：</p>
 * - 以批量模式消费点赞/收藏等增量事件，先在 JVM 内按 (聚合桶, 指标) 合并净增量，
 *   再通过管道一次性写入 Redis 聚合桶（Hash），并登记目录索引 Set；
 * - 以固定延迟定时任务将聚合增量折叠到 SDS 固定结构计数；
 * - 刷写成功后按值扣减聚合字段，避免重复加算；
 * - 目录索引替代 KEYS 全库扫描：只遍历"有待刷写增量"的桶，扫描量与全库 key 总量解耦。
 *
 * <p>一致性说明（at-least-once）：Kafka 位点在 Redis 写入成功后才提交，崩溃重放只会让
 * 同一 delta 重复累加进聚合桶（HINCRBY 幂等），不会丢失；SDS 折叠的重复应用概率极低，
 * 见 flush 的注释。</p>
 */
@Service
public class CounterAggregationConsumer {

    private static final Logger log = LoggerFactory.getLogger(CounterAggregationConsumer.class);

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> incrScript;
    private final DefaultRedisScript<Long> decrScript;
    private final DefaultRedisScript<Long> cleanupScript;

    // 使用 Redis Hash 作为持久化聚合桶：agg:{schema}:{etype}:{eid} ，field=idx ，value=delta
    public CounterAggregationConsumer(ObjectMapper objectMapper, StringRedisTemplate redis) {
        this.objectMapper = objectMapper;
        this.redis = redis;
        this.incrScript = new DefaultRedisScript<>();
        this.incrScript.setResultType(Long.class);
        this.incrScript.setScriptText(INCR_FIELD_LUA); // 原子将增量折叠到 SDS 指定段（大端 32 位）

        this.decrScript = new DefaultRedisScript<>();
        this.decrScript.setResultType(Long.class);
        this.decrScript.setScriptText(DECR_FIELD_LUA);

        this.cleanupScript = new DefaultRedisScript<>();
        this.cleanupScript.setResultType(Long.class);
        this.cleanupScript.setScriptText(CLEANUP_INDEX_LUA); // 原子清理目录索引，防并发交错丢增量
    }

    /**
     * 批量消费计数事件：JVM 内合并净增量后一次管道写入。
     *
     * <p>合并的意义：热点实体的高频事件（如爆款每秒上千次点赞）先按 (实体, 指标) 折叠成
     * 单个净 delta，写 Redis 的次数从"事件数"降为"实体数"，热点 field 的写压力随流量自动伸缩。
     * 例如一秒内同一篇文章的 +1、+1、-1 三个事件，在这里就被折叠成 +1 一笔。</p>
     *
     * <p>单条解析失败仅跳过并记日志，不阻塞整批：避免毒消息反复重放卡死整个分区。</p>
     *
     * @param records 本批消息（上限由 max.poll.records 控制，不足上限也会立即触发，不为攒批牺牲延迟）
     * @param ack 位点确认对象，Redis 写入成功后手动提交整批位点
     */
    @KafkaListener(topics = CounterTopics.EVENTS, groupId = "counter-agg",
            containerFactory = "counterBatchListenerFactory")
    public void onBatch(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        // merged 的结构：聚合桶 key -> (指标 idx -> 净增量)
        // 外层按实体分桶，内层按指标合并：同一批里同一 (实体, 指标) 的所有事件先加减抵消，
        // 最终每个 (实体, 指标) 只产生一次 HINCRBY

        // merged是聚合结果
        // key: CounterKeys.aggKey()生成的字符串, 类似于 agg:v1:knowpost:12345
        // value: key -> 指标下标; value -> 本批净增量
        Map<String, Map<Integer, Long>> merged = new HashMap<>();

        int discarded = 0;
        for (var record : records) {
            // 遍历每一条消息
            CounterEvent evt;
            try {
                // 转为CounterEvent类对象
                evt = objectMapper.readValue(record.value(), CounterEvent.class);
            } catch (Exception ex) {
                // 解析失败：跳过该条，避免毒消息阻塞整批重试
                discarded++;
                continue;
            }
            // computeIfAbsent 定位该实体的内层 Map；merge 把本条 delta 累加到对应指标上
            merged.computeIfAbsent(
                            CounterKeys.aggKey(evt.getEntityType(), evt.getEntityId()),
                            k -> new HashMap<>())
                    .merge(evt.getIdx(), (long) evt.getDelta(), Long::sum);
        }
        if (discarded > 0) {
            log.warn("计数事件批量解析：丢弃 {} 条非法消息", discarded);
        }

        if (!merged.isEmpty()) {
            // 管道一次性发送：不逐条等响应，整批命令一次网络往返全部发出去，
            // 降低批量场景下的 RTT 开销；StringRedisConnection 保证键值按字符串序列化
            redis.executePipelined((RedisCallback<Object>) connection -> {
                StringRedisConnection conn = (StringRedisConnection) connection;
                for (Map.Entry<String, Map<Integer, Long>> bucket : merged.entrySet()) {
                    for (Map.Entry<Integer, Long> field : bucket.getValue().entrySet()) {
                        // HINCRBY 天然幂等：崩溃重放导致同一 delta 重复累加，语义不变
                        conn.hIncrBy(bucket.getKey(), String.valueOf(field.getKey()), field.getValue());
                    }
                    // 登记目录索引：flush 只扫描这个 Set，替代 KEYS 全库扫描
                    // SADD 幂等：同一桶被多次登记只会存一份
                    conn.sAdd(CounterKeys.AGG_INDEX, bucket.getKey());
                }
                return null;
            });
        }
        // 写 Redis 成功后才提交整批位点；中途抛异常则不提交，整批等待 Kafka 重放（at-least-once）
        ack.acknowledge();
    }

    /**
     * 将聚合增量刷写到 SDS 固定结构计数。
     * 固定延迟 1s，保证秒级最终一致性。
     *
     * <p>只遍历目录索引中的"脏桶"，索引为空时零开销返回。</p>
     *
     * <p>多实例并发刷写同一桶是安全的：INCR/DECR 均按"值"操作而非清空字段，
     * 并发重叠仅存在"同一 delta 被重复折叠"的极小概率窗口（①②两步非原子，
     * 严格 exactly-once 需合并为单个转账 Lua，当前实现选择容忍）。</p>
     */
    @Scheduled(fixedDelay = 1000L)
    public void flush() {
        Set<String> dirtyKeys = redis.opsForSet().members(CounterKeys.AGG_INDEX);
        if (dirtyKeys == null || dirtyKeys.isEmpty()) {
            return;
        }

        for (String aggKey : dirtyKeys) {
            Map<Object, Object> entries = redis.opsForHash().entries(aggKey);
            if (entries.isEmpty()) {
                // 桶已空但索引残留（如并发清理交错），顺手清除索引后跳过
                redis.opsForSet().remove(CounterKeys.AGG_INDEX, aggKey);
                continue;
            }
            // 解析 etype/eid 以定位 SDS key
            String[] parts = aggKey.split(":", 4); // agg:schema:etype:eid
            if (parts.length < 4) {
                continue;
            }

            String cntKey = CounterKeys.sdsKey(parts[2], parts[3]);

            for (Map.Entry<Object, Object> e : entries.entrySet()) {
                String field = String.valueOf(e.getKey());
                // 增量
                long delta;
                try {
                    delta = Long.parseLong(String.valueOf(e.getValue()));
                } catch (NumberFormatException nfe) {
                    continue;
                }
                if (delta == 0) continue;
                int idx;

                try {
                    idx = Integer.parseInt(field);
                } catch (NumberFormatException nfe) {
                    continue;
                }

                try {
                    redis.execute(incrScript, List.of(cntKey),
                            String.valueOf(CounterSchema.SCHEMA_LEN),
                            String.valueOf(CounterSchema.FIELD_SIZE),
                            String.valueOf(idx),
                            String.valueOf(delta));

                    // 成功后扣减该字段，若结果为0则删除，避免并发写入丢失
                    redis.execute(decrScript, List.of(aggKey), field, String.valueOf(delta));
                } catch (Exception ex) {
                    // 留存字段，下一轮重试
                }
            }
            // 原子清理索引：桶的所有字段被 HDEL 后 Redis 会自动删除空 Hash，
            // 仅当桶确实不存在时才从索引移除。
            // 不能用"删桶 + SREM"两步完成：其间若有新事件完成 HINCRBY+SADD，
            // 删桶会连同新增量一起删掉并移除索引，造成增量"在桶里却永远不会被刷"的丢失窗口。
            redis.execute(cleanupScript, List.of(aggKey, CounterKeys.AGG_INDEX));
        }
    }

    private static final String INCR_FIELD_LUA = """

            local cntKey = KEYS[1]
            local schemaLen = tonumber(ARGV[1])
            local fieldSize = tonumber(ARGV[2]) -- 固定为4
            local idx = tonumber(ARGV[3])
            local delta = tonumber(ARGV[4])

            local function read32be(s, off)
              local b = {string.byte(s, off+1, off+4)}
              local n = 0
              for i=1,4 do n = n * 256 + b[i] end
              return n
            end

            local function write32be(n)
              local t = {}
              for i=4,1,-1 do t[i] = n % 256; n = math.floor(n/256) end
              return string.char(unpack(t))
            end

            local cnt = redis.call('GET', cntKey)
            if not cnt then cnt = string.rep(string.char(0), schemaLen * fieldSize) end
            local off = idx * fieldSize
            local v = read32be(cnt, off) + delta
            if v < 0 then v = 0 end
            local seg = write32be(v)
            cnt = string.sub(cnt, 1, off) .. seg .. string.sub(cnt, off+fieldSize+1)
            redis.call('SET', cntKey, cnt)
            return 1
            """;

    private static final String DECR_FIELD_LUA = """
            local key = KEYS[1]
            local field = ARGV[1]
            local delta = tonumber(ARGV[2])
            local v = redis.call('HINCRBY', key, field, -delta)
            if v == 0 then
                redis.call('HDEL', key, field)
            end
            return v
            """;

    // 原子清理目录索引：仅当聚合桶已不存在（所有增量均已刷写并随 HDEL 自动清键）时才 SREM。
    // Lua 保证检查与移除之间不会插入并发的 HINCRBY+SADD，
    // 杜绝"新增量刚写入就被误删索引、从此不再被刷写"的竞态。
    private static final String CLEANUP_INDEX_LUA = """
            if redis.call('EXISTS', KEYS[1]) == 0 then
              redis.call('SREM', KEYS[2], KEYS[1])
            end
            return 1
            """;
}
