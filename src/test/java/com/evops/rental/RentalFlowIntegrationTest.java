package com.evops.rental;

import com.evops.common.RequestContext;
import com.evops.rental.dto.HandoverRequest;
import com.evops.rental.dto.OrderCreateRequest;
import com.evops.rental.dto.ReturnSettleRequest;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 租车主流程集成测试：内存 H2，全链路（拦截器/事务/库层约束）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:rental-test;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always"
})
class RentalFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RentalService rentalService;
    @Autowired
    private RentalHandoverMapper handoverMapper;
    @Autowired
    private RentalOrderMapper orderMapper;
    @Autowired
    private RentalOrderEventMapper eventMapper;

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
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("data").get("id").asLong();
    }

    private long createOrder(String orderNo, long vehicleId, String customer, String bizTime, String requestNo)
            throws Exception {
        String body = "{\"orderNo\":\"" + orderNo + "\",\"vehicleId\":" + vehicleId
                + ",\"customerName\":\"" + customer + "\"}";
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req =
                write(post("/api/orders"), requestNo).content(body);
        if (bizTime != null) {
            req.header("X-Biz-Time", bizTime);
        }
        MvcResult result = mockMvc.perform(req).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    @Test
    void fullHappyFlow() throws Exception {
        long vehicleId = createVehicle("京A-" + reqNo().substring(0, 8));
        String orderNo = "RO-FULL-" + reqNo().substring(0, 8);
        long orderId = createOrder(orderNo, vehicleId, "张三", "2026-09-20T10:00:00", reqNo());

        // 交车：油量 + 车损照片
        mockMvc.perform(write(post("/api/orders/" + orderId + "/pickup"), reqNo())
                        .content("{\"fuelReading\":80,\"damagePhotos\":\"oss://p/a1.jpg\",\"remark\":\"左前门划痕\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RENTING"))
                .andExpect(jsonPath("$.data.operator").value("tester"))
                .andExpect(jsonPath("$.data.requestNo").isNotEmpty());

        // 车辆应已置出租中
        mockMvc.perform(get("/api/vehicles/" + vehicleId))
                .andExpect(jsonPath("$.data.status").value("RENTING"));

        // 还车结算：油量 + 车损照片 + 金额
        mockMvc.perform(write(post("/api/orders/" + orderId + "/return"), reqNo())
                        .content("{\"fuelReading\":45,\"damagePhotos\":\"oss://p/b1.jpg,oss://p/b2.jpg\","
                                + "\"settleAmount\":368.50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SETTLED"))
                .andExpect(jsonPath("$.data.settleAmount").value(368.50));

        mockMvc.perform(get("/api/vehicles/" + vehicleId))
                .andExpect(jsonPath("$.data.status").value("IDLE"));

        // 归档
        mockMvc.perform(write(post("/api/orders/" + orderId + "/archive"), reqNo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ARCHIVED"));

        // 冲销（已归档只许冲销）
        mockMvc.perform(write(post("/api/orders/" + orderId + "/reverse"), reqNo())
                        .content("{\"reason\":\"客户投诉，协商冲销\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVERSED"));

        // 全量留痕：2 条交接（油量/照片齐全）+ 5 个事件
        MvcResult detail = mockMvc.perform(get("/api/orders/" + orderId)).andReturn();
        JsonNode data = objectMapper.readTree(detail.getResponse().getContentAsString()).get("data");
        assertEquals(2, data.get("handovers").size());
        data.get("handovers").forEach(h -> {
            assertEquals(true, h.get("fuelReading").asInt() >= 0);
            assertEquals(true, !h.get("damagePhotos").asText().isEmpty());
            assertEquals("tester", h.get("operator").asText());
            assertEquals(true, h.get("bizTime") != null && !h.get("bizTime").isNull());
            assertEquals(true, h.get("requestNo").asText().length() > 0);
            assertEquals(0, h.get("version").asInt());
        });
        assertEquals(5, data.get("events").size());

        // 订单本体经多次更新，版本号应自增
        RentalOrder order = orderMapper.selectById(orderId);
        assertEquals("REVERSED", order.getStatus());
        assertEquals(4, order.getVersion().intValue()); // 交车/还车/归档/冲销
        assertEquals(LocalDateTime.of(2026, 9, 20, 10, 0, 0), order.getBizTime());
    }

    @Test
    void duplicateSubmitSameRequestNoRejectedAtEntry() throws Exception {
        long vehicleId = createVehicle("京B-" + reqNo().substring(0, 8));
        String sameRequestNo = reqNo();
        String body = "{\"vehicleId\":" + vehicleId + ",\"customerName\":\"李四\"}";

        mockMvc.perform(write(post("/api/orders"), sameRequestNo).content(body))
                .andExpect(status().isOk());
        // 同一请求号重复提交 -> 409
        mockMvc.perform(write(post("/api/orders"), sameRequestNo).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void failedRequestCanRetryWithSameRequestNo() throws Exception {
        String requestNo = reqNo();
        String badBody = "{\"vehicleId\":999999,\"customerName\":\"王五\"}";
        // 车辆不存在 -> 业务失败
        mockMvc.perform(write(post("/api/orders"), requestNo).content(badBody))
                .andExpect(status().isConflict());

        long vehicleId = createVehicle("京C-" + reqNo().substring(0, 8));
        String goodBody = "{\"vehicleId\":" + vehicleId + ",\"customerName\":\"王五\"}";
        // 同一请求号重试 -> 放行并成功
        mockMvc.perform(write(post("/api/orders"), requestNo).content(goodBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CREATED"));
    }

    @Test
    void duplicateOrderNoRejectedByDatabase() throws Exception {
        long vehicleId = createVehicle("京D-" + reqNo().substring(0, 8));
        String orderNo = "RO-DUP-" + reqNo().substring(0, 8);
        createOrder(orderNo, vehicleId, "赵六", null, reqNo());

        // 不同请求号（绕开入口幂等）但相同订单号 -> 应用预检/库层唯一约束兜底
        mockMvc.perform(write(post("/api/orders"), reqNo())
                        .content("{\"orderNo\":\"" + orderNo + "\",\"vehicleId\":" + vehicleId
                                + ",\"customerName\":\"赵六\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void multiTableWriteRollbackOnMidwayFailure() throws Exception {
        // 直接调服务层：还车时订单更新处制造失败（金额超出 DECIMAL(12,2) 精度）。
        // 期望：同一事务里已插入的 RETURN 交接、RETURN 事件随订单更新一起回滚，
        //       订单仍 RENTING、车辆仍 RENTING。
        long vid = createVehicle("京E-" + reqNo().substring(0, 8));
        RequestContext.set("svc-tester", LocalDateTime.now(), "svc-" + reqNo());
        try {
            OrderCreateRequest createReq = new OrderCreateRequest();
            createReq.setOrderNo("RO-RB-" + reqNo().substring(0, 8));
            createReq.setVehicleId(vid);
            createReq.setCustomerName("孙七");
            RentalOrder order = rentalService.createOrder(createReq);

            HandoverRequest pickup = new HandoverRequest();
            pickup.setFuelReading(70);
            pickup.setDamagePhotos("oss://p/pickup.jpg");
            rentalService.pickup(order.getId(), pickup);

            ReturnSettleRequest ret = new ReturnSettleRequest();
            ret.setFuelReading(30);
            ret.setDamagePhotos("oss://p/return.jpg");
            ret.setSettleAmount(new BigDecimal("999999999999.99")); // 超出 DECIMAL(12,2)
            assertThrows(Exception.class, () -> rentalService.returnAndSettle(order.getId(), ret));

            RentalOrder reloaded = orderMapper.selectById(order.getId());
            assertEquals("RENTING", reloaded.getStatus());
            // 只有交车 1 条交接，RETURN 已随事务回滚
            Long handovers = handoverMapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RentalHandover>()
                            .eq(RentalHandover::getOrderId, order.getId()));
            assertEquals(1L, handovers);
            // 事件也只有 CREATE + PICKUP 两条
            Long events = eventMapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RentalOrderEvent>()
                            .eq(RentalOrderEvent::getOrderId, order.getId()));
            assertEquals(2L, events);
        } finally {
            RequestContext.clear();
        }
    }

    @Test
    void duplicateOrderNoUniqueConstraintAtDatabaseLevel() throws Exception {
        // 绕过应用预检，直接用 Mapper 插同订单号，验证库层唯一约束兜底
        long vehicleId = createVehicle("京I-" + reqNo().substring(0, 8));
        String orderNo = "RO-UK-" + reqNo().substring(0, 8);
        createOrder(orderNo, vehicleId, "钱多多", null, reqNo());

        RequestContext.set("svc-tester", LocalDateTime.now(), "svc-" + reqNo());
        try {
            RentalOrder dup = new RentalOrder();
            dup.setOrderNo(orderNo);
            dup.setVehicleId(vehicleId);
            dup.setCustomerName("钱多多");
            dup.setStatus("CREATED");
            assertThrows(org.springframework.dao.DuplicateKeyException.class,
                    () -> orderMapper.insert(dup));
        } finally {
            RequestContext.clear();
        }
    }

    @Test
    void physicalDeleteForbiddenAndReverseRulesEnforced() throws Exception {
        long vehicleId = createVehicle("京F-" + reqNo().substring(0, 8));
        long orderId = createOrder("RO-DEL-" + reqNo().substring(0, 8), vehicleId, "周八", null, reqNo());

        // 物理删除一律拒绝
        mockMvc.perform(write(delete("/api/orders/" + orderId), reqNo()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("禁止物理删除")));

        // CREATED 不允许冲销（只有已结算/已归档可以）
        mockMvc.perform(write(post("/api/orders/" + orderId + "/reverse"), reqNo())
                        .content("{\"reason\":\"x\"}"))
                .andExpect(status().isConflict());

        // 单据仍在，状态未变
        mockMvc.perform(get("/api/orders/" + orderId))
                .andExpect(jsonPath("$.data.order.status").value("CREATED"));
    }

    @Test
    void searchByOrderNoAndBizTimeRange() throws Exception {
        long vehicleId = createVehicle("京G-" + reqNo().substring(0, 8));
        String orderNo = "RO-QRY-" + reqNo().substring(0, 8);
        createOrder(orderNo, vehicleId, "吴九", "2026-09-20T10:00:00", reqNo());
        createOrder("RO-QRY-OTHER-" + reqNo().substring(0, 6), vehicleId, "吴九",
                "2026-08-01T10:00:00", reqNo());

        // 订单号 + 业务时间区间 组合检索
        mockMvc.perform(get("/api/orders")
                        .param("orderNo", "RO-QRY")
                        .param("bizTimeFrom", "2026-09-01T00:00:00")
                        .param("bizTimeTo", "2026-09-30T23:59:59"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].orderNo").value(orderNo));

        // 仅按业务时间区间
        mockMvc.perform(get("/api/orders")
                        .param("bizTimeFrom", "2026-08-01T00:00:00")
                        .param("bizTimeTo", "2026-08-31T23:59:59"))
                .andExpect(jsonPath("$.data.records[0].bizTime").isNotEmpty());
    }

    @Test
    void missingHeadersAndHandoverFieldsRejected() throws Exception {
        // 缺请求头
        mockMvc.perform(post("/api/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plateNo\":\"无请求头" + reqNo().substring(0, 6) + "\"}"))
                .andExpect(status().isBadRequest());

        long vehicleId = createVehicle("京H-" + reqNo().substring(0, 8));
        long orderId = createOrder("RO-VAL-" + reqNo().substring(0, 8), vehicleId, "郑十", null, reqNo());

        // 交接缺油量/照片
        mockMvc.perform(write(post("/api/orders/" + orderId + "/pickup"), reqNo())
                        .content("{\"remark\":\"缺必填\"}"))
                .andExpect(status().isBadRequest());

        // 油量越界
        mockMvc.perform(write(post("/api/orders/" + orderId + "/pickup"), reqNo())
                        .content("{\"fuelReading\":120,\"damagePhotos\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }
}
