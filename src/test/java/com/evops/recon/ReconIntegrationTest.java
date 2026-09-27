package com.evops.recon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对账（recon-1）集成测试：内存 H2，全链路（拦截器/事务/库层约束/周期作业外壳）。
 *
 * 覆盖：
 * - 双方流水拉取与自动勾稽（全匹配）；
 * - 金额超差/状态不一致/单边账进差异清单；
 * - 手工核销原因必填、核销后 OPEN 口径即时清零；
 * - 匹配容差白天排队、夜间批处理生效，重跑后差异自动消解（AUTO）且人工核销不翻案；
 * - 日结对账单生成/刷新/确认冻结；
 * - 周期作业运行记录与日结对账作业自动出对账单。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:recon-test;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always",
        "evops.recon.enabled=false"
})
class ReconIntegrationTest {

    // JUnit5 每个用例新建测试实例；网点与业务日每用例唯一（静态序号分配），
    // 避免日结作业扫描到其他用例（含已冻结）的网点。
    private static final java.util.concurrent.atomic.AtomicInteger DAY_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();
    private final String branch = "B-RC-" + UUID.randomUUID().toString().substring(0, 8);
    private final java.time.LocalDate bizDate =
            java.time.LocalDate.of(2026, 2, 1).plusDays(DAY_SEQ.getAndIncrement());

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ReconConfigMapper configMapper;

    /** 容差是全局单行配置：每用例前重置为 0，避免上一用例的夜间生效值污染。 */
    @BeforeEach
    void resetConfig() {
        ReconConfig config = configMapper.selectById(ReconConfig.SINGLETON_ID);
        config.setMatchTolerance(BigDecimal.ZERO);
        configMapper.updateById(config);
    }

    private static String reqNo() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder builder) {
        return builder.header("X-Operator", "dispatcher-li")
                .header("X-Request-No", reqNo())
                .contentType(MediaType.APPLICATION_JSON);
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private long createVehicle(String plate) throws Exception {
        MvcResult result = mockMvc.perform(write(post("/api/vehicles"))
                        .content("{\"plateNo\":\"" + plate + "\",\"model\":\"Model-A\"}"))
                .andExpect(status().isOk()).andReturn();
        return data(result).get("id").asLong();
    }

    /** 建单（带归属网点）-> 交车 -> 在业务日 D 还车结算，返回订单 id。 */
    private long createSettledOrder(String orderNo, String amount) throws Exception {
        long vehicleId = createVehicle("京RC-" + reqNo().substring(0, 8));
        String orderBody = "{\"orderNo\":\"" + orderNo + "\",\"vehicleId\":" + vehicleId
                + ",\"customerName\":\"王五\",\"branchCode\":\"" + branch + "\"}";
        MvcResult created = mockMvc.perform(write(post("/api/orders")).content(orderBody))
                .andExpect(status().isOk()).andReturn();
        long orderId = data(created).get("id").asLong();

        mockMvc.perform(write(post("/api/orders/" + orderId + "/pickup"))
                        .content("{\"fuelReading\":80,\"damagePhotos\":\"oss://p/a.jpg\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(write(post("/api/orders/" + orderId + "/return"))
                        .header("X-Biz-Time", bizDate.toString() + "T18:30:00")
                        .content("{\"fuelReading\":40,\"damagePhotos\":\"oss://p/b.jpg\","
                                + "\"settleAmount\":" + amount + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SETTLED"));
        return orderId;
    }

    private String importExternal(String orderNo, String type, String amount, String status) throws Exception {
        String body = "{\"branchCode\":\"" + branch + "\",\"bizDate\":\"" + bizDate.toString() + "\","
                + "\"txns\":[{"
                + "\"refNo\":\"CH-" + reqNo().substring(0, 8) + "\","
                + "\"externalRefNo\":\"" + orderNo + "\","
                + "\"txnType\":\"" + type + "\","
                + "\"amount\":" + amount + ","
                + "\"status\":\"" + status + "\","
                + "\"rawText\":\"channel-file-row\"}]}";
        MvcResult result = mockMvc.perform(write(post("/api/recon/external-txns/import")).content(body))
                .andExpect(status().isOk()).andReturn();
        return result.getResponse().getContentAsString();
    }

    private long runRecon() throws Exception {
        String body = "{\"branchCode\":\"" + branch + "\",\"bizDate\":\"" + bizDate.toString() + "\"}";
        MvcResult result = mockMvc.perform(write(post("/api/recon/run")).content(body))
                .andExpect(status().isOk()).andReturn();
        JsonNode batch = data(result);
        assertEquals("DONE", batch.get("status").asText());
        return batch.get("id").asLong();
    }

    private JsonNode openDiffs() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/recon/diffs")
                        .param("branchCode", branch).param("bizDate", bizDate.toString()).param("status", "OPEN"))
                .andExpect(status().isOk()).andReturn();
        return data(result);
    }

    private long generateStatement() throws Exception {
        MvcResult result = mockMvc.perform(write(post("/api/recon/statements"))
                        .param("branchCode", branch).param("bizDate", bizDate.toString()))
                .andExpect(status().isOk()).andReturn();
        return data(result).get("id").asLong();
    }

    @Test
    void matchedFlowProducesNoDiffAndBalancedStatement() throws Exception {
        String orderNo = "RO-MATCH-" + reqNo().substring(0, 8);
        createSettledOrder(orderNo, "300.00");
        importExternal(orderNo, "SETTLE", "300.00", "SUCCESS");

        runRecon();
        assertEquals(0, openDiffs().size());

        mockMvc.perform(get("/api/recon/internal-txns")
                        .param("branchCode", branch).param("bizDate", bizDate.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].refNo").value(orderNo))
                .andExpect(jsonPath("$.data[0].status").value("SETTLED"));

        MvcResult st = mockMvc.perform(write(post("/api/recon/statements"))
                        .param("branchCode", branch).param("bizDate", bizDate.toString()))
                .andExpect(status().isOk()).andReturn();
        JsonNode s = data(st);
        assertEquals(1, s.get("internalCount").asInt());
        assertEquals(1, s.get("externalCount").asInt());
        assertEquals(1, s.get("matchedCount").asInt());
        assertEquals(0, s.get("diffOpenCount").asInt());
        assertEquals(0, new java.math.BigDecimal(s.get("gapAmount").asText()).signum());
    }

    @Test
    void amountMismatchEntersDiffAndManualResolveRequiresReasonAndClearsImmediately() throws Exception {
        String orderNo = "RO-AMT-" + reqNo().substring(0, 8);
        createSettledOrder(orderNo, "300.00");
        importExternal(orderNo, "SETTLE", "280.00", "SUCCESS");
        runRecon();

        JsonNode diffs = openDiffs();
        assertEquals(1, diffs.size());
        JsonNode diff = diffs.get(0);
        assertEquals("AMOUNT_MISMATCH", diff.get("diffType").asText());
        assertEquals(20, new java.math.BigDecimal(diff.get("amountGap").asText()).intValue());
        long diffId = diff.get("id").asLong();

        // 不填原因：校验拒绝
        mockMvc.perform(write(post("/api/recon/diffs/" + diffId + "/resolve"))
                        .content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());

        // 填原因核销
        mockMvc.perform(write(post("/api/recon/diffs/" + diffId + "/resolve"))
                        .content("{\"reason\":\"渠道手续费 20 元，网点负责人确认承担\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.resolveType").value("MANUAL"))
                .andExpect(jsonPath("$.data.resolveOperator").value("dispatcher-li"));

        // 即时清零：OPEN 清单立即为空
        assertEquals(0, openDiffs().size());

        // 对账单口径已随核销刷新（GENERATED 单）
        long statementId = generateStatement();
        mockMvc.perform(get("/api/recon/statements/" + statementId))
                .andExpect(jsonPath("$.data.diffOpenCount").value(0))
                .andExpect(jsonPath("$.data.diffResolvedCount").value(1));
    }

    @Test
    void statusMismatchAutoResolvesAfterExternalCorrectionAndRerun() throws Exception {
        String orderNo = "RO-STS-" + reqNo().substring(0, 8);
        createSettledOrder(orderNo, "300.00");
        importExternal(orderNo, "SETTLE", "300.00", "PROCESSING");
        runRecon();
        assertEquals("STATUS_MISMATCH", openDiffs().get(0).get("diffType").asText());

        // 渠道更正状态后重新导入（同 externalRefNo+类型覆盖）并重跑
        importExternal(orderNo, "SETTLE", "300.00", "SUCCESS");
        runRecon();
        assertEquals(0, openDiffs().size());

        MvcResult all = mockMvc.perform(get("/api/recon/diffs")
                        .param("branchCode", branch).param("bizDate", bizDate.toString())
                        .param("status", "RESOLVED"))
                .andExpect(status().isOk()).andReturn();
        JsonNode resolved = data(all);
        assertEquals(1, resolved.size());
        assertEquals("AUTO", resolved.get(0).get("resolveType").asText());
    }

    @Test
    void singleSidedDiffsAreRaised() throws Exception {
        String orderNo = "RO-ONE-" + reqNo().substring(0, 8);
        createSettledOrder(orderNo, "300.00");
        // 只有一笔渠道单边账，内部订单无对应外部流水
        importExternal("RO-GHOST-" + reqNo().substring(0, 8), "SETTLE", "100.00", "SUCCESS");
        runRecon();

        JsonNode diffs = openDiffs();
        assertEquals(2, diffs.size());
        boolean inOnly = false, exOnly = false;
        for (JsonNode d : diffs) {
            if ("INTERNAL_ONLY".equals(d.get("diffType").asText())) {
                inOnly = true;
                assertEquals(orderNo, d.get("refNo").asText());
            }
            if ("EXTERNAL_ONLY".equals(d.get("diffType").asText())) {
                exOnly = true;
            }
        }
        assertTrue(inOnly && exOnly, "应同时产生本方单边与渠道单边差异");
    }

    @Test
    void toleranceChangeQueuesByDayAndAppliesAtNightThenRerunClearsDiff() throws Exception {
        String orderNo = "RO-TOL-" + reqNo().substring(0, 8);
        createSettledOrder(orderNo, "300.00");
        importExternal(orderNo, "SETTLE", "285.00", "SUCCESS");
        runRecon();
        assertEquals(1, openDiffs().size());

        // 当前容差仍为 0
        mockMvc.perform(get("/api/recon/config"))
                .andExpect(jsonPath("$.data.matchTolerance").value(0));

        // 白天提交变更：只排队，不立即生效
        mockMvc.perform(write(post("/api/recon/config-changes"))
                        .content("{\"configKey\":\"MATCH_TOLERANCE\",\"newValue\":20,\"reason\":\"渠道分批结算抹零\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        mockMvc.perform(get("/api/recon/config"))
                .andExpect(jsonPath("$.data.matchTolerance").value(0));

        // 负值拒绝
        mockMvc.perform(write(post("/api/recon/config-changes"))
                        .content("{\"configKey\":\"MATCH_TOLERANCE\",\"newValue\":-1}"))
                .andExpect(status().isBadRequest());

        // 夜间批处理生效，并留作业运行记录
        mockMvc.perform(write(post("/api/recon/jobs/config-apply")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
        mockMvc.perform(get("/api/recon/config"))
                .andExpect(jsonPath("$.data.matchTolerance").value(20));
        mockMvc.perform(get("/api/recon/config-changes").param("status", "APPLIED"))
                .andExpect(jsonPath("$.data[0].status").value("APPLIED"));

        // 重跑：差额 15 在容差 20 内，原差异自动消解
        runRecon();
        assertEquals(0, openDiffs().size());

        mockMvc.perform(get("/api/recon/job-runs").param("jobType", "CONFIG_APPLY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].jobType").value("CONFIG_APPLY"))
                .andExpect(jsonPath("$.data[0].summary").isNotEmpty());
    }

    @Test
    void manuallyResolvedDiffIsNotReopenedByRerunAndConfirmedStatementFreezesDay() throws Exception {
        String orderNo = "RO-FRZ-" + reqNo().substring(0, 8);
        createSettledOrder(orderNo, "300.00");
        importExternal(orderNo, "SETTLE", "260.00", "SUCCESS");   // 差 40，超过后续容差
        runRecon();
        long diffId = openDiffs().get(0).get("id").asLong();

        mockMvc.perform(write(post("/api/recon/diffs/" + diffId + "/resolve"))
                        .content("{\"reason\":\"客户事故免赔，经理审批核销\"}"))
                .andExpect(status().isOk());

        // 夜间容差调整为 20 后重跑，该差异差额 40 仍超差：人工核销结果不得翻案
        mockMvc.perform(write(post("/api/recon/config-changes"))
                        .content("{\"configKey\":\"MATCH_TOLERANCE\",\"newValue\":20}"))
                .andExpect(status().isOk());
        mockMvc.perform(write(post("/api/recon/jobs/config-apply"))).andExpect(status().isOk());
        runRecon();
        assertEquals(0, openDiffs().size());
        mockMvc.perform(get("/api/recon/diffs/" + diffId))
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.resolveType").value("MANUAL"))
                .andExpect(jsonPath("$.data.resolveReason").value("客户事故免赔，经理审批核销"));

        // 出对账单并确认冻结
        long statementId = generateStatement();
        mockMvc.perform(write(post("/api/recon/statements/" + statementId + "/confirm"))
                        .content("{\"remark\":\"次日早晨核对无误\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.confirmedBy").value("dispatcher-li"));

        // 冻结后：重跑勾稽与重新生成对账单均拒绝
        mockMvc.perform(write(post("/api/recon/run"))
                        .content("{\"branchCode\":\"" + branch + "\",\"bizDate\":\"" + bizDate.toString() + "\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(write(post("/api/recon/statements"))
                        .param("branchCode", branch).param("bizDate", bizDate.toString()))
                .andExpect(status().isConflict());
    }

    @Test
    void dailyReconJobCoversActiveBranchAndGeneratesStatement() throws Exception {
        String orderNo = "RO-JOB-" + reqNo().substring(0, 8);
        createSettledOrder(orderNo, "500.00");
        importExternal(orderNo, "SETTLE", "500.00", "SUCCESS");

        mockMvc.perform(write(post("/api/recon/jobs/daily-recon")).param("bizDate", bizDate.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.summary").isNotEmpty());

        // 作业自动勾稽 + 出对账单
        MvcResult st = mockMvc.perform(get("/api/recon/statements")
                        .param("branchCode", branch).param("bizDate", bizDate.toString()))
                .andExpect(status().isOk()).andReturn();
        JsonNode statements = data(st);
        assertEquals(1, statements.size());
        assertEquals("GENERATED", statements.get(0).get("status").asText());
        assertEquals(1, statements.get(0).get("matchedCount").asInt());
        assertEquals(0, statements.get(0).get("diffOpenCount").asInt());

        mockMvc.perform(get("/api/recon/job-runs").param("jobType", "DAILY_RECON"))
                .andExpect(jsonPath("$.data[0].status").value("SUCCESS"));
    }

    @Test
    void duplicateExternalRowInSameFileIsRejected() throws Exception {
        String orderNo = "RO-DUP-" + reqNo().substring(0, 8);
        String body = "{\"branchCode\":\"" + branch + "\",\"bizDate\":\"" + bizDate.toString() + "\","
                + "\"txns\":["
                + "{\"externalRefNo\":\"" + orderNo + "\",\"txnType\":\"SETTLE\",\"amount\":1,\"status\":\"SUCCESS\"},"
                + "{\"externalRefNo\":\"" + orderNo + "\",\"txnType\":\"SETTLE\",\"amount\":2,\"status\":\"SUCCESS\"}"
                + "]}";
        mockMvc.perform(write(post("/api/recon/external-txns/import")).content(body))
                .andExpect(status().isConflict());
    }
}
