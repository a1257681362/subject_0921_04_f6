package com.evops.recon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 对账周期调度（cron 可在 application.yml 配置，改点不动代码）：
 *
 * 1. CONFIG_APPLY 夜间配置批处理：默认每天 02:00，把白天排队的 PENDING 配置变更
 *    校验后统一生效（evops.recon.config-apply-cron）；
 * 2. DAILY_RECON 凌晨日结对账：默认每天 03:00，对前一业务日全部活跃网点
 *    拉双方流水自动勾稽并生成对账单（evops.recon.daily-recon-cron）。
 *
 * 每次触发写 t_recon_job_run，网点负责人次日早晨 GET /api/recon/job-runs 核对结果。
 * 配置项 recon.enabled=false 可整体停掉周期任务（应急开关，不影响手工触发）。
 */
@Component
public class ReconScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconScheduler.class);

    private final ReconJobRunner jobRunner;
    private final ReconConfigService configService;
    private final ReconService reconService;

    @Value("${evops.recon.enabled:true}")
    private boolean enabled;

    public ReconScheduler(ReconJobRunner jobRunner,
                          ReconConfigService configService,
                          ReconService reconService) {
        this.jobRunner = jobRunner;
        this.configService = configService;
        this.reconService = reconService;
    }

    /** 夜间配置批处理：默认 02:00。 */
    @Scheduled(cron = "${evops.recon.config-apply-cron:0 0 2 * * ?}")
    public void applyConfigsAtNight() {
        if (!enabled) {
            return;
        }
        try {
            ReconJobRun run = jobRunner.run(ReconJobRun.JOB_CONFIG_APPLY, null, null, () -> {
                ReconConfigService.ApplySummary summary = configService.applyPending(null);
                return "配置批处理完成：待处理 " + summary.total + " 笔，生效 " + summary.applied
                        + " 笔，驳回 " + summary.rejected + " 笔"
                        + (summary.rejectDetails.isEmpty() ? ""
                        : "；驳回明细：" + String.join("；", summary.rejectDetails));
            });
            log.info("对账夜间配置批处理完成 runId={} summary={}", run.getId(), run.getSummary());
        } catch (Exception ex) {
            log.error("对账夜间配置批处理失败", ex);
        }
    }

    /** 凌晨日结对账：默认 03:00，处理前一业务日。 */
    @Scheduled(cron = "${evops.recon.daily-recon-cron:0 0 3 * * ?}")
    public void dailyReconBeforeDawn() {
        if (!enabled) {
            return;
        }
        LocalDate bizDate = LocalDate.now().minusDays(1);
        try {
            ReconJobRun run = jobRunner.run(ReconJobRun.JOB_DAILY_RECON, null, bizDate,
                    () -> runDailyRecon(bizDate));
            log.info("日结对账完成 bizDate={} runId={} summary={}", bizDate, run.getId(), run.getSummary());
        } catch (Exception ex) {
            log.error("日结对账失败 bizDate={}", bizDate, ex);
        }
    }

    /**
     * 对指定业务日全部活跃网点执行勾稽 + 出对账单。单网点失败不影响其余网点，
     * 失败网点在摘要中列明（差异/流水可白天修复后手工重跑）。
     */
    public String runDailyRecon(LocalDate bizDate) {
        List<String> branches = reconService.activeBranches(bizDate);
        int ok = 0;
        int skipped = 0;
        java.util.List<String> failures = new java.util.ArrayList<>();
        for (String branchCode : branches) {
            try {
                // 当日对账单已确认冻结的网点跳过（如白天已手工完成日结），不计失败。
                List<ReconStatement> statements = reconService.listStatements(branchCode, bizDate);
                boolean confirmed = statements.stream()
                        .anyMatch(s -> ReconStatement.STATUS_CONFIRMED.equals(s.getStatus()));
                if (confirmed) {
                    skipped++;
                    continue;
                }
                reconService.runRecon(branchCode, bizDate, ReconBatch.TRIGGER_SCHEDULED);
                reconService.generateStatement(branchCode, bizDate);
                ok++;
            } catch (Exception ex) {
                failures.add(branchCode + "(" + truncate(ex.getMessage(), 80) + ")");
            }
        }
        String summary = "业务日 " + bizDate + " 活跃网点 " + branches.size()
                + " 个，对账单已出 " + ok + " 个，跳过已确认 " + skipped + " 个"
                + (failures.isEmpty() ? "" : "，失败：" + String.join("、", failures));
        if (!failures.isEmpty()) {
            throw new IllegalStateException(summary);
        }
        return summary;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "未知错误";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
