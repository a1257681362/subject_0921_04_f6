package com.evops.rental;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 订单状态机集成测试：内存 H2，全链路（拦截器/事务/库层约束）。
 *
 * 覆盖：合法路径集中定义、越级与回退按规则拒绝、流转历史完整可追溯、
 * 并行流转只有一个成功、新增状态不改代码、通用入口与规则管理保护。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:rental-sm-test;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always"
})
class RentalStateMachineIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RentalOrderMapper orderMapper;
    @Autowired
    private RentalHandoverMapper handoverMapper;
    @Autowired
    private RentalOrderEventMapper eventMapper;
    @Autowired
    private OrderTransitionMapper transitionMapper;

    private static String reqNo() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 带幂等头的 POST。 */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder write(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, String requestNo) {
        return builder.header("X-Operator", "tester")
                .header("X-Request-No", requestNo)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private long createVehicle(String plateNo) throws Exception {
        String body = "{\"plateNo\":\"" + plateNo + "\",\"model\":\"Model-A\"}";
        MvcResult result = mockMvc.perform(write(post("/api/vehicles"), reqNo()).content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    private long createOrder(long vehicleId, String customer) throws Exception {
        String body = "{\"vehicleId\":" + vehicleId + ",\"customerName\":\"" + customer + "\"}";
        MvcResult result = mockMvc.perform(write(post("/api/orders"), reqNo()).content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    private void pickup(long orderId) throws Exception {
        mockMvc.perform(write(post("/api/orders/" + orderId + "/pickup"), reqNo())
                        .content("{\"fuelReading\":80,\"damagePhotos\":\"oss://p/a.jpg\"}"))
                .andExpect(status().isOk());
    }

    private void returnAndSettle(long orderId) throws Exception {
        mockMvc.perform(write(post("/api/orders/" + orderId + "/return"), reqNo())
                        .content("{\"fuelReading\":40,\"damagePhotos\":\"oss://p/b.jpg\",\"settleAmount\":100}"))
                .andExpect(status().isOk());
    }

    private long countTransitions(long orderId, String action) {
        return transitionMapper.selectCount(new LambdaQueryWrapper<OrderTransition>()
                .eq(OrderTransition::getOrderId, orderId)
                .eq(action != null, OrderTransition::getAction, action));
    }

    @Test
    void illegalTransitionsRejectedWithGuidance() throws Exception {
        long vehicleId = createVehicle("京SM-" + reqNo().substring(0, 8));
        long orderId = createOrder(vehicleId, "越级客户");

        // 越级：CREATED 直接还车 -> 409，报出当前合法出路
        mockMvc.perform(write(post("/api/orders/" + orderId + "/return"), reqNo())
                        .content("{\"fuelReading\":50,\"damagePhotos\":\"oss://p/x.jpg\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不允许执行 RETURN")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("PICKUP->RENTING")));

        // 越级：CREATED 直接归档 -> 409
        mockMvc.perform(write(post("/api/orders/" + orderId + "/archive"), reqNo()))
                .andExpect(status().isConflict());

        // 状态未被破坏
        mockMvc.perform(get("/api/orders/" + orderId))
                .andExpect(jsonPath("$.data.order.status").value("CREATED"));

        // 回退/重复：交车后再交车 -> 409（RENTING 无 PICKUP 出路）
        pickup(orderId);
        mockMvc.perform(write(post("/api/orders/" + orderId + "/pickup"), reqNo())
                        .content("{\"fuelReading\":80,\"damagePhotos\":\"oss://p/a2.jpg\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不允许执行 PICKUP")));

        // 回退：已结算再交车 -> 409
        returnAndSettle(orderId);
        mockMvc.perform(write(post("/api/orders/" + orderId + "/pickup"), reqNo())
                        .content("{\"fuelReading\":80,\"damagePhotos\":\"oss://p/a3.jpg\"}"))
                .andExpect(status().isConflict());

        // 冲销后为终态：任何动作都拒绝
        mockMvc.perform(write(post("/api/orders/" + orderId + "/reverse"), reqNo())
                        .content("{\"reason\":\"测试冲销\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(write(post("/api/orders/" + orderId + "/archive"), reqNo()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已到终态")));

        // 非法动作全程未产生流转记录之外的垃圾：CREATE/PICKUP/RETURN/REVERSE 共 4 行
        assertEquals(4L, countTransitions(orderId, null));
    }

    @Test
    void transitionHistoryFullyTraceable() throws Exception {
        long vehicleId = createVehicle("京SM-" + reqNo().substring(0, 8));
        long orderId = createOrder(vehicleId, "留痕客户");
        pickup(orderId);
        returnAndSettle(orderId);
        mockMvc.perform(write(post("/api/orders/" + orderId + "/archive"), reqNo()))
                .andExpect(status().isOk());
        mockMvc.perform(write(post("/api/orders/" + orderId + "/reverse"), reqNo())
                        .content("{\"reason\":\"协商冲销\"}"))
                .andExpect(status().isOk());

        // 流转历史：5 行，链条逐行衔接，末行即订单当前状态
        MvcResult result = mockMvc.perform(get("/api/orders/" + orderId + "/transitions"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode transitions = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertEquals(5, transitions.size());
        String[] actions = {"CREATE", "PICKUP", "RETURN", "ARCHIVE", "REVERSE"};
        String[] toChain = {"CREATED", "RENTING", "SETTLED", "ARCHIVED", "REVERSED"};
        for (int i = 0; i < transitions.size(); i++) {
            JsonNode t = transitions.get(i);
            assertEquals(actions[i], t.get("action").asText());
            assertEquals(toChain[i], t.get("toStatus").asText());
            if (i == 0) {
                assertTrue(t.get("fromStatus").isNull());
            } else {
                assertEquals(toChain[i - 1], t.get("fromStatus").asText());
            }
            // 每行都带四要素：谁、何时、哪个请求，查什么都对得上
            assertEquals("tester", t.get("operator").asText());
            assertTrue(t.get("bizTime") != null && !t.get("bizTime").isNull());
            assertTrue(t.get("requestNo").asText().length() > 0);
        }

        // 详情同视图：订单当前状态 = 流转末行目标状态；交接/事件/流转互相对得上
        MvcResult detail = mockMvc.perform(get("/api/orders/" + orderId)).andReturn();
        JsonNode data = objectMapper.readTree(detail.getResponse().getContentAsString()).get("data");
        assertEquals("REVERSED", data.get("order").get("status").asText());
        assertEquals(5, data.get("transitions").size());
        assertEquals(5, data.get("events").size());
        assertEquals(2, data.get("handovers").size());
    }

    @Test
    void concurrentTransitionsOnlyOneSucceeds() throws Exception {
        long vehicleId = createVehicle("京SM-" + reqNo().substring(0, 8));
        long orderId = createOrder(vehicleId, "并发客户");

        // 4 个现场终端同时交车同一订单
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch fire = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                fire.await();
                MvcResult r = mockMvc.perform(write(post("/api/orders/" + orderId + "/pickup"), reqNo())
                                .content("{\"fuelReading\":80,\"damagePhotos\":\"oss://p/c.jpg\"}"))
                        .andReturn();
                return r.getResponse().getStatus();
            }));
        }
        ready.await(10, TimeUnit.SECONDS);
        fire.countDown();

        int ok = 0;
        int conflict = 0;
        for (Future<Integer> future : futures) {
            int code = future.get(30, TimeUnit.SECONDS);
            if (code == 200) {
                ok++;
            } else if (code == 409) {
                conflict++;
            }
        }
        pool.shutdown();
        // 并行迁移只有一个成功，其余 409
        assertEquals(1, ok);
        assertEquals(threads - 1, conflict);

        // 现场只留一份：交接 1 条、PICKUP 事件 1 个、PICKUP 流转 1 行、版本号只自增一次
        assertEquals(1L, handoverMapper.selectCount(new LambdaQueryWrapper<RentalHandover>()
                .eq(RentalHandover::getOrderId, orderId)).longValue());
        assertEquals(1L, eventMapper.selectCount(new LambdaQueryWrapper<RentalOrderEvent>()
                .eq(RentalOrderEvent::getOrderId, orderId)
                .eq(RentalOrderEvent::getEventType, "PICKUP")).longValue());
        assertEquals(1L, countTransitions(orderId, "PICKUP"));
        RentalOrder order = orderMapper.selectById(orderId);
        assertEquals("RENTING", order.getStatus());
        assertEquals(1, order.getVersion().intValue());
    }

    @Test
    void newStateAndPathTakeEffectWithoutCodeChange() throws Exception {
        // 配置新路径：SETTLED --RECHECK--> RECHECKED（自定义动作，通用入口自动开放）
        mockMvc.perform(write(post("/api/orders/state-machine/transitions"), reqNo())
                        .content("{\"fromStatus\":\"settled\",\"action\":\"recheck\","
                                + "\"toStatus\":\"rechecked\",\"remark\":\"出库前复检\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fromStatus").value("SETTLED"))
                .andExpect(jsonPath("$.data.action").value("RECHECK"))
                .andExpect(jsonPath("$.data.toStatus").value("RECHECKED"))
                .andExpect(jsonPath("$.data.openApi").value("ON"))
                .andExpect(jsonPath("$.data.enabled").value("ON"));

        // 合法路径集中定义可查
        mockMvc.perform(get("/api/orders/state-machine/transitions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.action=='RECHECK' && @.fromStatus=='SETTLED')]").isArray());

        // 订单走到 SETTLED
        long vehicleId = createVehicle("京SM-" + reqNo().substring(0, 8));
        long orderId = createOrder(vehicleId, "复检客户");
        pickup(orderId);
        returnAndSettle(orderId);

        // 通用入口执行新动作（小写归一）：SETTLED -> RECHECKED，不改一行代码
        mockMvc.perform(write(post("/api/orders/" + orderId + "/transitions"), reqNo())
                        .content("{\"action\":\"recheck\",\"detail\":\"复检通过\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RECHECKED"));

        // 新状态接入既有专用接口：配置 RECHECKED --ARCHIVE--> ARCHIVED（保留动作，通用入口不开放）
        mockMvc.perform(write(post("/api/orders/state-machine/transitions"), reqNo())
                        .content("{\"fromStatus\":\"RECHECKED\",\"action\":\"ARCHIVE\",\"toStatus\":\"ARCHIVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.openApi").value("OFF"));
        mockMvc.perform(write(post("/api/orders/" + orderId + "/archive"), reqNo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ARCHIVED"));

        // 流转历史全程留痕：CREATE/PICKUP/RETURN/RECHECK/ARCHIVE 链条衔接
        MvcResult result = mockMvc.perform(get("/api/orders/" + orderId + "/transitions"))
                .andReturn();
        JsonNode transitions = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertEquals(5, transitions.size());
        assertEquals("RECHECK", transitions.get(3).get("action").asText());
        assertEquals("SETTLED", transitions.get(3).get("fromStatus").asText());
        assertEquals("RECHECKED", transitions.get(3).get("toStatus").asText());
        assertEquals("RECHECKED", transitions.get(4).get("fromStatus").asText());
        assertEquals("ARCHIVED", transitions.get(4).get("toStatus").asText());
    }

    @Test
    void genericEntryAndRuleManagementGuarded() throws Exception {
        long vehicleId = createVehicle("京SM-" + reqNo().substring(0, 8));
        long orderId = createOrder(vehicleId, "守卫客户");

        // 通用入口执行保留动作 PICKUP -> 409（交接登记规则绕不过）
        mockMvc.perform(write(post("/api/orders/" + orderId + "/transitions"), reqNo())
                        .content("{\"action\":\"PICKUP\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("专用业务接口")));

        // 通用入口执行未配置动作 -> 409
        mockMvc.perform(write(post("/api/orders/" + orderId + "/transitions"), reqNo())
                        .content("{\"action\":\"FLY\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("未定义的流转动作")));

        // 内置主流程路径禁改（CREATED --PICKUP--> RENTING 为种子）
        mockMvc.perform(write(post("/api/orders/state-machine/transitions"), reqNo())
                        .content("{\"fromStatus\":\"CREATED\",\"action\":\"PICKUP\",\"toStatus\":\"SETTLED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("内置主流程路径")));

        // CREATE 为初始创建动作，禁止配置
        mockMvc.perform(write(post("/api/orders/state-machine/transitions"), reqNo())
                        .content("{\"fromStatus\":\"CREATED\",\"action\":\"CREATE\",\"toStatus\":\"RENTING\"}"))
                .andExpect(status().isConflict());

        // 源状态与目标状态相同 -> 409
        mockMvc.perform(write(post("/api/orders/state-machine/transitions"), reqNo())
                        .content("{\"fromStatus\":\"SETTLED\",\"action\":\"LOOP\",\"toStatus\":\"SETTLED\"}"))
                .andExpect(status().isConflict());

        // 非法名称（含特殊字符）-> 409
        mockMvc.perform(write(post("/api/orders/state-machine/transitions"), reqNo())
                        .content("{\"fromStatus\":\"SET TLED\",\"action\":\"X\",\"toStatus\":\"Y\"}"))
                .andExpect(status().isConflict());

        // 配置后停用：路径立即失效
        mockMvc.perform(write(post("/api/orders/state-machine/transitions"), reqNo())
                        .content("{\"fromStatus\":\"CREATED\",\"action\":\"HOLD\",\"toStatus\":\"HELD\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(write(post("/api/orders/state-machine/transitions"), reqNo())
                        .content("{\"fromStatus\":\"CREATED\",\"action\":\"HOLD\","
                                + "\"toStatus\":\"HELD\",\"enabled\":\"OFF\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value("OFF"));
        mockMvc.perform(write(post("/api/orders/" + orderId + "/transitions"), reqNo())
                        .content("{\"action\":\"HOLD\"}"))
                .andExpect(status().isConflict());

        // 订单状态全程未被非法动作污染
        mockMvc.perform(get("/api/orders/" + orderId))
                .andExpect(jsonPath("$.data.order.status").value("CREATED"));
        assertEquals(1L, countTransitions(orderId, null)); // 只有 CREATE 一行
    }
}
