package com.evops.rental;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 客户姓名拼音检索集成测试：全拼/首字母/大小写与全半角容错、中文直查、
 * 相关度排序、改名同事务同步、灰度 OFF 回退、全量重建。
 *
 * 同类用例共用一个内存库，故各用例姓名取互不冲突的生僻样例，避免跨方法数据干扰排序断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:name-search-test;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always"
})
class NameSearchIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RentalOrderNameIndexMapper indexMapper;

    private static String reqNo() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder builder, String requestNo) {
        return builder.header("X-Operator", "tester")
                .header("X-Request-No", requestNo)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private long createVehicle(String plateNo) throws Exception {
        String body = "{\"plateNo\":\"" + plateNo + "\",\"model\":\"Model-A\"}";
        MvcResult result = mockMvc.perform(write(post("/api/vehicles"), reqNo()).content(body))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    private long createOrder(long vehicleId, String customer) throws Exception {
        String body = "{\"orderNo\":\"RO-" + reqNo() + "\",\"vehicleId\":" + vehicleId
                + ",\"customerName\":\"" + customer + "\"}";
        MvcResult result = mockMvc.perform(write(post("/api/orders"), reqNo()).content(body))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    private void setGray(String status) throws Exception {
        mockMvc.perform(write(post("/api/gray/rules"), reqNo())
                        .content("{\"scene\":\"CUSTOMER_NAME_SEARCH\",\"branchCode\":\"*\",\"status\":\""
                                + status + "\"}"))
                .andExpect(status().isOk());
    }

    private JsonNode search(String customerName) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/orders").param("customerName", customerName))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private long firstId(String keyword) throws Exception {
        return search(keyword).get("records").get(0).get("id").asLong();
    }

    @Test
    void indexBuiltOnCreateAndSearchByFullPinyinAndInitials() throws Exception {
        setGray("ON");
        long vid = createVehicle("京P-" + reqNo().substring(0, 8));
        long id = createOrder(vid, "诸葛孔明");

        // 下单即在同事务建好索引
        RentalOrderNameIndex index = indexMapper.selectById(id);
        assertEquals("zhugekongming", index.getFullPinyin());
        assertEquals("zgkm", index.getInitials());

        // 全拼
        assertEquals(id, firstId("zhugekongming"));
        // 全拼前缀片段（记不全名字）
        assertEquals(id, firstId("zhuge"));
        // 首字母
        assertEquals(id, firstId("zgkm"));
        // 首字母片段
        assertEquals(id, firstId("zg"));
    }

    @Test
    void caseInsensitiveAndFullWidthTolerant() throws Exception {
        setGray("ON");
        long vid = createVehicle("京W-" + reqNo().substring(0, 8));
        long id = createOrder(vid, "欧阳修");

        // 大写
        assertEquals(id, firstId("OUYANGXIU"));
        assertEquals(id, firstId("OYX"));
        // 全角字母（NFKC 归一为半角）
        assertEquals(id, firstId("ＯＹＸ"));
        assertEquals(id, firstId("Ｏｕｙａｎｇ"));
        // 带空格/点号等标点容错（被压缩）
        assertEquals(id, firstId(" o y x "));
    }

    @Test
    void chineseAndMixedLatinDigitsSearch() throws Exception {
        setGray("ON");
        long vid = createVehicle("京M-" + reqNo().substring(0, 8));
        long cnId = createOrder(vid, "司马迁");
        long mixId = createOrder(vid, "张a三1");
        long enId = createOrder(vid, "John Wang");

        // 中文原文直查（包含）
        assertEquals(cnId, firstId("司马"));
        // 混合名：全拼/首字母保留外文字母与数字
        RentalOrderNameIndex mix = indexMapper.selectById(mixId);
        assertEquals("zhangasan1", mix.getFullPinyin());
        assertEquals("zas1", mix.getInitials());
        assertEquals(mixId, firstId("zas1"));
        assertEquals(mixId, firstId("zhangasan1"));
        // 外文姓名大小写/全角容错（空格被压缩）
        assertEquals(enId, firstId("john"));
        assertEquals(enId, firstId("JOHNWANG"));
        assertEquals(enId, firstId("ＪＯＨＮ"));
    }

    @Test
    void resultsRankedByRelevance() throws Exception {
        setGray("ON");
        long vid = createVehicle("京R-" + reqNo().substring(0, 8));
        long exact = createOrder(vid, "王五");       // full 精确命中 wangwu
        long prefix = createOrder(vid, "王五六");     // full 前缀命中 wangwu...
        long contains = createOrder(vid, "小王五");   // full 包含命中 ...wangwu

        JsonNode records = search("wangwu").get("records");
        assertEquals(3, records.size());
        // 相关度：精确 > 前缀 > 包含
        assertEquals(exact, records.get(0).get("id").asLong());
        assertEquals(prefix, records.get(1).get("id").asLong());
        assertEquals(contains, records.get(2).get("id").asLong());
    }

    @Test
    void renameKeepsIndexInSyncAndLeavesEvent() throws Exception {
        setGray("ON");
        long vid = createVehicle("京N-" + reqNo().substring(0, 8));
        long id = createOrder(vid, "李四");
        assertEquals(id, firstId("lisi"));

        // 改名
        mockMvc.perform(write(put("/api/orders/" + id + "/customer"), reqNo())
                        .content("{\"customerName\":\"李五\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerName").value("李五"));

        // 旧拼音查不到，新拼音查得到
        assertEquals(0, search("lisi").get("records").size());
        assertEquals(id, firstId("liwu"));
        assertEquals("李五", indexMapper.selectById(id).getCustomerName());
        assertEquals("李五", indexMapper.selectById(id).getNormalizedName());

        // RENAME 留痕
        MvcResult detail = mockMvc.perform(get("/api/orders/" + id)).andReturn();
        JsonNode events = objectMapper.readTree(detail.getResponse().getContentAsString())
                .get("data").get("events");
        boolean renamed = false;
        for (JsonNode e : events) {
            if ("RENAME".equals(e.get("eventType").asText())) {
                renamed = true;
            }
        }
        assertTrue(renamed, "改名应留 RENAME 事件");

        // 改名入参校验
        mockMvc.perform(write(put("/api/orders/" + id + "/customer"), reqNo())
                        .content("{\"customerName\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void grayOffIgnoresKeywordAndFallsBackToLegacySearch() throws Exception {
        // 默认即 OFF；显式置 OFF 验证回退
        setGray("OFF");
        long vid = createVehicle("京F-" + reqNo().substring(0, 8));
        long id = createOrder(vid, "赵钱孙");

        // customerName 被忽略：即使给不可能匹配的关键字，仍按旧链路返回订单
        JsonNode data = search("zzzz不可能匹配");
        assertTrue(data.get("total").asLong() >= 1);
        boolean present = false;
        for (JsonNode r : data.get("records")) {
            if (r.get("id").asLong() == id) {
                present = true;
            }
        }
        assertTrue(present, "灰度关闭时应退回旧检索，忽略 customerName");
    }

    @Test
    void rebuildRestoresMissingIndex() throws Exception {
        setGray("ON");
        long vid = createVehicle("京B-" + reqNo().substring(0, 8));
        long id = createOrder(vid, "周吴郑");
        assertEquals(id, firstId("zhouwuzheng"));

        // 人为删掉派生索引行，模拟漏同步
        indexMapper.deleteById(id);
        assertEquals(0, search("zhouwuzheng").get("records").size());

        // 全量重建后恢复
        mockMvc.perform(write(post("/api/orders/name-index/rebuild"), reqNo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rebuilt").isNumber());
        assertEquals(id, firstId("zhouwuzheng"));
        assertEquals("周吴郑", indexMapper.selectById(id).getCustomerName());
    }
}
