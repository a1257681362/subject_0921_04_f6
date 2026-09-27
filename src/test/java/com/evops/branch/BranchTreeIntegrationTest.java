package com.evops.branch;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.branch.dto.MoveRequest;
import com.evops.common.RequestContext;
import com.evops.gray.GrayRuleMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 网点树集成测试：内存 H2，全链路（拦截器/事务/闭包表/灰度闸/库层约束）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:branch-test;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always"
})
class BranchTreeIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private BranchService branchService;
    @Autowired
    private BranchMapper branchMapper;
    @Autowired
    private BranchClosureMapper closureMapper;
    @Autowired
    private BranchEventMapper eventMapper;
    @Autowired
    private GrayRuleMapper grayRuleMapper;

    private static String reqNo() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 带幂等头的写请求。 */
    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder builder, String requestNo) {
        return builder.header("X-Operator", "tester")
                .header("X-Request-No", requestNo)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private long createBranch(String code, String name, Long parentId, Integer sortOrder) throws Exception {
        StringBuilder body = new StringBuilder("{\"branchCode\":\"" + code + "\",\"name\":\"" + name + "\"");
        if (parentId != null) {
            body.append(",\"parentId\":").append(parentId);
        }
        if (sortOrder != null) {
            body.append(",\"sortOrder\":").append(sortOrder);
        }
        MvcResult result = mockMvc.perform(write(post("/api/branches"), reqNo())
                        .content(body.append("}").toString()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    /** 放开一条试点灰度规则。 */
    private void openGray(String branchCode) throws Exception {
        mockMvc.perform(write(post("/api/gray/rules"), reqNo())
                        .content("{\"branchCode\":\"" + branchCode + "\",\"status\":\"ON\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void createBuildsClosureLinksAndChildrenSorted() throws Exception {
        String p = "T1-" + reqNo().substring(0, 8);
        long root = createBranch(p, "根网点", null, null);
        long c1 = createBranch(p + "-A", "甲", root, 3);
        long c2 = createBranch(p + "-B", "乙", root, 1);
        createBranch(p + "-C", "丙", root, 2);
        long g1 = createBranch(p + "-A1", "甲一", c1, 0);

        // 闭包链路：自闭环 + 祖先链（含隔代），反向不存在
        assertEquals(1L, closureMapper.countLink(root, root));
        assertEquals(1L, closureMapper.countLink(root, c2));
        assertEquals(1L, closureMapper.countLink(root, g1));
        assertEquals(1L, closureMapper.countLink(c1, g1));
        assertEquals(0L, closureMapper.countLink(g1, root));

        openGray(p);

        // 同级按 sort_order 配置排序：乙(1) 丙(2) 甲(3)
        mockMvc.perform(get("/api/branches/" + root + "/children"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].branchCode").value(p + "-B"))
                .andExpect(jsonPath("$.data[1].branchCode").value(p + "-C"))
                .andExpect(jsonPath("$.data[2].branchCode").value(p + "-A"))
                .andExpect(jsonPath("$.data[0].depth").value(1));

        // 任意层级后代一次查出；maxDepth 截断
        mockMvc.perform(get("/api/branches/" + root + "/subtree"))
                .andExpect(jsonPath("$.data.length()").value(4));
        mockMvc.perform(get("/api/branches/" + root + "/subtree").param("maxDepth", "1"))
                .andExpect(jsonPath("$.data.length()").value(3));

        // 到根路径：根在前
        mockMvc.perform(get("/api/branches/" + g1 + "/path"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].branchCode").value(p))
                .andExpect(jsonPath("$.data[1].branchCode").value(p + "-A"));
    }

    @Test
    void moveSubtreeMigratesDescendantsAndCleansOldLinks() throws Exception {
        String p = "T2-" + reqNo().substring(0, 8);
        long r1 = createBranch(p + "-R1", "区域一", null, null);
        long a = createBranch(p + "-A", "A", r1, 0);
        long b = createBranch(p + "-B", "B", a, 0);
        long c = createBranch(p + "-C", "C", a, 0);
        long r2 = createBranch(p + "-R2", "区域二", null, null);
        openGray(p + "-R1");
        openGray(p + "-R2");

        mockMvc.perform(write(post("/api/branches/" + a + "/move"), reqNo())
                        .content("{\"newParentId\":" + r2 + ",\"reason\":\"区域调整\"}"))
                .andExpect(status().isOk());

        // 子孙整体跟随：R2 下 A(深1)、B/C(深2)
        mockMvc.perform(get("/api/branches/" + r2 + "/subtree"))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].branchCode").value(p + "-A"))
                .andExpect(jsonPath("$.data[0].depth").value(1))
                .andExpect(jsonPath("$.data[1].depth").value(2))
                .andExpect(jsonPath("$.data[2].depth").value(2));

        // 旧链路清理干净：R1 下已空；子树内部链路保留；parent_id 已改
        mockMvc.perform(get("/api/branches/" + r1 + "/subtree"))
                .andExpect(jsonPath("$.data.length()").value(0));
        assertEquals(0L, closureMapper.countLink(r1, a));
        assertEquals(0L, closureMapper.countLink(r1, b));
        assertEquals(0L, closureMapper.countLink(r1, c));
        assertEquals(1L, closureMapper.countLink(r2, b));
        assertEquals(1L, closureMapper.countLink(a, b));
        assertEquals(r2, branchMapper.selectById(a).getParentId().longValue());

        // 到根路径反映新链路；MOVE 事件留痕
        mockMvc.perform(get("/api/branches/" + b + "/path"))
                .andExpect(jsonPath("$.data[0].branchCode").value(p + "-R2"))
                .andExpect(jsonPath("$.data[1].branchCode").value(p + "-A"));
        Long moves = eventMapper.selectCount(new LambdaQueryWrapper<BranchEvent>()
                .eq(BranchEvent::getBranchId, a)
                .eq(BranchEvent::getEventType, "MOVE"));
        assertEquals(1L, moves.longValue());
    }

    @Test
    void moveToRootDetachesAllAncestors() throws Exception {
        String p = "T3-" + reqNo().substring(0, 8);
        long r = createBranch(p + "-R", "区域", null, null);
        long a = createBranch(p + "-A", "A", r, 0);
        long b = createBranch(p + "-B", "B", a, 0);
        openGray(p + "-R");
        openGray(p + "-A");

        mockMvc.perform(write(post("/api/branches/" + a + "/move"), reqNo())
                        .content("{\"newParentId\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parentId").value(0));

        // A 成为根：无祖先；B 跟随：路径只剩 A；旧链路清理、内部链路保留
        mockMvc.perform(get("/api/branches/" + a + "/path"))
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get("/api/branches/" + b + "/path"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].branchCode").value(p + "-A"));
        assertEquals(0L, closureMapper.countLink(r, a));
        assertEquals(0L, closureMapper.countLink(r, b));
        assertEquals(1L, closureMapper.countLink(a, b));
    }

    @Test
    void moveCycleAndNoopRejected() throws Exception {
        String p = "T4-" + reqNo().substring(0, 8);
        long r = createBranch(p + "-R", "区域", null, null);
        long a = createBranch(p + "-A", "A", r, 0);
        long b = createBranch(p + "-B", "B", a, 0);

        // 挂到自身
        mockMvc.perform(write(post("/api/branches/" + a + "/move"), reqNo())
                        .content("{\"newParentId\":" + a + "}"))
                .andExpect(status().isConflict());
        // 挂到自己的子孙下（成环）
        mockMvc.perform(write(post("/api/branches/" + r + "/move"), reqNo())
                        .content("{\"newParentId\":" + b + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("成环")));
        // 原地搬家
        mockMvc.perform(write(post("/api/branches/" + a + "/move"), reqNo())
                        .content("{\"newParentId\":" + r + "}"))
                .andExpect(status().isConflict());
        // 目标父节点不存在
        mockMvc.perform(write(post("/api/branches/" + a + "/move"), reqNo())
                        .content("{\"newParentId\":999999}"))
                .andExpect(status().isConflict());

        // 树保持原样
        assertEquals(1L, closureMapper.countLink(r, a));
        assertEquals(1L, closureMapper.countLink(r, b));
        assertEquals(r, branchMapper.selectById(a).getParentId().longValue());
    }

    @Test
    void moveRollbackOnMidwayFailure() throws Exception {
        String p = "T5-" + reqNo().substring(0, 8);
        long r1 = createBranch(p + "-R1", "区域一", null, null);
        long a = createBranch(p + "-A", "A", r1, 0);
        long b = createBranch(p + "-B", "B", a, 0);
        long r2 = createBranch(p + "-R2", "区域二", null, null);

        // 直接调服务层：迁移事件留痕处制造失败（原因超长触发 detail 列长度约束）。
        // 期望：同事务里已删的旧链路、已接的新链路、parent_id 全部回滚。
        RequestContext.set("svc-tester", LocalDateTime.now(), "svc-" + reqNo());
        try {
            MoveRequest move = new MoveRequest();
            move.setNewParentId(r2);
            StringBuilder reason = new StringBuilder();
            for (int i = 0; i < 600; i++) {
                reason.append('调');
            }
            move.setReason(reason.toString());
            assertThrows(Exception.class, () -> branchService.move(a, move));

            // 原子回滚：旧链路还在、新链路没接上、parent_id 未变、无 MOVE 事件
            assertEquals(1L, closureMapper.countLink(r1, a));
            assertEquals(1L, closureMapper.countLink(r1, b));
            assertEquals(0L, closureMapper.countLink(r2, a));
            assertEquals(r1, branchMapper.selectById(a).getParentId().longValue());
            Long moves = eventMapper.selectCount(new LambdaQueryWrapper<BranchEvent>()
                    .eq(BranchEvent::getBranchId, a)
                    .eq(BranchEvent::getEventType, "MOVE"));
            assertEquals(0L, moves.longValue());
        } finally {
            RequestContext.clear();
        }
    }

    @Test
    void sortConfigTakesEffectAndDuplicateSubmitRejected() throws Exception {
        String p = "T6-" + reqNo().substring(0, 8);
        long r = createBranch(p + "-R", "区域", null, null);
        createBranch(p + "-A", "A", r, 1);
        long b = createBranch(p + "-B", "B", r, 2);
        openGray(p + "-R");

        mockMvc.perform(get("/api/branches/" + r + "/children"))
                .andExpect(jsonPath("$.data[0].branchCode").value(p + "-A"));

        // B 排序值调为 0 -> 排到最前；SORT 事件留痕
        String sortReqNo = reqNo();
        mockMvc.perform(write(put("/api/branches/" + b + "/sort"), sortReqNo)
                        .content("{\"sortOrder\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sortOrder").value(0));
        mockMvc.perform(get("/api/branches/" + r + "/children"))
                .andExpect(jsonPath("$.data[0].branchCode").value(p + "-B"));
        Long sorts = eventMapper.selectCount(new LambdaQueryWrapper<BranchEvent>()
                .eq(BranchEvent::getBranchId, b)
                .eq(BranchEvent::getEventType, "SORT"));
        assertEquals(1L, sorts.longValue());

        // 同请求号重复提交 -> 409
        mockMvc.perform(write(put("/api/branches/" + b + "/sort"), sortReqNo)
                        .content("{\"sortOrder\":0}"))
                .andExpect(status().isConflict());
    }

    @Test
    void grayGatePilotThenFullRollout() throws Exception {
        String p = "T7-" + reqNo().substring(0, 8);
        long pilot = createBranch(p + "-P", "试点", null, null);
        long pilotChild = createBranch(p + "-P1", "试点子", pilot, 0);
        long other = createBranch(p + "-Q", "未试点", null, null);

        // 无任何规则：调度查询一律 409；运维读（详情）不受影响
        mockMvc.perform(get("/api/branches/" + pilot + "/children"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/branches/" + pilot))
                .andExpect(status().isOk());

        // 试点放开 P：P 及其子树内节点放行，Q 仍 409
        openGray(p + "-P");
        mockMvc.perform(get("/api/branches/" + pilot + "/children"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/branches/" + pilotChild + "/path"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/branches/" + other + "/children"))
                .andExpect(status().isConflict());

        // 试点规则可关停（upsert 同键更新状态）
        mockMvc.perform(write(post("/api/gray/rules"), reqNo())
                        .content("{\"branchCode\":\"" + p + "-P\",\"status\":\"OFF\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/branches/" + pilot + "/children"))
                .andExpect(status().isConflict());

        // 推全：'*' ON -> 全部放行；用毕关停，避免影响其他用例
        try {
            mockMvc.perform(write(post("/api/gray/rules"), reqNo())
                            .content("{\"branchCode\":\"*\",\"status\":\"ON\"}"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/branches/" + other + "/children"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/branches/" + pilot + "/children"))
                    .andExpect(status().isOk());
        } finally {
            mockMvc.perform(write(post("/api/gray/rules"), reqNo())
                            .content("{\"branchCode\":\"*\",\"status\":\"OFF\"}"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void deleteForbiddenAndDuplicateCodeRejected() throws Exception {
        String p = "T8-" + reqNo().substring(0, 8);
        long a = createBranch(p + "-A", "A", null, null);

        // 物理删除一律拒绝
        mockMvc.perform(write(delete("/api/branches/" + a), reqNo()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("禁止物理删除")));

        // 同编码不同请求号（绕开入口幂等）-> 库层唯一约束兜底
        mockMvc.perform(write(post("/api/branches"), reqNo())
                        .content("{\"branchCode\":\"" + p + "-A\",\"name\":\"重复编码\"}"))
                .andExpect(status().isConflict());

        // 父节点不存在
        mockMvc.perform(write(post("/api/branches"), reqNo())
                        .content("{\"branchCode\":\"" + p + "-B\",\"name\":\"孤儿\",\"parentId\":999999}"))
                .andExpect(status().isConflict());
    }

    @Test
    void optimisticLockRejectsStaleUpdate() throws Exception {
        String p = "T9-" + reqNo().substring(0, 8);
        long a = createBranch(p + "-A", "A", null, null);

        Branch stale = branchMapper.selectById(a);
        Branch fresh = branchMapper.selectById(a);
        fresh.setSortOrder(9);
        assertEquals(1, branchMapper.updateById(fresh));   // version 0 -> 1
        stale.setSortOrder(5);
        // 旧版本更新被乐观锁拦截（搬家/排序的并发兜底同理）
        assertEquals(0, branchMapper.updateById(stale));

        Branch reloaded = branchMapper.selectById(a);
        assertEquals(9, reloaded.getSortOrder().intValue());
        assertEquals(1, reloaded.getVersion().intValue());
    }
}
