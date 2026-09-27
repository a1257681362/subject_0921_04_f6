package com.evops.rental;

import com.evops.common.RequestContext;
import com.evops.rental.dto.HandoverRequest;
import com.evops.rental.dto.OrderCreateRequest;
import com.evops.rental.fsm.RentalOrderTransition;
import com.evops.rental.fsm.RentalOrderTransitionMapper;
import com.evops.rental.fsm.RentalStateMachine;
import com.evops.rental.fsm.RentalStateMachineConfig;
import com.evops.vehicle.Vehicle;
import com.evops.vehicle.VehicleMapper;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 交车生命周期状态机集成测试：
 * 合法路径集中配置、越级/回退/未知动作拒绝、流转历史完整可追溯、
 * 并行流转只有一个成功（乐观锁 CAS）、新增状态不改代码、状态机视图。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:rental-fsm-test;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always",
        // 新增两个试点状态与动作，只加配置不改代码：
        // RENTING --暂扣复检--> INSPECTING --复检放行--> RENTING（显式允许的逆向回到租用中），
        // CREATED --暂缓--> HELD；HELD 为终态之一（本期不放开恢复）。
        "evops.rental.fsm.transitions[0].action=PICKUP",
        "evops.rental.fsm.transitions[0].from=CREATED",
        "evops.rental.fsm.transitions[0].to=RENTING",
        "evops.rental.fsm.transitions[0].forward=true",
        "evops.rental.fsm.transitions[1].action=RETURN",
        "evops.rental.fsm.transitions[1].from=RENTING",
        "evops.rental.fsm.transitions[1].to=SETTLED",
        "evops.rental.fsm.transitions[1].forward=true",
        "evops.rental.fsm.transitions[2].action=ARCHIVE",
        "evops.rental.fsm.transitions[2].from=SETTLED",
        "evops.rental.fsm.transitions[2].to=ARCHIVED",
        "evops.rental.fsm.transitions[2].forward=true",
        "evops.rental.fsm.transitions[3].action=REVERSE",
        "evops.rental.fsm.transitions[3].from=SETTLED",
        "evops.rental.fsm.transitions[3].to=REVERSED",
        "evops.rental.fsm.transitions[3].forward=false",
        "evops.rental.fsm.transitions[4].action=REVERSE",
        "evops.rental.fsm.transitions[4].from=ARCHIVED",
        "evops.rental.fsm.transitions[4].to=REVERSED",
        "evops.rental.fsm.transitions[4].forward=false",
        "evops.rental.fsm.transitions[5].action=INSPECT",
        "evops.rental.fsm.transitions[5].from=RENTING",
        "evops.rental.fsm.transitions[5].to=INSPECTING",
        "evops.rental.fsm.transitions[5].forward=true",
        "evops.rental.fsm.transitions[6].action=INSPECT_DONE",
        "evops.rental.fsm.transitions[6].from=INSPECTING",
        "evops.rental.fsm.transitions[6].to=RENTING",
        "evops.rental.fsm.transitions[6].forward=false",
        "evops.rental.fsm.transitions[7].action=HOLD",
        "evops.rental.fsm.transitions[7].from=CREATED",
        "evops.rental.fsm.transitions[7].to=HELD",
        "evops.rental.fsm.transitions[7].forward=true"
})
class RentalStateMachineIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RentalService rentalService;
    @Autowired
    private RentalOrderMapper orderMapper;
    @Autowired
    private RentalOrderTransitionMapper transitionMapper;
    @Autowired
    private RentalStateMachineConfig fsmConfig;
    @Autowired
    private RentalStateMachine stateMachine;
    @Autowired
    private VehicleMapper vehicleMapper;
    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    private static String reqNo() {
        return UUID.randomUUID().toString().replace("-", "");
    }

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

    private long createOrder(String orderNo, long vehicleId, String customer) throws Exception {
        String body = "{\"orderNo\":\"" + orderNo + "\",\"vehicleId\":" + vehicleId
                + ",\"customerName\":\"" + customer + "\"}";
        MvcResult result = mockMvc.perform(write(post("/api/orders"), reqNo()).content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    private void pickup(long orderId) throws Exception {
        mockMvc.perform(write(post("/api/orders/" + orderId + "/pickup"), reqNo())
                        .content("{\"fuelReading\":80,\"damagePhotos\":\"oss://p/a.jpg\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RENTING"));
    }

    @Test
    void legalFlowWritesCompleteTransitionChain() throws Exception {
        long vehicleId = createVehicle("京FSM-" + reqNo().substring(0, 8));
        long orderId = createOrder("RO-FSM-" + reqNo().substring(0, 8), vehicleId, "张三");

        pickup(orderId);
        mockMvc.perform(write(post("/api/orders/" + orderId + "/return"), reqNo())
                        .content("{\"fuelReading\":40,\"damagePhotos\":\"oss://p/b.jpg\",\"settleAmount\":200.00}"))
                .andExpect(status().isOk());
        mockMvc.perform(write(post("/api/orders/" + orderId + "/archive"), reqNo()))
                .andExpect(status().isOk());
        mockMvc.perform(write(post("/api/orders/" + orderId + "/reverse"), reqNo())
                        .content("{\"reason\":\"audit reverse\"}"))
                .andExpect(status().isOk());

        // 流转历史：4 次流转，链完整、首尾相接、版本连续、动作/状态与配置一致
        MvcResult result = mockMvc.perform(get("/api/orders/" + orderId + "/transitions"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertEquals(4, rows.size());

        String[][] expected = {
                {"PICKUP", "CREATED", "RENTING"},
                {"RETURN", "RENTING", "SETTLED"},
                {"ARCHIVE", "SETTLED", "ARCHIVED"},
                {"REVERSE", "ARCHIVED", "REVERSED"}
        };
        for (int i = 0; i < expected.length; i++) {
            JsonNode row = rows.get(i);
            assertEquals(expected[i][0], row.get("action").asText());
            assertEquals(expected[i][1], row.get("fromStatus").asText());
            assertEquals(expected[i][2], row.get("toStatus").asText());
            assertEquals(i, row.get("fromVersion").asInt());
            assertEquals(i + 1, row.get("toVersion").asInt());
            assertEquals("tester", row.get("operator").asText());
            assertTrue(row.get("requestNo").asText().length() > 0);
            assertNotNull(row.get("bizTime"));
        }
        // 冲销原因入流转备注
        assertEquals("audit reverse", rows.get(3).get("remark").asText());
        // 交车/还车流转可对到交接记录
        assertNotNull(rows.get(0).get("handoverId"));
        assertNotNull(rows.get(1).get("handoverId"));
        assertTrue(rows.get(2).get("handoverId").isNull());

        // 详情内嵌同样的流转链
        MvcResult detail = mockMvc.perform(get("/api/orders/" + orderId)).andReturn();
        JsonNode data = objectMapper.readTree(detail.getResponse().getContentAsString()).get("data");
        assertEquals(4, data.get("transitions").size());
        assertEquals("REVERSED", data.get("order").get("status").asText());
    }

    @Test
    void skipLevelTransitionRejected() throws Exception {
        long vehicleId = createVehicle("京SKIP-" + reqNo().substring(0, 8));
        long orderId = createOrder("RO-SKIP-" + reqNo().substring(0, 8), vehicleId, "李四");

        // CREATED 直接归档：越级，拒绝
        mockMvc.perform(write(post("/api/orders/" + orderId + "/archive"), reqNo()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("越级/回退")));
        // CREATED 直接还车：越级，拒绝
        mockMvc.perform(write(post("/api/orders/" + orderId + "/return"), reqNo())
                        .content("{\"fuelReading\":40,\"damagePhotos\":\"oss://p/b.jpg\"}"))
                .andExpect(status().isConflict());

        // 状态未动、无任何流转历史
        assertEquals("CREATED", orderMapper.selectById(orderId).getStatus());
        assertEquals(0L, transitionMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RentalOrderTransition>()
                        .eq(RentalOrderTransition::getOrderId, orderId)));
    }

    @Test
    void rollbackTransitionRejected() throws Exception {
        long vehicleId = createVehicle("京BACK-" + reqNo().substring(0, 8));
        long orderId = createOrder("RO-BACK-" + reqNo().substring(0, 8), vehicleId, "王五");
        pickup(orderId); // RENTING

        // RENTING 再交车 = 退回语义；交车接口拒绝（PICKUP 仅允许 CREATED）
        mockMvc.perform(write(post("/api/orders/" + orderId + "/pickup"), reqNo())
                        .content("{\"fuelReading\":80,\"damagePhotos\":\"oss://p/a.jpg\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("越级/回退")));

        // 已冲销不允许再归档（REVERSED 是终态，配置无出路）
        mockMvc.perform(write(post("/api/orders/" + orderId + "/return"), reqNo())
                        .content("{\"fuelReading\":40,\"damagePhotos\":\"oss://p/b.jpg\",\"settleAmount\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(write(post("/api/orders/" + orderId + "/reverse"), reqNo()))
                .andExpect(status().isOk());
        mockMvc.perform(write(post("/api/orders/" + orderId + "/archive"), reqNo()))
                .andExpect(status().isConflict());
        assertEquals("REVERSED", orderMapper.selectById(orderId).getStatus());
    }

    @Test
    void unknownActionRejected() throws Exception {
        long vehicleId = createVehicle("京UNK-" + reqNo().substring(0, 8));
        long orderId = createOrder("RO-UNK-" + reqNo().substring(0, 8), vehicleId, "赵六");

        mockMvc.perform(write(post("/api/orders/" + orderId + "/transitions"), reqNo())
                        .content("{\"action\":\"FLY\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("未知流转动作")));
        assertEquals("CREATED", orderMapper.selectById(orderId).getStatus());
    }

    @Test
    void handoverActionsNotAllowedOnGenericTransitionEndpoint() throws Exception {
        long vehicleId = createVehicle("京GEN-" + reqNo().substring(0, 8));
        long orderId = createOrder("RO-GEN-" + reqNo().substring(0, 8), vehicleId, "孙七");

        // 交车必须登记油量/照片，通用接口不放行
        mockMvc.perform(write(post("/api/orders/" + orderId + "/transitions"), reqNo())
                        .content("{\"action\":\"PICKUP\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("专属接口")));
    }

    @Test
    void parallelTransitionsOnlyOneSucceeds() throws Exception {
        // 两个线程持同一版本快照，在 CAS 更新处同时推进同一订单：
        // UPDATE ... WHERE id=? AND version=? —— 影响行数=1 的事务成功提交，
        // 败者影响行数=0 抛并发冲突、整体回滚，流转历史只留成功者一行。
        RentalOrder snapshot1 = orderMapper.selectById(seedRentingOrder("RO-PAR-" + reqNo().substring(0, 8)));
        RentalOrder snapshot2 = orderMapper.selectById(snapshot1.getId());
        int baseVersion = snapshot1.getVersion(); // 交车后版本=1；两个快照同为 RENTING@1

        int parallel = 2;
        CountDownLatch atCas = new CountDownLatch(parallel);
        CountDownLatch done = new CountDownLatch(parallel);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        try {
            pool.submit(() -> runCasFire(atCas, done, success, conflict, copyOf(snapshot1, baseVersion)));
            pool.submit(() -> runCasFire(atCas, done, success, conflict, copyOf(snapshot2, baseVersion)));
            assertTrue(done.await(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdown();
        }

        assertEquals(1, success.get(), "并行流转必须只有一个成功");
        assertEquals(1, conflict.get(), "败者应命中并发冲突");

        RentalOrder finalOrder = orderMapper.selectById(snapshot1.getId());
        assertEquals("SETTLED", finalOrder.getStatus());
        assertEquals(baseVersion + 1, finalOrder.getVersion().intValue());
        // 只有成功者的一次流转历史（败者事务整体回滚，历史不留半截）
        Long transitions = transitionMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RentalOrderTransition>()
                        .eq(RentalOrderTransition::getOrderId, snapshot1.getId())
                        .eq(RentalOrderTransition::getAction, "RETURN"));
        assertEquals(1L, transitions);
    }

    private RentalOrder copyOf(RentalOrder src, int version) {
        RentalOrder copy = new RentalOrder();
        copy.setId(src.getId());
        copy.setOrderNo(src.getOrderNo());
        copy.setVehicleId(src.getVehicleId());
        copy.setCustomerName(src.getCustomerName());
        copy.setBranchCode(src.getBranchCode());
        copy.setStatus(src.getStatus());
        copy.setSettleAmount(src.getSettleAmount());
        copy.setVersion(version);
        return copy;
    }

    private Long seedRentingOrder(String orderNo) {
        return transactionTemplate.execute(status -> {
            RequestContext.set("setup", LocalDateTime.now(), "setup-" + reqNo());
            try {
                Vehicle vehicle = new Vehicle();
                vehicle.setPlateNo("京PAR-" + reqNo().substring(0, 8));
                vehicle.setModel("Model-A");
                vehicleMapper.insert(vehicle);

                OrderCreateRequest createReq = new OrderCreateRequest();
                createReq.setOrderNo(orderNo);
                createReq.setVehicleId(vehicle.getId());
                createReq.setCustomerName("周八");
                RentalOrder order = rentalService.createOrder(createReq);

                HandoverRequest pickupReq = new HandoverRequest();
                pickupReq.setFuelReading(70);
                pickupReq.setDamagePhotos("oss://p/p.jpg");
                rentalService.pickup(order.getId(), pickupReq);
                return order.getId();
            } finally {
                RequestContext.clear();
            }
        });
    }

    private void runCasFire(CountDownLatch atCas, CountDownLatch done,
                            AtomicInteger success, AtomicInteger conflict, RentalOrder snapshot) {
        try {
            transactionTemplate.executeWithoutResult(tx -> {
                RequestContext.set("parallel-" + Thread.currentThread().getId(),
                        LocalDateTime.now(), "par-" + reqNo());
                try {
                    atCas.countDown();
                    atCas.await(5, TimeUnit.SECONDS);
                    stateMachine.fire(snapshot, "RETURN", null, null);
                    success.incrementAndGet();
                } catch (com.evops.common.BizException ex) {
                    tx.setRollbackOnly();
                    if (ex.getMessage().contains("并发冲突")) {
                        conflict.incrementAndGet();
                    } else {
                        throw ex;
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    tx.setRollbackOnly();
                } finally {
                    RequestContext.clear();
                }
            });
        } finally {
            done.countDown();
        }
    }

    @Test
    void newStatusFromConfigWorksWithoutCodeChange() throws Exception {
        // 配置新增状态 HELD / INSPECTING 与动作 HOLD / INSPECT / INSPECT_DONE，
        // 不经任何代码分支，通用流转接口直接承载，库层 CHECK 也不再拦截新状态。
        long vehicleId = createVehicle("京NEW-" + reqNo().substring(0, 8));
        long orderId = createOrder("RO-NEW-" + reqNo().substring(0, 8), vehicleId, "吴九");

        mockMvc.perform(write(post("/api/orders/" + orderId + "/transitions"), reqNo())
                        .content("{\"action\":\"HOLD\",\"remark\":\"客户资料待补，暂缓\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HELD"));

        // HELD 在配置中无出路：任何动作都被拒
        mockMvc.perform(write(post("/api/orders/" + orderId + "/transitions"), reqNo())
                        .content("{\"action\":\"PICKUP\"}"))
                .andExpect(status().isConflict());
        assertEquals("HELD", orderMapper.selectById(orderId).getStatus());

        // 另一单走暂扣复检：RENTING -> INSPECTING -> RENTING（显式配置的逆向放行）
        long orderId2 = createOrder("RO-NEW2-" + reqNo().substring(0, 8), vehicleId, "郑十");
        pickup(orderId2);
        mockMvc.perform(write(post("/api/orders/" + orderId2 + "/transitions"), reqNo())
                        .content("{\"action\":\"INSPECT\",\"remark\":\"事故疑点暂扣复检\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INSPECTING"));
        // INSPECTING 下未配置的动作（如直接还车）拒绝
        mockMvc.perform(write(post("/api/orders/" + orderId2 + "/return"), reqNo())
                        .content("{\"fuelReading\":30,\"damagePhotos\":\"oss://p/x.jpg\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(write(post("/api/orders/" + orderId2 + "/transitions"), reqNo())
                        .content("{\"action\":\"INSPECT_DONE\",\"remark\":\"复检无异常放行\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RENTING"));

        List<RentalOrderTransition> chain = rentalService.listTransitions(orderId2);
        assertEquals(3, chain.size());
        assertEquals("INSPECT", chain.get(1).getAction());
        assertEquals("INSPECTING", chain.get(1).getToStatus());
        assertEquals("RENTING", chain.get(2).getToStatus());
    }

    @Test
    void stateMachineViewReturnsCentralLegalPaths() throws Exception {
        // 视图即配置：8 条规则（5 条默认 + 3 条本测试新增）
        assertEquals(8, fsmConfig.getTransitions().size());
        MvcResult result = mockMvc.perform(get("/api/orders/state-machine"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertEquals("CREATED", data.get("initialStatus").asText());
        assertEquals(8, data.get("transitions").size());
        // 冲销标注为非正向
        JsonNode firstReverse = data.get("transitions").get(3);
        assertEquals("REVERSE", firstReverse.get("action").asText());
        assertEquals(false, firstReverse.get("forward").asBoolean());
    }
}
