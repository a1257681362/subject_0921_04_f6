package com.evops.telematics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 车载定位平台文件往来（telematics-1）集成测试：内存 H2，导出目录指向临时目录。
 *
 * 覆盖：
 * - 导出：列序/表头、特殊字符转义、null 与空串区分、CRLF 行尾、UTF-8 编码、按日期归档；
 * - 模板可下载；
 * - 导入：成功落库、同文件重复导入幂等、同设备不同文件覆盖；
 * - 导入失败：错误明细带行号、业务行零写入（全或无）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:telematics-test;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always",
        "evops.recon.enabled=false",
        "evops.telematics.export-dir=${java.io.tmpdir}/evops-telematics-test/export"
})
class TelematicsIntegrationTest {

    private static final String EXPORT_DIR =
            System.getProperty("java.io.tmpdir") + "/evops-telematics-test/export";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private static String reqNo() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder builder) {
        return builder.header("X-Operator", "dispatcher-li")
                .header("X-Request-No", reqNo())
                .contentType(MediaType.APPLICATION_JSON);
    }

    private JsonNode data(MvcResult result) throws Exception {
        // 用字节数组解析：响应无 charset 时 getContentAsString() 会按 ISO-8859-1 解码，中文变乱码
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).get("data");
    }

    private long createVehicle(String plate) throws Exception {
        MvcResult result = mockMvc.perform(write(post("/api/vehicles"))
                        .content("{\"plateNo\":\"" + plate + "\",\"model\":\"Model-A\"}"))
                .andExpect(status().isOk()).andReturn();
        return data(result).get("id").asLong();
    }

    private long createOrder(String plate) throws Exception {
        long vehicleId = createVehicle(plate);
        MvcResult created = mockMvc.perform(write(post("/api/orders"))
                        .content("{\"vehicleId\":" + vehicleId + ",\"customerName\":\"张三\"}"))
                .andExpect(status().isOk()).andReturn();
        return data(created).get("id").asLong();
    }

    // =====================================================================
    // 导出
    // =====================================================================

    @Test
    void exportEscapesAndDistinguishesNullFromEmptyAndArchivesByDate() throws Exception {
        String bizDate = "2026-09-20";
        // 交接 1：备注含逗号/引号/中文，照片含逗号 -> 验证转义
        long order1 = createOrder("京T" + reqNo().substring(0, 6));
        mockMvc.perform(write(post("/api/orders/" + order1 + "/pickup"))
                        .header("X-Biz-Time", bizDate + "T09:15:00")
                        .content("{\"fuelReading\":80,"
                                + "\"damagePhotos\":\"oss://p/a1.jpg,oss://p/a2.jpg\","
                                + "\"remark\":\"前杠有划痕, 客户说\\\"没事\\\"，已拍照\"}"))
                .andExpect(status().isOk());
        // 交接 2：remark 传空串 -> 导出应为 ""（与 null 区分）
        long order2 = createOrder("京T" + reqNo().substring(0, 6));
        mockMvc.perform(write(post("/api/orders/" + order2 + "/pickup"))
                        .header("X-Biz-Time", bizDate + "T10:20:00")
                        .content("{\"fuelReading\":50,\"damagePhotos\":\"oss://p/b.jpg\",\"remark\":\"\"}"))
                .andExpect(status().isOk());
        // 交接 3：remark 不传 -> null -> 空字段
        long order3 = createOrder("京T" + reqNo().substring(0, 6));
        mockMvc.perform(write(post("/api/orders/" + order3 + "/pickup"))
                        .header("X-Biz-Time", bizDate + "T11:25:00")
                        .content("{\"fuelReading\":60,\"damagePhotos\":\"oss://p/c.jpg\"}"))
                .andExpect(status().isOk());

        MvcResult exported = mockMvc.perform(write(post("/api/telematics/exports"))
                        .param("bizDate", bizDate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recordCount").value(3))
                .andExpect(jsonPath("$.data.charset").value("UTF-8"))
                .andReturn();
        JsonNode exportRecord = data(exported);
        long exportId = exportRecord.get("id").asLong();
        String filePath = exportRecord.get("filePath").asText();

        // 按日期归档：文件落在 <export-dir>/20260920/ 下且真实存在
        assertTrue(filePath.contains("20260920"), "归档路径应含业务日目录：" + filePath);
        Path archived = Paths.get(filePath);
        assertTrue(Files.exists(archived), "归档文件应真实存在：" + filePath);
        assertTrue(archived.startsWith(Paths.get(EXPORT_DIR, "20260920")));

        // 下载内容与归档文件一致
        MvcResult downloaded = mockMvc.perform(get("/api/telematics/exports/" + exportId + "/download"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andReturn();
        byte[] bytes = downloaded.getResponse().getContentAsByteArray();
        assertEquals(new String(Files.readAllBytes(archived), StandardCharsets.UTF_8),
                new String(bytes, StandardCharsets.UTF_8));

        String csv = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = csv.split("\r\n", -1);
        // 表头定序 + CRLF 行尾（split 后尾元素为空串即最后一行以 CRLF 结尾）
        assertEquals("order_no,plate_no,handover_type,handover_time,fuel_reading,damage_photos,remark",
                lines[0]);
        assertEquals("", lines[lines.length - 1]);
        assertEquals(4 + 1, lines.length);   // 表头 + 3 数据行 + 结尾空串

        // 行 1：照片含逗号 -> 引号包围；备注含逗号/引号 -> 引号包围且引号双写
        assertTrue(lines[1].contains("\"oss://p/a1.jpg,oss://p/a2.jpg\""), "逗号字段应引号包围：" + lines[1]);
        assertTrue(lines[1].contains("\"前杠有划痕, 客户说\"\"没事\"\"，已拍照\""), "引号应双写：" + lines[1]);
        assertTrue(lines[1].contains(",PICKUP,2026-09-20 09:15:00,80,"));
        // 行 2：空串备注 -> ""
        assertTrue(lines[2].endsWith(",\"\""), "空串备注应导出为 \"\"：" + lines[2]);
        // 行 3：null 备注 -> 空字段（行尾直接是逗号）
        assertTrue(lines[3].endsWith(","), "null 备注应导出为空字段：" + lines[3]);
        assertFalse(lines[3].endsWith(",\"\""));

        // 归档列表可查
        mockMvc.perform(get("/api/telematics/exports").param("bizDate", bizDate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].fileName").value(exportRecord.get("fileName").asText()));
    }

    // =====================================================================
    // 模板
    // =====================================================================

    @Test
    void templateIsDownloadableWithHeader() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/telematics/imports/template"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("device_report_template.csv")))
                .andReturn();
        String csv = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("plate_no,device_no,device_status,report_time,remark\r\n"),
                "模板首行应为表头：" + csv);
    }

    // =====================================================================
    // 导入
    // =====================================================================

    private MvcResult doImport(String fileName, String csvContent) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", fileName, "text/csv", csvContent.getBytes(StandardCharsets.UTF_8));
        return mockMvc.perform(multipart("/api/telematics/imports").file(file)
                        .header("X-Operator", "dispatcher-li")
                        .header("X-Request-No", reqNo()))
                .andExpect(status().isOk())
                .andReturn();
    }

    private static String reportCsv(String... rows) {
        StringBuilder sb = new StringBuilder("plate_no,device_no,device_status,report_time,remark\r\n");
        for (String row : rows) {
            sb.append(row).append("\r\n");
        }
        return sb.toString();
    }

    @Test
    void importSuccessAndSameFileReimportIsIdempotent() throws Exception {
        String tag = reqNo().substring(0, 6);
        String csv = reportCsv(
                "京I" + tag + ",DEV-" + tag + "-1,ONLINE,2026-09-25 08:30:00,\"\"",
                "京I" + tag + ",DEV-" + tag + "-2,OFFLINE,2026-09-25 09:00:00,\"备注，含逗号\"");

        MvcResult first = doImport("report-" + tag + ".csv", csv);
        JsonNode firstData = data(first);
        assertEquals("SUCCESS", firstData.get("status").asText());
        assertEquals(2, firstData.get("totalLines").asInt());
        assertEquals(2, firstData.get("successLines").asInt());
        assertFalse(firstData.get("duplicate").asBoolean());

        // 回执落库：空串备注存空串（非 null）
        mockMvc.perform(get("/api/telematics/device-reports").param("plateNo", "京I" + tag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].deviceStatus").value("ONLINE"))
                .andExpect(jsonPath("$.data[0].remark").value(""))
                .andExpect(jsonPath("$.data[1].remark").value("备注，含逗号"));

        // 同一文件重复导入（换请求号）：幂等返回首次结果，回执行数不变
        MvcResult second = doImport("report-" + tag + ".csv", csv);
        JsonNode secondData = data(second);
        assertTrue(secondData.get("duplicate").asBoolean());
        assertEquals(firstData.get("id").asLong(), secondData.get("id").asLong());
        mockMvc.perform(get("/api/telematics/device-reports").param("plateNo", "京I" + tag))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void sameDeviceInDifferentFileIsOverwrittenByLatest() throws Exception {
        String tag = reqNo().substring(0, 6);
        doImport("r1-" + tag + ".csv",
                reportCsv("京O" + tag + ",DEV-" + tag + ",OFFLINE,2026-09-25 08:30:00,"));
        doImport("r2-" + tag + ".csv",
                reportCsv("京O" + tag + ",DEV-" + tag + ",ONLINE,2026-09-25 10:00:00,已恢复"));

        mockMvc.perform(get("/api/telematics/device-reports").param("plateNo", "京O" + tag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].deviceStatus").value("ONLINE"))
                .andExpect(jsonPath("$.data[0].reportTime").value("2026-09-25T10:00:00"))
                .andExpect(jsonPath("$.data[0].remark").value("已恢复"));
    }

    @Test
    void importFailureReportsLineNumbersAndWritesNothing() throws Exception {
        String tag = reqNo().substring(0, 6);
        String csv = reportCsv(
                "京F" + tag + ",DEV-OK,ONLINE,2026-09-25 08:30:00,",          // 第 2 行：合法
                "京F" + tag + ",DEV-BAD1,FLYING,2026-09-25 08:31:00,",        // 第 3 行：状态非法
                "京F" + tag + ",DEV-BAD2,ONLINE,25/09/2026 08:32,",           // 第 4 行：时间格式错
                "京F" + tag + ",DEV-BAD3,ONLINE,2026-09-25 08:33:00");        // 第 5 行：列数不足

        MvcResult result = doImport("bad-" + tag + ".csv", csv);
        JsonNode record = data(result);
        assertEquals("FAILED", record.get("status").asText());
        assertEquals(4, record.get("totalLines").asInt());
        assertEquals(0, record.get("successLines").asInt());
        String errorDetail = record.get("errorDetail").asText();
        assertTrue(errorDetail.contains("第 3 行"), "应带行号 3：" + errorDetail);
        assertTrue(errorDetail.contains("device_status"), "应说明状态非法：" + errorDetail);
        assertTrue(errorDetail.contains("第 4 行"), "应带行号 4：" + errorDetail);
        assertTrue(errorDetail.contains("report_time"), "应说明时间格式：" + errorDetail);
        assertTrue(errorDetail.contains("第 5 行"), "应带行号 5：" + errorDetail);

        // 全或无：合法的第 2 行也不落库
        mockMvc.perform(get("/api/telematics/device-reports").param("plateNo", "京F" + tag))
                .andExpect(jsonPath("$.data.length()").value(0));

        // 导入记录可查明细
        mockMvc.perform(get("/api/telematics/imports/" + record.get("id").asLong()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.errorDetail").isNotEmpty());
    }

    @Test
    void importRejectsBadHeaderAndUnclosedQuote() throws Exception {
        // 表头不符
        MvcResult badHeader = doImport("h1.csv", "a,b,c\r\n1,2,3\r\n");
        JsonNode h1 = data(badHeader);
        assertEquals("FAILED", h1.get("status").asText());
        assertTrue(h1.get("errorDetail").asText().contains("第 1 行"),
                "表头错误应带行号：" + h1.get("errorDetail").asText());

        // 引号未闭合
        MvcResult unclosed = doImport("h2.csv",
                "plate_no,device_no,device_status,report_time,remark\r\n京X1,DEV-1,ONLINE,2026-09-25 08:30:00,\"未闭合\r\n");
        JsonNode h2 = data(unclosed);
        assertEquals("FAILED", h2.get("status").asText());
        assertTrue(h2.get("errorDetail").asText().contains("第 2 行"),
                "引号未闭合应报记录起始行：" + h2.get("errorDetail").asText());
    }
}
