package com.seatlock.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.util.List;

/**
 * Redis wiring: one template and three preloaded Lua scripts.
 */
@Configuration
public class RedisConfig {

    /**
     * {@link StringRedisTemplate} rather than a generic {@code RedisTemplate}
     * with a JSON serialiser.
     *
     * <p>Every value we store in Redis is a UUID string. Wrapping that in JDK
     * serialisation (the default for a bare {@code RedisTemplate}) would produce
     * binary blobs that are unreadable from {@code redis-cli}, larger on the
     * wire, and - importantly - impossible for a Lua script to compare with
     * {@code ==}. Our scripts compare stored values against a token; that only
     * works if what Java writes is exactly the bytes Lua reads.
     *
     * <p>It also sidesteps an entire vulnerability class: JDK deserialisation of
     * attacker-influenced bytes is a remote-code-execution primitive. Plain
     * strings cannot be a gadget chain.
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    /**
     * Scripts are loaded once at startup and sent to Redis with EVALSHA.
     *
     * <p>Spring computes the SHA-1 of the script body and calls {@code EVALSHA}.
     * Redis keeps a script cache, so after the first call only the 40-character
     * digest crosses the network, not the whole script. If the cache was cleared
     * (a restart, a {@code SCRIPT FLUSH}), Redis answers NOSCRIPT and Spring
     * transparently falls back to a full {@code EVAL} and re-caches it. We get
     * the bandwidth saving without having to handle the failure case ourselves.
     *
     * <p>The scripts live in {@code src/main/resources/redis/*.lua} rather than
     * as Java string constants so they can be syntax-highlighted, diffed, and
     * pasted straight into {@code redis-cli} for debugging.
     */
    @Bean
    @SuppressWarnings("rawtypes")   // Redis returns a heterogeneous array; see below
    public RedisScript<List> acquireHoldsScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("redis/acquire_holds.lua")));
        // The script returns a Lua table of integers (the conflicting indices).
        // Spring Data Redis can only express that as the raw List type - a
        // List<Long> generic here would be erased anyway, so the cast happens
        // once, in SeatHoldService, where it is checked and commented.
        script.setResultType(List.class);
        return script;
    }

    @Bean
    public RedisScript<Long> releaseHoldsScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("redis/release_holds.lua")));
        script.setResultType(Long.class);
        return script;
    }

    @Bean
    public RedisScript<Long> extendHoldsScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("redis/extend_holds.lua")));
        script.setResultType(Long.class);
        return script;
    }
}
