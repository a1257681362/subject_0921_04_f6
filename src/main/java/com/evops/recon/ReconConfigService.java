package com.evops.recon;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BizException;
import com.evops.recon.dto.ConfigChangeRequest;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 对账配置变更：白天不敢动生效配置，调整先落 PENDING 变更单；
 * 夜间批处理 {@link #applyPending(Long)} 按申请顺序逐条校验、生效，
 * 合法值改写 t_recon_config 并置 APPLIED，非法值置 REJECTED 留驳回原因，
 * 单条失败不影响其余变更，次日早晨凭作业记录与变更单状态核对。
 */
@Service
public class ReconConfigService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final ReconConfigChangeMapper changeMapper;
    private final ReconConfigMapper configMapper;
    private final ReconConfigService self;

    public ReconConfigService(ReconConfigChangeMapper changeMapper,
                              ReconConfigMapper configMapper,
                              @Lazy ReconConfigService self) {
        this.changeMapper = changeMapper;
        this.configMapper = configMapper;
        this.self = self;
    }

    /** 白天提交配置变更：只排队（PENDING），不触碰当前生效配置。 */
    @Transactional(rollbackFor = Exception.class)
    public ReconConfigChange submitChange(ConfigChangeRequest request) {
        String key = request.getConfigKey().trim().toUpperCase();
        if (!ReconConfig.KEY_MATCH_TOLERANCE.equals(key)) {
            throw new BizException("暂不支持的配置项：" + key
                    + "（当前仅支持 " + ReconConfig.KEY_MATCH_TOLERANCE + "）");
        }
        BigDecimal newValue = request.getNewValue();
        if (newValue == null || newValue.signum() < 0) {
            throw new BizException("匹配容差不能为负");
        }

        ReconConfig config = configMapper.selectById(ReconConfig.SINGLETON_ID);
        BigDecimal oldValue = config == null ? BigDecimal.ZERO : config.getMatchTolerance();
        if (newValue.compareTo(oldValue) == 0) {
            throw new BizException("新值与当前生效值一致，无需变更：" + oldValue.toPlainString());
        }

        ReconConfigChange change = new ReconConfigChange();
        change.setChangeNo("CC" + LocalDateTime.now().format(NO_FMT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000)));
        change.setConfigKey(key);
        change.setOldValue(oldValue.toPlainString());
        change.setNewValue(newValue.setScale(2).toPlainString());
        change.setReason(request.getReason());
        change.setStatus(ReconConfigChange.STATUS_PENDING);
        changeMapper.insert(change);
        return change;
    }

    public List<ReconConfigChange> listChanges(String status) {
        return changeMapper.selectList(new LambdaQueryWrapper<ReconConfigChange>()
                .eq(status != null && !status.trim().isEmpty(),
                        ReconConfigChange::getStatus, status == null ? null : status.trim().toUpperCase())
                .orderByAsc(ReconConfigChange::getId));
    }

    /**
     * 夜间批处理：逐条应用 PENDING 变更，返回处理结果摘要。
     * 每条变更独立事务，单条非法/异常只驳回该条，不阻断后续变更。
     */
    public ApplySummary applyPending(Long jobRunId) {
        List<ReconConfigChange> pending = changeMapper.selectList(
                new LambdaQueryWrapper<ReconConfigChange>()
                        .eq(ReconConfigChange::getStatus, ReconConfigChange.STATUS_PENDING)
                        .orderByAsc(ReconConfigChange::getId));
        ApplySummary summary = new ApplySummary(pending.size());
        for (ReconConfigChange change : pending) {
            try {
                self.applyOne(change.getId(), jobRunId);
                summary.applied++;
            } catch (AlreadyHandledException ex) {
                // 已被另一作业处理，幂等跳过，不计入任何结果
            } catch (Exception ex) {
                // 独立事务已回滚；驳回留痕走新事务，保证驳回原因可见
                self.reject(change.getId(), jobRunId, truncate(ex.getMessage(), 500));
                summary.rejected++;
                summary.rejectDetails.add(change.getChangeNo() + "：" + ex.getMessage());
            }
        }
        return summary;
    }

    /** 单条变更生效：二次校验 PENDING 状态（乐观锁兜底夜间重复执行）。 */
    @Transactional(rollbackFor = Exception.class)
    public void applyOne(Long changeId, Long jobRunId) {
        ReconConfigChange change = changeMapper.selectById(changeId);
        if (change == null) {
            throw new BizException("配置变更单不存在：" + changeId);
        }
        if (!ReconConfigChange.STATUS_PENDING.equals(change.getStatus())) {
            // 已被另一作业处理，幂等跳过（不计驳回）
            throw new AlreadyHandledException();
        }
        BigDecimal newValue;
        try {
            newValue = new BigDecimal(change.getNewValue());
        } catch (NumberFormatException ex) {
            throw new BizException("新值不是合法金额：" + change.getNewValue());
        }
        if (newValue.signum() < 0) {
            throw new BizException("匹配容差不能为负：" + newValue.toPlainString());
        }

        ReconConfig config = configMapper.selectById(ReconConfig.SINGLETON_ID);
        if (config == null) {
            config = new ReconConfig();
            config.setId(ReconConfig.SINGLETON_ID);
            config.setMatchTolerance(newValue.setScale(2));
            configMapper.insert(config);
        } else {
            change.setOldValue(config.getMatchTolerance().setScale(2).toPlainString());
            config.setMatchTolerance(newValue.setScale(2));
            configMapper.updateById(config);
        }

        change.setStatus(ReconConfigChange.STATUS_APPLIED);
        change.setAppliedTime(LocalDateTime.now());
        change.setAppliedJobRunId(jobRunId);
        changeMapper.updateById(change);
    }

    /** 非法变更驳回：独立事务落 REJECTED，保证驳回原因不随生效事务回滚。 */
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long changeId, Long jobRunId, String reason) {
        ReconConfigChange change = changeMapper.selectById(changeId);
        if (change == null || !ReconConfigChange.STATUS_PENDING.equals(change.getStatus())) {
            return;
        }
        change.setStatus(ReconConfigChange.STATUS_REJECTED);
        change.setRejectReason(reason);
        change.setAppliedTime(LocalDateTime.now());
        change.setAppliedJobRunId(jobRunId);
        changeMapper.updateById(change);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "校验失败";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 已被其他作业处理时的内部跳过信号。 */
    static class AlreadyHandledException extends RuntimeException {
    }

    /** 夜间批处理结果摘要，写入作业运行记录。 */
    public static class ApplySummary {
        public final int total;
        public int applied;
        public int rejected;
        public final List<String> rejectDetails = new ArrayList<>();

        public ApplySummary(int total) {
            this.total = total;
        }
    }
}
