package com.tongji.counter.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.HashMap;
import java.util.Map;

/**
 * 计数模块配置：启用调度与 Kafka，并提供字符串模板。
 */
@Configuration
@EnableScheduling // 启用 @Scheduled 定时任务（计数聚合刷写）
@EnableKafka // 启用 Kafka（计数事件生产与消费）
public class CounterConfig {

    @Bean
    public ProducerFactory<String, String> stringProducerFactory(KafkaProperties properties) {
        // 无参版本自 Spring Boot 3.2 起弃用；本项目 Kafka 未启用 SSL，显式传 null
        var props = properties.buildProducerProperties(null);
        return new DefaultKafkaProducerFactory<>(props, new StringSerializer(), new StringSerializer()); // 统一字符串序列化
    }

    @Bean
    public KafkaTemplate<String, String> stringKafkaTemplate(ProducerFactory<String, String> pf) {
        return new KafkaTemplate<>(pf);
    }

    /**
     * 计数事件专用的批量监听容器工厂。
     *
     * <p>与全局单条监听器（relation/search 的 canal 消费者）隔离：仅在共享 ConsumerFactory
     * 配置上叠加 max.poll.records，不改动全局 listener.type，避免影响其他模块。</p>
     *
     * <p>批量语义：max.poll.records 是"每批上限"而非"攒满才给"——低流量时每批可能只有
     * 1~2 条，照样立即触发，不会为攒批牺牲延迟；高流量时一次合并数百条，让消费端先聚一轮。</p>
     *
     * @param consumerFactory Spring Boot 自动配置的共享 ConsumerFactory
     * @return 批量模式的监听容器工厂（手动 ack）
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> counterBatchListenerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        // 复制共享配置并叠加批量参数：不污染其他单条监听器的 ConsumerFactory
        Map<String, Object> props = new HashMap<>(consumerFactory.getConfigurationProperties());
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500); // 每批最多 500 条

        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        return factory;
    }
}