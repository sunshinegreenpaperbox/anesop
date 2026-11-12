package com.anesop.m0.impl.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 刷新令牌哈希：SHA-256 十六进制、确定性、不同输入不碰撞（V3 token_hash 唯一索引依赖）。
 */
class TokenHashingTest {

    @Test
    void producesDeterministicSha256Hex() {
        String first = TokenHashing.sha256("SYNTH-REFRESH-TOKEN");
        String second = TokenHashing.sha256("SYNTH-REFRESH-TOKEN");

        assertThat(first).isEqualTo(second).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void differentTokensProduceDifferentHashes() {
        assertThat(TokenHashing.sha256("SYNTH-REFRESH-TOKEN-1"))
                .isNotEqualTo(TokenHashing.sha256("SYNTH-REFRESH-TOKEN-2"));
    }
}
