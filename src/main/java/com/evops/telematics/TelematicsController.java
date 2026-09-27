package com.evops.telematics;

import com.evops.common.ApiResponse;
import com.evops.common.BizException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/**
 * 车载定位平台文件往接口。写请求统一携带 X-Operator / X-Request-No（/X-Biz-Time）三件套，过幂等拦截。
 */
@RestController
@RequestMapping("/api/telematics")
public class TelematicsController {

    private final TelematicsService telematicsService;
    private final TelematicsProperties properties;

    public TelematicsController(TelematicsService telematicsService, TelematicsProperties properties) {
        this.telematicsService = telematicsService;
        this.properties = properties;
    }

    // ---- 导出（我方 -> 平台） ----

    /** 导出指定业务日（缺省当天）的交接记录为 CSV 并按日期归档；同日可重复导出（补发）。 */
    @PostMapping("/exports")
    public ApiResponse<TelematicsExportFile> export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
        LocalDate target = bizDate != null ? bizDate : LocalDate.now();
        return ApiResponse.ok(telematicsService.exportHandovers(target));
    }

    @GetMapping("/exports")
    public ApiResponse<List<TelematicsExportFile>> listExports(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
        return ApiResponse.ok(telematicsService.listExports(bizDate));
    }

    /** 下载归档的导出文件。 */
    @GetMapping("/exports/{id}/download")
    public ResponseEntity<byte[]> downloadExport(@PathVariable Long id) {
        TelematicsExportFile record = telematicsService.requireExport(id);
        byte[] content = telematicsService.readExportFile(id);
        return csvDownload(record.getFileName(), content);
    }

    // ---- 导入（平台 -> 我方） ----

    /** 导入模板下载：表头 + 示例行，与解析口径完全一致。 */
    @GetMapping("/imports/template")
    public ResponseEntity<byte[]> template() {
        return csvDownload("device_report_template.csv", telematicsService.templateBytes());
    }

    /**
     * 上传设备回执文件导入。全或无：任一行非法整单 FAILED（错误明细带行号）；
     * 同一文件重复导入幂等（duplicate=true 返回首次结果）。
     */
    @PostMapping("/imports")
    public ApiResponse<TelematicsImportFile> importFile(@RequestParam("file") MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw new BizException("上传文件读取失败：" + ex.getMessage());
        }
        return ApiResponse.ok(telematicsService.importReport(file.getOriginalFilename(), content));
    }

    @GetMapping("/imports")
    public ApiResponse<List<TelematicsImportFile>> listImports() {
        return ApiResponse.ok(telematicsService.listImports());
    }

    /** 导入记录详情（含失败行号明细）。 */
    @GetMapping("/imports/{id}")
    public ApiResponse<TelematicsImportFile> getImport(@PathVariable Long id) {
        return ApiResponse.ok(telematicsService.requireImport(id));
    }

    // ---- 回执数据 ----

    @GetMapping("/device-reports")
    public ApiResponse<List<TelematicsDeviceReport>> listDeviceReports(
            @RequestParam(required = false) String plateNo) {
        return ApiResponse.ok(telematicsService.listDeviceReports(plateNo));
    }

    // ---- 内部辅助 ----

    private ResponseEntity<byte[]> csvDownload(String fileName, byte[] content) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=" + properties.getCharset()))
                .body(content);
    }
}
