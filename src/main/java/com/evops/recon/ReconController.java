package com.evops.recon;

import com.evops.common.ApiResponse;
import com.evops.recon.dto.ConfigChangeRequest;
import com.evops.recon.dto.DiffResolveRequest;
import com.evops.recon.dto.ExternalTxnImportRequest;
import com.evops.recon.dto.ManualReconRequest;
import com.evops.recon.dto.StatementConfirmRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对账接口。写请求统一携带 X-Operator / X-Request-No（/X-Biz-Time）三件套，过幂等拦截。
 */
@RestController
@RequestMapping("/api/recon")
public class ReconController {

    private final ReconService reconService;
    private final ReconConfigService configService;
    private final ReconJobRunner jobRunner;
    private final ReconScheduler scheduler;

    public ReconController(ReconService reconService,
                           ReconConfigService configService,
                           ReconJobRunner jobRunner,
                           ReconScheduler scheduler) {
        this.reconService = reconService;
        this.configService = configService;
        this.jobRunner = jobRunner;
        this.scheduler = scheduler;
    }

    // ---- 双方流水 ----

    /** 导入渠道对账文件（外部流水）；同 externalRefNo+txnType 重复导入覆盖。 */
    @PostMapping("/external-txns/import")
    public ApiResponse<Map<String, Integer>> importExternal(
            @Valid @RequestBody ExternalTxnImportRequest request) {
        int affected = reconService.importExternal(request);
        Map<String, Integer> data = new HashMap<>();
        data.put("imported", affected);
        return ApiResponse.ok(data);
    }

    @GetMapping("/internal-txns")
    public ApiResponse<List<InternalTxn>> listInternal(
            @RequestParam(required = false) String branchCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
        return ApiResponse.ok(reconService.listInternal(branchCode, bizDate));
    }

    @GetMapping("/external-txns")
    public ApiResponse<List<ExternalTxn>> listExternal(
            @RequestParam(required = false) String branchCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
        return ApiResponse.ok(reconService.listExternal(branchCode, bizDate));
    }

    // ---- 勾稽 / 批次 ----

    /** 手工触发对账（补跑/重跑）；凌晨由周期任务自动触发，无需人工介入。 */
    @PostMapping("/run")
    public ApiResponse<ReconBatch> run(@Valid @RequestBody ManualReconRequest request) {
        return ApiResponse.ok(reconService.runRecon(
                request.getBranchCode().trim(), request.getBizDate(), ReconBatch.TRIGGER_MANUAL));
    }

    @GetMapping("/batches")
    public ApiResponse<List<ReconBatch>> listBatches(
            @RequestParam(required = false) String branchCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
        return ApiResponse.ok(reconService.listBatches(branchCode, bizDate));
    }

    // ---- 差异清单 / 手工核销 ----

    /** 差异清单：默认返回全部，status=OPEN 看待核销，RESOLVED 看已清零。 */
    @GetMapping("/diffs")
    public ApiResponse<List<ReconDiff>> listDiffs(
            @RequestParam(required = false) String branchCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(reconService.listDiffs(branchCode, bizDate, status));
    }

    @GetMapping("/diffs/{id}")
    public ApiResponse<ReconDiff> getDiff(@PathVariable Long id) {
        return ApiResponse.ok(reconService.getDiff(id));
    }

    /** 手工核销：原因强制必填，提交后差异即时清零（OPEN 清单立即不可见）。 */
    @PostMapping("/diffs/{id}/resolve")
    public ApiResponse<ReconDiff> resolveDiff(@PathVariable Long id,
                                              @Valid @RequestBody DiffResolveRequest request) {
        return ApiResponse.ok(reconService.resolveDiff(id, request.getReason()));
    }

    // ---- 对账单 ----

    /** 日结后生成对账单；未确认可重复生成刷新口径，已确认冻结拒绝。 */
    @PostMapping("/statements")
    public ApiResponse<ReconStatement> generateStatement(
            @RequestParam String branchCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
        return ApiResponse.ok(reconService.generateStatement(branchCode.trim(), bizDate));
    }

    @GetMapping("/statements")
    public ApiResponse<List<ReconStatement>> listStatements(
            @RequestParam(required = false) String branchCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
        return ApiResponse.ok(reconService.listStatements(branchCode, bizDate));
    }

    @GetMapping("/statements/{id}")
    public ApiResponse<ReconStatement> getStatement(@PathVariable Long id) {
        return ApiResponse.ok(reconService.getStatement(id));
    }

    /** 次日早晨核对无误后确认对账单；确认即冻结。 */
    @PostMapping("/statements/{id}/confirm")
    public ApiResponse<ReconStatement> confirmStatement(@PathVariable Long id,
                                                        @RequestBody(required = false)
                                                        StatementConfirmRequest request) {
        String remark = request == null ? null : request.getRemark();
        return ApiResponse.ok(reconService.confirmStatement(id, remark));
    }

    // ---- 配置：白天排队，夜间生效 ----

    /** 当前生效配置（匹配容差）。 */
    @GetMapping("/config")
    public ApiResponse<Map<String, BigDecimal>> currentConfig() {
        Map<String, BigDecimal> data = new HashMap<>();
        data.put("matchTolerance", reconService.currentTolerance());
        return ApiResponse.ok(data);
    }

    /** 提交配置变更：只登记 PENDING，夜里 02:00 批处理统一生效。 */
    @PostMapping("/config-changes")
    public ApiResponse<ReconConfigChange> submitConfigChange(
            @Valid @RequestBody ConfigChangeRequest request) {
        return ApiResponse.ok(configService.submitChange(request));
    }

    @GetMapping("/config-changes")
    public ApiResponse<List<ReconConfigChange>> listConfigChanges(
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(configService.listChanges(status));
    }

    // ---- 周期作业：手工应急触发 + 运行记录核对 ----

    /** 应急手工触发夜间配置批处理（正常由 02:00 周期任务执行），留作业运行记录。 */
    @PostMapping("/jobs/config-apply")
    public ApiResponse<ReconJobRun> triggerConfigApply() {
        return ApiResponse.ok(jobRunner.run(ReconJobRun.JOB_CONFIG_APPLY, null, null,
                () -> {
                    ReconConfigService.ApplySummary summary = configService.applyPending(null);
                    return "手工触发配置批处理：待处理 " + summary.total + " 笔，生效 "
                            + summary.applied + " 笔，驳回 " + summary.rejected + " 笔";
                }));
    }

    /** 应急手工触发日结对账（bizDate 缺省=前一业务日），留作业运行记录。 */
    @PostMapping("/jobs/daily-recon")
    public ApiResponse<ReconJobRun> triggerDailyRecon(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
        LocalDate target = bizDate != null ? bizDate : LocalDate.now().minusDays(1);
        return ApiResponse.ok(jobRunner.run(ReconJobRun.JOB_DAILY_RECON, null, target,
                () -> scheduler.runDailyRecon(target)));
    }

    /** 次日早晨核对：查周期作业每次触发的结果（SUCCESS/FAILED + 摘要）。 */
    @GetMapping("/job-runs")
    public ApiResponse<List<ReconJobRun>> listJobRuns(
            @RequestParam(required = false) String jobType) {
        return ApiResponse.ok(jobRunner.list(jobType));
    }
}
