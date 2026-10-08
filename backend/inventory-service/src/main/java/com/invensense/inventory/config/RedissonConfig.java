package com.invensense.inventory.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    /**
     * Redisson client. Not created when unsafe mode is on, so tests
     * that don't have Redis can still run.
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "inventory.concurrency.unsafe-mode", havingValue = "false", matchIfMissing = true)
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort)
                .setConnectionPoolSize(32)
                .setConnectionMinimumIdleSize(8);
        log.info("Redisson client connecting to redis://{}:{}", redisHost, redisPort);
        return Redisson.create(config);
    }
}
