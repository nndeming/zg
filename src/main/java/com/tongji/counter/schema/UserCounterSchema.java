package com.tongji.counter.schema;

import java.util.Map;
import java.util.Set;

/**
 * 用户维度计数 Schema 映射及常量定义。
 * 与文章维度 CounterSchema 对齐，统一采用 0 基坐标。
 */
public final class UserCounterSchema {

    // 使用 v1 Schema：0 基下标约定
    // 0: followings（关注数）
    // 1: followers（粉丝数）
    // 2: posts（发文数）
    // 3: likesReceived（获赞数，作者维度）
    // 4: favsReceived（获收藏数，作者维度）
    public static final int SCHEMA_LEN = 5; // 预留 5 个指标位
    public static final int FIELD_SIZE = 4; // 每段 4 字节 Int32 大端存储

    public static final int IDX_FOLLOWINGS = 0;
    public static final int IDX_FOLLOWERS = 1;
    public static final int IDX_POSTS = 2;
    public static final int IDX_LIKES_RECEIVED = 3;
    public static final int IDX_FAVS_RECEIVED = 4;

    public static final Map<String, Integer> NAME_TO_IDX = Map.of(
            "followings", IDX_FOLLOWINGS,
            "followers", IDX_FOLLOWERS,
            "posts", IDX_POSTS,
            "likedPosts", IDX_LIKES_RECEIVED,
            "favedPosts", IDX_FAVS_RECEIVED
    );

    public static final Set<String> SUPPORTED_METRICS = NAME_TO_IDX.keySet(); // 对外可请求的指标集合

    private UserCounterSchema() {}

    /**
     * 按 0 基下标读取一段 4 字节大端 Int32。
     *
     * @param buf SDS 原始字节，可为 null
     * @param idx 0 基槽位下标
     * @return 该槽位计数；数据缺失或越界时返回 0
     */
    public static long read32be(byte[] buf, int idx) {
        if (buf == null || idx < 0) return 0L;
        int off = idx * FIELD_SIZE;
        if (off + FIELD_SIZE > buf.length) return 0L;
        long n = 0L;
        for (int i = 0; i < 4; i++) n = (n << 8) | (buf[off + i] & 0xFFL);
        return n;
    }
}
