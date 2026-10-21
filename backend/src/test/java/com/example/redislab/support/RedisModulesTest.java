package com.example.redislab.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisModulesTest {

    /** Lettuce keeps per-command state keyed by the keyword; a new object per call leaked a histogram each time. */
    @Test
    void reusesOneKeywordPerCommand() {
        assertThat(RedisModules.keyword("json.set")).isSameAs(RedisModules.keyword("JSON.SET"));
        assertThat(new String(RedisModules.keyword("ft.search").getBytes())).isEqualTo("FT.SEARCH");
    }
}
