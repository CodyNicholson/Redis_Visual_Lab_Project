package com.example.redislab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs every lab endpoint against real Postgres and Redis 8 containers. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisLabIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    StringRedisTemplate redis;

    // ---- Postgres vs Redis -------------------------------------------------------------

    @Test
    void catalogFromRedisIsByteIdenticalToPostgres() throws Exception {
        mvc.perform(delete("/api/catalog/cache")).andExpect(status().isOk());

        MockHttpServletResponse db = mvc.perform(get("/api/catalog/db").param("size", "1000")).andReturn().getResponse();
        MockHttpServletResponse miss = mvc.perform(get("/api/catalog/cache").param("size", "1000")).andReturn().getResponse();
        MockHttpServletResponse hit = mvc.perform(get("/api/catalog/cache").param("size", "1000")).andReturn().getResponse();

        assertThat(db.getHeader("X-Source")).isEqualTo("postgres");
        assertThat(miss.getHeader("X-Cache")).isEqualTo("MISS");
        assertThat(hit.getHeader("X-Cache")).isEqualTo("HIT");
        assertThat(hit.getHeader("X-Rows")).isEqualTo("1000");
        assertThat(hit.getHeader("X-Sha256")).isEqualTo(db.getHeader("X-Sha256")).isEqualTo(miss.getHeader("X-Sha256"));
        assertThat(hit.getContentAsByteArray()).isEqualTo(db.getContentAsByteArray());
        assertThat(hit.getHeader("Server-Timing")).contains("redis;dur=");

        JsonNode rows = mapper.readTree(hit.getContentAsByteArray());
        assertThat(rows).hasSize(1000);
        assertThat(rows.get(0).get("id").asInt()).isEqualTo(1);
        assertThat(rows.get(0).has("avgRating")).isTrue();
    }

    @Test
    void evictingTheCatalogForcesAMiss() throws Exception {
        mvc.perform(get("/api/catalog/cache").param("size", "500"));
        JsonNode evicted = json(mvc.perform(delete("/api/catalog/cache")).andReturn().getResponse());
        assertThat(evicted.at("/data/removed").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(mvc.perform(get("/api/catalog/cache").param("size", "500")).andReturn().getResponse().getHeader("X-Cache"))
                .isEqualTo("MISS");
    }

    @Test
    void springCacheMissThenHitThenWriteThroughThenEvict() throws Exception {
        mvc.perform(post("/api/spring-cache/products/42/evict")).andExpect(status().isOk());
        JsonNode first = passing(get("/api/spring-cache/products/42"));
        JsonNode second = passing(get("/api/spring-cache/products/42"));
        assertThat(first.at("/data/cacheHit").asBoolean()).isFalse();
        assertThat(second.at("/data/cacheHit").asBoolean()).isTrue();
        assertThat(second.at("/data/product/id").asInt()).isEqualTo(42);

        mvc.perform(get("/api/catalog/cache").param("size", "100"));
        JsonNode updated = passing(put("/api/spring-cache/products/42/price").param("price", "12.34"));
        assertThat(mvc.perform(get("/api/catalog/cache").param("size", "100")).andReturn().getResponse().getHeader("X-Cache"))
                .as("catalog pages holding the old price were invalidated").isEqualTo("MISS");
        assertThat(updated.at("/data/product/price").asDouble()).isEqualTo(12.34);
        assertThat(passing(get("/api/spring-cache/products/42")).at("/data/product/price").asDouble()).isEqualTo(12.34);

        passing(post("/api/spring-cache/products/42/evict"));
        assertThat(redis.hasKey("lab:spring:product::42")).isFalse();
    }

    // ---- Every lab button --------------------------------------------------------------

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "POST, /api/catalog/benchmark?size=2000&iterations=3",
            "POST, /api/lab/strings",
            "POST, /api/lab/strings/counter?threads=8&increments=100",
            "POST, /api/lab/ttl?seconds=10",
            "POST, /api/lab/hashes",
            "POST, /api/lab/lists/enqueue?count=5",
            "POST, /api/lab/lists/work?count=3",
            "POST, /api/lab/sets",
            "POST, /api/lab/leaderboard?rounds=20",
            "POST, /api/lab/ratelimit/burst?requests=12",
            "POST, /api/lab/ratelimit/hit",
            "POST, /api/lab/lock?workers=4",
            "POST, /api/lab/transactions/transfer?amount=25",
            "POST, /api/lab/transactions/watch",
            "POST, /api/lab/lua?stock=20&buyers=100",
            "POST, /api/lab/pipeline?commands=2000",
            "POST, /api/lab/pubsub?channel=lab:chat&message=ping",
            "POST, /api/lab/streams/produce?count=5",
            "POST, /api/lab/hyperloglog?visits=50000",
            "POST, /api/lab/bitmaps?users=1000",
            "POST, /api/lab/bloom?names=5000",
            "GET, /api/lab/bloom/check?name=user_7",
            "POST, /api/lab/geo",
            "POST, /api/lab/json",
            "POST, /api/lab/search?q=leather&maxPrice=200",
            "POST, /api/lab/timeseries?hours=6",
            "POST, /api/lab/vectors?color=%23e4572e",
            "POST, /api/lab/vectors?color=%233a86ff&onlyLight=true",
            "GET, /api/lab/server",
            "POST, /api/lab/server/bgsave",
    })
    void everyDemoPassesItsSelfChecks(String method, String path) throws Exception {
        JsonNode result = passing("GET".equals(method) ? get(path) : post(path));
        assertThat(result.get("commands")).isNotEmpty();
        assertThat(result.get("checks")).isNotEmpty();
    }

    // ---- Behaviour worth asserting precisely -------------------------------------------

    @Test
    void luaNeverOversellsWhileReadModifyWriteMay() throws Exception {
        JsonNode data = passing(post("/api/lab/lua").param("stock", "10").param("buyers", "80")).get("data");
        assertThat(data.get("atomicSold").asInt()).isEqualTo(10);
        assertThat(data.get("atomicStockLeft").asInt()).isZero();
    }

    @Test
    void rateLimiterAdmitsFiveThenBlocks() throws Exception {
        JsonNode data = passing(post("/api/lab/ratelimit/burst").param("requests", "8")).get("data");
        List<Boolean> allowed = new ArrayList<>();
        data.get("results").forEach(r -> allowed.add(r.get("allowed").asBoolean()));
        assertThat(allowed).containsExactly(true, true, true, true, true, false, false, false);
    }

    @Test
    void streamConsumerGroupDeliversPendingAndRecovers() throws Exception {
        passing(post("/api/lab/streams/reset"));
        passing(post("/api/lab/streams/produce").param("count", "5"));
        JsonNode unacked = passing(post("/api/lab/streams/consume")
                .param("consumer", "worker-a").param("count", "3").param("ack", "false")).get("data");
        assertThat(unacked.get("delivered")).hasSize(3);
        assertThat(unacked.get("pendingTotal").asLong()).isEqualTo(3);

        JsonNode rest = passing(post("/api/lab/streams/consume").param("consumer", "worker-b").param("count", "10")).get("data");
        assertThat(rest.get("delivered")).hasSize(2);

        JsonNode recovered = passing(post("/api/lab/streams/recover").param("consumer", "worker-b")).get("data");
        assertThat(recovered.get("claimed")).hasSize(3);
        assertThat(recovered.get("pendingTotal").asLong()).isZero();
    }

    @Test
    void keysExpireOnTheirTtl() throws Exception {
        passing(post("/api/lab/ttl").param("seconds", "2"));
        Thread.sleep(2600);
        JsonNode keys = json(mvc.perform(get("/api/lab/ttl")).andReturn().getResponse()).at("/data/keys");
        assertThat(keys.get(0).get("exists").asBoolean()).as("OTP expired").isFalse();
        assertThat(keys.get(1).get("exists").asBoolean()).as("session lives twice as long").isTrue();
        assertThat(keys.get(2).get("ttlMs").asLong()).as("persisted key has no TTL").isEqualTo(-1);
    }

    @Test
    void searchFiltersByCategoryAndPrice() throws Exception {
        JsonNode data = passing(post("/api/lab/search").param("q", "chair").param("category", "Office")
                .param("maxPrice", "300")).get("data");
        assertThat(data.get("query").asText()).isEqualTo("(chair*) @category:{Office} @price:[0 300.0]");
        assertThat(data.get("hits")).as("documents parsed from the reply").isNotEmpty();
        assertThat(data.get("hits").get(0).get("name").asText()).containsIgnoringCase("chair");
        data.get("hits").forEach(hit -> {
            assertThat(hit.get("category").asText()).isEqualTo("Office");
            assertThat(hit.get("price").asDouble()).isLessThanOrEqualTo(300);
        });
    }

    @Test
    @Order(Integer.MAX_VALUE)
    void resetRemovesEveryLabKey() throws Exception {
        passing(post("/api/lab/sets"));
        passing(post("/api/lab/reset"));
        assertThat(json(mvc.perform(get("/api/lab/server")).andReturn().getResponse()).at("/data/labKeys").asInt()).isZero();
    }

    private JsonNode passing(MockHttpServletRequestBuilder request) throws Exception {
        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        JsonNode result = json(response);
        result.get("checks").forEach(check -> assertThat(check.get("passed").asBoolean())
                .as(result.get("demo").asText() + ": " + check.get("name").asText() + " (" + check.get("detail").asText() + ")")
                .isTrue());
        return result;
    }

    private JsonNode json(MockHttpServletResponse response) throws Exception {
        return mapper.readTree(response.getContentAsByteArray());
    }
}
