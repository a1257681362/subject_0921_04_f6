package com.evops.recon;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.RequestContext;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 对账周期作业统一外壳：
 * - 为系统触发的作业建立 RequestContext（操作人 SYS_RECON、系统请求号、当前业务时间），
 *   使作业内写入同样带业务四要素并经 MetaObjectHandler 填充；
 * - 每次触发在 t_recon_job_run 留痕（SUCCESS/FAILED + 摘要/错误明细），
 *   网点负责人次日早晨据此核对夜间处理结果。
 */
@Component
public class ReconJobRunner {

    private final ReconJobRunMapper jobRunMapper;

    public ReconJobRunner(ReconJobRunMapper jobRunMapper) {
        this.jobRunMapper = jobRunMapper;
    }

    /** 执行作业并记录结果。job 内抛异常 -> FAILED 留痕后继续向上抛（供调度日志可见）。 */
    public ReconJobRun run(String jobType, String branchCode, java.time.LocalDate bizDate,
                           java.util.function.Supplier<String> job) {
        ReconJobRun run = new ReconJobRun();
        run.setJobType(jobType);
        run.setFiredAt(LocalDateTime.now());
        run.setBranchCode(branchCode);
        run.setBizDate(bizDate);
        jobRunMapper.insert(run);

        // 作业统一以 SYS_RECON 身份写入；若在 HTTP 线程（手工应急触发），结束后恢复原上下文，
        // 不能清空——入口幂等拦截器 afterCompletion 还要读它。
        RequestContext.Ctx previous = currentContext();
        String requestNo = "JOB-" + jobType + "-" + run.getId() + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        RequestContext.set(ReconService.SYS_OPERATOR, LocalDateTime.now(), requestNo);
        try {
            String summary = job.get();
            run.setStatus(ReconJobRun.STATUS_SUCCESS);
            run.setSummary(summary);
            run.setFinishedAt(LocalDateTime.now());
            jobRunMapper.updateById(run);
            return run;
        } catch (RuntimeException ex) {
            run.setStatus(ReconJobRun.STATUS_FAILED);
            run.setSummary("作业失败");
            run.setErrorDetail(truncate(ex.toString(), 4000));
            run.setFinishedAt(LocalDateTime.now());
            jobRunMapper.updateById(run);
            throw ex;
        } finally {
            restore(previous);
        }
    }

    private static RequestContext.Ctx currentContext() {
        try {
            return RequestContext.get();
        } catch (IllegalStateException ex) {
            return null;
        }
    }

    private static void restore(RequestContext.Ctx previous) {
        if (previous == null) {
            RequestContext.clear();
        } else {
            RequestContext.set(previous.getOperator(), previous.getBizTime(), previous.getRequestNo());
        }
    }

    /** 作业运行记录（次日早晨核对）：可按作业类型过滤，按触发时间倒序。 */
    public List<ReconJobRun> list(String jobType) {
        return jobRunMapper.selectList(new LambdaQueryWrapper<ReconJobRun>()
                .eq(jobType != null && !jobType.trim().isEmpty(),
                        ReconJobRun::getJobType, jobType == null ? null : jobType.trim().toUpperCase())
                .orderByDesc(ReconJobRun::getId));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
