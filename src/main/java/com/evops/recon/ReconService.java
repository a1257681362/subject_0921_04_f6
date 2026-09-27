package com.evops.recon;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BizException;
import com.evops.recon.dto.ExternalTxnImportRequest;
import com.evops.recon.dto.ExternalTxnItemRequest;
import com.evops.rental.RentalOrder;
import com.evops.rental.RentalOrderEvent;
import com.evops.rental.RentalOrderEventMapper;
import com.evops.rental.RentalOrderMapper;
import com.evops.rental.RentalService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 对账核心：拉双方流水 -> 按业务参考号自动勾稽 -> 金额/状态不符进差异清单 ->
 * 日结后出对账单。同网点同业务日可安全重跑。
 *
 * 勾稽规则：
 * - 配对键：业务参考号 + 业务大类（结算 SETTLE / 冲销退款 REVERSE）。
 *   外部流水类型归一：REFUND/REVERSE 归冲销类，其余（SETTLE/PAYMENT 等）归结算类；
 * - 金额：|内部-外部| <= 当前生效匹配容差视为金额一致（容差含边界）；
 * - 状态：按业务大类归一渠道状态原文（结算类认 SUCCESS/SETTLED/PAID，
 *   冲销类认 SUCCESS/REFUNDED/REFUND/REVERSED），认不出即状态不一致；
 * - 只有一方有账：单边账（INTERNAL_ONLY / EXTERNAL_ONLY）。
 *
 * 重跑口径：
 * - 本次仍存在的 OPEN 差异原地刷新；本次已消失且未经人工核销的差异自动消解(AUTO)；
 * - 人工核销(MANUAL)结果永不翻案。
 */
@Service
public class ReconService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public static final String SYS_OPERATOR = "SYS_RECON";

    private static final String CAT_SETTLE = "SETTLE";
    private static final String CAT_REVERSE = "REVERSE";

    private static final Set<String> SETTLE_OK = new HashSet<>(java.util.Arrays.asList(
            "SUCCESS", "SETTLED", "PAID"));
    private static final Set<String> REVERSE_OK = new HashSet<>(java.util.Arrays.asList(
            "SUCCESS", "REFUNDED", "REFUND", "REVERSED"));

    private final InternalTxnMapper internalMapper;
    private final ExternalTxnMapper externalMapper;
    private final ReconBatchMapper batchMapper;
    private final ReconDiffMapper diffMapper;
    private final ReconStatementMapper statementMapper;
    private final ReconConfigMapper configMapper;
    private final RentalOrderMapper orderMapper;
    private final RentalOrderEventMapper eventMapper;
    private final ReconService self;

    public ReconService(InternalTxnMapper internalMapper,
                        ExternalTxnMapper externalMapper,
                        ReconBatchMapper batchMapper,
                        ReconDiffMapper diffMapper,
                        ReconStatementMapper statementMapper,
                        ReconConfigMapper configMapper,
                        RentalOrderMapper orderMapper,
                        RentalOrderEventMapper eventMapper,
                        @Lazy ReconService self) {
        this.internalMapper = internalMapper;
        this.externalMapper = externalMapper;
        this.batchMapper = batchMapper;
        this.diffMapper = diffMapper;
        this.statementMapper = statementMapper;
        this.configMapper = configMapper;
        this.orderMapper = orderMapper;
        this.eventMapper = eventMapper;
        this.self = self;
    }

    // =====================================================================
    // 流水拉取
    // =====================================================================

    /**
     * 外部流水导入（渠道对账文件）：同 (externalRefNo, txnType) 重复导入按最新内容覆盖。
     * 同批次文件内不允许出现相同的 (externalRefNo, txnType)。
     */
    @Transactional(rollbackFor = Exception.class)
    public int importExternal(ExternalTxnImportRequest request) {
        String branchCode = request.getBranchCode().trim();
        LocalDate bizDate = request.getBizDate();
        Set<String> seen = new HashSet<>();
        int affected = 0;
        for (ExternalTxnItemRequest item : request.getTxns()) {
            String extRef = item.getExternalRefNo().trim();
            String type = item.getTxnType().trim().toUpperCase();
            String dedup = extRef + "|" + type;
            if (!seen.add(dedup)) {
                throw new BizException("导入文件内存在重复流水：" + extRef + " / " + type);
            }
            ExternalTxn existing = externalMapper.selectOne(new LambdaQueryWrapper<ExternalTxn>()
                    .eq(ExternalTxn::getExternalRefNo, extRef)
                    .eq(ExternalTxn::getTxnType, type));
            if (existing == null) {
                ExternalTxn txn = new ExternalTxn();
                txn.setBranchCode(branchCode);
                txn.setBizDate(bizDate);
                txn.setRefNo(item.getRefNo());
                txn.setExternalRefNo(extRef);
                txn.setTxnType(type);
                txn.setAmount(item.getAmount());
                txn.setStatus(item.getStatus().trim().toUpperCase());
                txn.setRawText(item.getRawText());
                externalMapper.insert(txn);
            } else {
                existing.setBranchCode(branchCode);
                existing.setBizDate(bizDate);
                existing.setRefNo(item.getRefNo());
                existing.setAmount(item.getAmount());
                existing.setStatus(item.getStatus().trim().toUpperCase());
                existing.setRawText(item.getRawText());
                externalMapper.updateById(existing);
            }
            affected++;
        }
        return affected;
    }

    /**
     * 拉内部流水：以租车单 RETURN/REVERSE 事件流水为准，抽取指定网点业务日的
     * 结算/冲销账。同一 (refNo, txnType) 只保留一行，重复拉取按订单现状覆盖。
     * 返回抽取/刷新的笔数。
     */
    @Transactional(rollbackFor = Exception.class)
    public int pullInternal(String branchCode, LocalDate bizDate) {
        LocalDateTime from = bizDate.atStartOfDay();
        LocalDateTime to = bizDate.plusDays(1).atStartOfDay();
        List<RentalOrderEvent> events = eventMapper.selectList(new LambdaQueryWrapper<RentalOrderEvent>()
                .in(RentalOrderEvent::getEventType,
                        RentalService.EventType.RETURN, RentalService.EventType.REVERSE)
                .ge(RentalOrderEvent::getBizTime, from)
                .lt(RentalOrderEvent::getBizTime, to));
        if (events.isEmpty()) {
            return 0;
        }
        Set<Long> orderIds = events.stream().map(RentalOrderEvent::getOrderId).collect(Collectors.toSet());
        Map<Long, RentalOrder> orders = orderMapper.selectBatchIds(orderIds).stream()
                .filter(o -> branchCode.equals(o.getBranchCode()))
                .collect(Collectors.toMap(RentalOrder::getId, o -> o));

        // 一单当日可能先结算后冲销，两个事件各出一笔流水；同类型事件重复只出一笔。
        Set<String> typeDone = new HashSet<>();
        int affected = 0;
        for (RentalOrderEvent event : events) {
            RentalOrder order = orders.get(event.getOrderId());
            if (order == null) {
                continue;
            }
            String txnType = RentalService.EventType.RETURN.equals(event.getEventType())
                    ? InternalTxn.TYPE_SETTLE : InternalTxn.TYPE_REVERSE;
            if (!typeDone.add(order.getId() + "|" + txnType)) {
                continue;
            }
            upsertInternal(branchCode, bizDate, order, txnType);
            affected++;
        }
        return affected;
    }

    private void upsertInternal(String branchCode, LocalDate bizDate, RentalOrder order, String txnType) {
        InternalTxn existing = internalMapper.selectOne(new LambdaQueryWrapper<InternalTxn>()
                .eq(InternalTxn::getRefNo, order.getOrderNo())
                .eq(InternalTxn::getTxnType, txnType));
        String status = InternalTxn.TYPE_REVERSE.equals(txnType)
                ? RentalService.OrderStatus.REVERSED : RentalService.OrderStatus.SETTLED;
        BigDecimal amount = order.getSettleAmount() == null ? BigDecimal.ZERO : order.getSettleAmount();
        if (existing == null) {
            InternalTxn txn = new InternalTxn();
            txn.setBranchCode(branchCode);
            txn.setBizDate(bizDate);
            txn.setRefNo(order.getOrderNo());
            txn.setTxnType(txnType);
            txn.setAmount(amount);
            txn.setStatus(status);
            txn.setOrderId(order.getId());
            internalMapper.insert(txn);
        } else {
            existing.setBranchCode(branchCode);
            existing.setBizDate(bizDate);
            existing.setAmount(amount);
            existing.setStatus(status);
            existing.setOrderId(order.getId());
            internalMapper.updateById(existing);
        }
    }

    // =====================================================================
    // 自动勾稽
    // =====================================================================

    /**
     * 执行一次对账（自动拉内部流水，外部流水需已导入），产出批次与差异清单。
     * 批次头独立事务先落 RUNNING（失败也留痕），勾稽主体独立事务，
     * 主体异常整体回滚后再用独立事务把批次置 FAILED，异常继续向上抛。
     * 若该业务日已存在 GENERATED 对账单，成功结束时同步刷新口径（CONFIRMED 的不动）。
     */
    public ReconBatch runRecon(String branchCode, LocalDate bizDate, String triggerType) {
        ReconStatement frozen = findStatement(branchCode, bizDate);
        if (frozen != null && ReconStatement.STATUS_CONFIRMED.equals(frozen.getStatus())) {
            throw new BizException("对账单已确认冻结，该业务日不能再勾稽重跑：" + frozen.getStatementNo());
        }
        ReconBatch batch = self.startBatch(branchCode, bizDate, triggerType);
        try {
            return self.executeRecon(batch.getId());
        } catch (RuntimeException ex) {
            self.markBatchFailed(batch.getId(), truncate(ex.getMessage(), 500));
            throw ex;
        }
    }

    /** 批次头落库：独立事务，保证 RUNNING 行先可见。 */
    @Transactional(rollbackFor = Exception.class)
    public ReconBatch startBatch(String branchCode, LocalDate bizDate, String triggerType) {
        return newBatch(branchCode, bizDate, triggerType);
    }

    /** 失败标记：独立事务（REQUIRES_NEW），不被主体回滚带走。 */
    @Transactional(rollbackFor = Exception.class, propagation =
            org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void markBatchFailed(Long batchId, String remark) {
        ReconBatch batch = batchMapper.selectById(batchId);
        if (batch == null || !ReconBatch.STATUS_RUNNING.equals(batch.getStatus())) {
            return;
        }
        batch.setStatus(ReconBatch.STATUS_FAILED);
        batch.setFinishedAt(LocalDateTime.now());
        batch.setRemark(remark);
        batchMapper.updateById(batch);
    }

    /** 勾稽主体：拉内部流水 -> 双方配对 -> 差异清单 upsert/消解 -> 批次收口。 */
    @Transactional(rollbackFor = Exception.class)
    public ReconBatch executeRecon(Long batchId) {
        ReconBatch batch = batchMapper.selectById(batchId);
        String branchCode = batch.getBranchCode();
        LocalDate bizDate = batch.getBizDate();

        BigDecimal tolerance = currentTolerance();
        pullInternal(branchCode, bizDate);

        List<InternalTxn> internals = internalMapper.selectList(new LambdaQueryWrapper<InternalTxn>()
                .eq(InternalTxn::getBranchCode, branchCode)
                .eq(InternalTxn::getBizDate, bizDate));
        List<ExternalTxn> externals = externalMapper.selectList(new LambdaQueryWrapper<ExternalTxn>()
                .eq(ExternalTxn::getBranchCode, branchCode)
                .eq(ExternalTxn::getBizDate, bizDate));

        MatchResult result = match(branchCode, bizDate, batchId, internals, externals, tolerance);

        batch.setInternalCount(internals.size());
        batch.setExternalCount(externals.size());
        batch.setMatchedCount(result.matched);
        batch.setDiffCount(result.openKeys.size());
        batch.setStatus(ReconBatch.STATUS_DONE);
        batch.setFinishedAt(LocalDateTime.now());
        batchMapper.updateById(batch);

        // 已有 GENERATED 对账单随重跑刷新；已确认的冻结不动。
        refreshStatementIfGenerated(branchCode, bizDate);
        return batchMapper.selectById(batchId);
    }

    private ReconBatch newBatch(String branchCode, LocalDate bizDate, String triggerType) {
        ReconBatch batch = new ReconBatch();
        batch.setBatchNo("RB" + LocalDateTime.now().format(NO_FMT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000)));
        batch.setBranchCode(branchCode);
        batch.setBizDate(bizDate);
        batch.setStatus(ReconBatch.STATUS_RUNNING);
        batch.setTriggerType(triggerType);
        batch.setInternalCount(0);
        batch.setExternalCount(0);
        batch.setMatchedCount(0);
        batch.setDiffCount(0);
        batch.setToleranceAmount(currentTolerance());
        batch.setStartedAt(LocalDateTime.now());
        batchMapper.insert(batch);
        return batch;
    }

    private static class MatchResult {
        int matched;
        Set<String> openKeys = new HashSet<>();
    }

    private MatchResult match(String branchCode, LocalDate bizDate, Long batchId,
                              List<InternalTxn> internals, List<ExternalTxn> externals,
                              BigDecimal tolerance) {
        Map<String, InternalTxn> inMap = new HashMap<>();
        for (InternalTxn t : internals) {
            inMap.put(t.getRefNo() + "|" + t.getTxnType(), t);
        }
        Map<String, ExternalTxn> exMap = new HashMap<>();
        for (ExternalTxn t : externals) {
            exMap.put(t.getExternalRefNo() + "|" + categoryOf(t.getTxnType()), t);
        }

        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(inMap.keySet());
        allKeys.addAll(exMap.keySet());

        MatchResult result = new MatchResult();
        for (String key : allKeys) {
            InternalTxn in = inMap.get(key);
            ExternalTxn ex = exMap.get(key);
            if (in != null && ex != null) {
                boolean amountOk = in.getAmount().subtract(ex.getAmount()).abs()
                        .compareTo(tolerance) <= 0;
                boolean statusOk = statusAgree(in, ex);
                if (amountOk && statusOk) {
                    result.matched++;
                    continue;
                }
                String diffType = !amountOk && !statusOk ? ReconDiff.TYPE_AMOUNT_STATUS_MISMATCH
                        : !amountOk ? ReconDiff.TYPE_AMOUNT_MISMATCH
                        : ReconDiff.TYPE_STATUS_MISMATCH;
                upsertPairDiff(branchCode, bizDate, batchId, diffType, in, ex, result);
            } else if (in != null) {
                upsertSingleDiff(branchCode, bizDate, batchId,
                        ReconDiff.TYPE_INTERNAL_ONLY, "I:" + in.getId(), in, null, result);
            } else {
                upsertSingleDiff(branchCode, bizDate, batchId,
                        ReconDiff.TYPE_EXTERNAL_ONLY, "E:" + ex.getId(), null, ex, result);
            }
        }

        autoResolveStale(branchCode, bizDate, batchId, result.openKeys);
        return result;
    }

    private void upsertPairDiff(String branchCode, LocalDate bizDate, Long batchId, String diffType,
                                InternalTxn in, ExternalTxn ex, MatchResult result) {
        String diffKey = "IE:" + in.getId() + "-" + ex.getId();
        ReconDiff diff = findDiff(branchCode, bizDate, diffKey);
        if (diff == null) {
            diff = new ReconDiff();
            diff.setBranchCode(branchCode);
            diff.setBizDate(bizDate);
            diff.setDiffKey(diffKey);
            diff.setStatus(ReconDiff.STATUS_OPEN);
        } else if (ReconDiff.STATUS_RESOLVED.equals(diff.getStatus())) {
            // 人工已核销的差异永不翻案
            return;
        }
        diff.setBatchId(batchId);
        diff.setDiffType(diffType);
        diff.setInternalTxnId(in.getId());
        diff.setExternalTxnId(ex.getId());
        diff.setRefNo(in.getRefNo());
        diff.setInternalAmount(in.getAmount());
        diff.setExternalAmount(ex.getAmount());
        diff.setAmountGap(in.getAmount().subtract(ex.getAmount()));
        diff.setInternalStatus(in.getStatus());
        diff.setExternalStatus(ex.getStatus());
        saveDiff(diff);
        result.openKeys.add(diffKey);
    }

    private void upsertSingleDiff(String branchCode, LocalDate bizDate, Long batchId, String diffType,
                                  String diffKey, InternalTxn in, ExternalTxn ex, MatchResult result) {
        ReconDiff diff = findDiff(branchCode, bizDate, diffKey);
        if (diff == null) {
            diff = new ReconDiff();
            diff.setBranchCode(branchCode);
            diff.setBizDate(bizDate);
            diff.setDiffKey(diffKey);
            diff.setStatus(ReconDiff.STATUS_OPEN);
        } else if (ReconDiff.STATUS_RESOLVED.equals(diff.getStatus())) {
            return;
        }
        diff.setBatchId(batchId);
        diff.setDiffType(diffType);
        diff.setInternalTxnId(in == null ? null : in.getId());
        diff.setExternalTxnId(ex == null ? null : ex.getId());
        diff.setRefNo(in != null ? in.getRefNo() : ex.getExternalRefNo());
        BigDecimal inAmt = in == null ? null : in.getAmount();
        BigDecimal exAmt = ex == null ? null : ex.getAmount();
        diff.setInternalAmount(inAmt);
        diff.setExternalAmount(exAmt);
        // 单边账差额取在账方金额，提示资金缺口规模
        diff.setAmountGap(in != null ? inAmt : exAmt.negate());
        diff.setInternalStatus(in == null ? null : in.getStatus());
        diff.setExternalStatus(ex == null ? null : ex.getStatus());
        saveDiff(diff);
        result.openKeys.add(diffKey);
    }

    private void saveDiff(ReconDiff diff) {
        if (diff.getId() == null) {
            diffMapper.insert(diff);
        } else {
            diffMapper.updateById(diff);
        }
    }

    private ReconDiff findDiff(String branchCode, LocalDate bizDate, String diffKey) {
        return diffMapper.selectOne(new LambdaQueryWrapper<ReconDiff>()
                .eq(ReconDiff::getBranchCode, branchCode)
                .eq(ReconDiff::getBizDate, bizDate)
                .eq(ReconDiff::getDiffKey, diffKey));
    }

    /** 本次勾稽已不存在、且未经人工核销的 OPEN 差异 -> 自动消解（重跑自愈）。 */
    private void autoResolveStale(String branchCode, LocalDate bizDate, Long batchId, Set<String> currentKeys) {
        List<ReconDiff> opens = diffMapper.selectList(new LambdaQueryWrapper<ReconDiff>()
                .eq(ReconDiff::getBranchCode, branchCode)
                .eq(ReconDiff::getBizDate, bizDate)
                .eq(ReconDiff::getStatus, ReconDiff.STATUS_OPEN));
        for (ReconDiff diff : opens) {
            if (!currentKeys.contains(diff.getDiffKey())) {
                diff.setStatus(ReconDiff.STATUS_RESOLVED);
                diff.setResolveType(ReconDiff.RESOLVE_AUTO);
                diff.setResolveReason("重新勾稽后差异消失，系统自动消解");
                diff.setResolveOperator(SYS_OPERATOR);
                diff.setResolveTime(LocalDateTime.now());
                diffMapper.updateById(diff);
            }
        }
    }

    private boolean statusAgree(InternalTxn in, ExternalTxn ex) {
        Set<String> ok = InternalTxn.TYPE_REVERSE.equals(in.getTxnType()) ? REVERSE_OK : SETTLE_OK;
        return ok.contains(ex.getStatus());
    }

    private String categoryOf(String externalTxnType) {
        String t = externalTxnType == null ? "" : externalTxnType.trim().toUpperCase();
        return "REFUND".equals(t) || "REVERSE".equals(t) ? CAT_REVERSE : CAT_SETTLE;
    }

    // =====================================================================
    // 手工核销
    // =====================================================================

    /**
     * 差异手工核销：原因强制必填（DTO 校验 + 此处二次兜底），核销提交后差异即时清零
     * （OPEN 清单立即不可见）。只允许核销 OPEN 差异。
     */
    @Transactional(rollbackFor = Exception.class)
    public ReconDiff resolveDiff(Long diffId, String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new BizException("核销原因必填：手工核销必须填写原因");
        }
        ReconDiff diff = diffMapper.selectById(diffId);
        if (diff == null) {
            throw new BizException("差异记录不存在：" + diffId);
        }
        if (!ReconDiff.STATUS_OPEN.equals(diff.getStatus())) {
            throw new BizException("差异已核销，不能重复核销（差异号：" + diffId + "）");
        }
        diff.setStatus(ReconDiff.STATUS_RESOLVED);
        diff.setResolveType(ReconDiff.RESOLVE_MANUAL);
        diff.setResolveReason(reason.trim());
        diff.setResolveOperator(com.evops.common.RequestContext.operator());
        diff.setResolveTime(LocalDateTime.now());
        diffMapper.updateById(diff);

        // 即时清零：若该业务日已有 GENERATED 对账单，同步口径；已确认的保持冻结。
        refreshStatementIfGenerated(diff.getBranchCode(), diff.getBizDate());
        return diffMapper.selectById(diffId);
    }

    // =====================================================================
    // 日结对账单
    // =====================================================================

    /**
     * 日结后生成对账单：一网点一业务日一份。未确认前重复生成/勾稽重跑会刷新口径，
     * 已确认(CONFIRMED)的对账单冻结，拒绝重新生成。
     */
    @Transactional(rollbackFor = Exception.class)
    public ReconStatement generateStatement(String branchCode, LocalDate bizDate) {
        ReconStatement existing = findStatement(branchCode, bizDate);
        if (existing != null && ReconStatement.STATUS_CONFIRMED.equals(existing.getStatus())) {
            throw new BizException("对账单已确认冻结，不能重新生成：" + existing.getStatementNo());
        }
        return buildStatement(branchCode, bizDate, existing);
    }

    /** 次日早晨核对无误后确认对账单；确认即冻结。 */
    @Transactional(rollbackFor = Exception.class)
    public ReconStatement confirmStatement(Long statementId, String remark) {
        ReconStatement statement = statementMapper.selectById(statementId);
        if (statement == null) {
            throw new BizException("对账单不存在：" + statementId);
        }
        if (ReconStatement.STATUS_CONFIRMED.equals(statement.getStatus())) {
            throw new BizException("对账单已确认，不能重复确认：" + statement.getStatementNo());
        }
        statement.setStatus(ReconStatement.STATUS_CONFIRMED);
        statement.setConfirmedBy(com.evops.common.RequestContext.operator());
        statement.setConfirmedTime(LocalDateTime.now());
        statement.setConfirmRemark(remark);
        statementMapper.updateById(statement);
        return statementMapper.selectById(statementId);
    }

    private void refreshStatementIfGenerated(String branchCode, LocalDate bizDate) {
        ReconStatement existing = findStatement(branchCode, bizDate);
        if (existing != null && ReconStatement.STATUS_GENERATED.equals(existing.getStatus())) {
            buildStatement(branchCode, bizDate, existing);
        }
    }

    private ReconStatement buildStatement(String branchCode, LocalDate bizDate, ReconStatement existing) {
        List<InternalTxn> internals = internalMapper.selectList(new LambdaQueryWrapper<InternalTxn>()
                .eq(InternalTxn::getBranchCode, branchCode)
                .eq(InternalTxn::getBizDate, bizDate));
        List<ExternalTxn> externals = externalMapper.selectList(new LambdaQueryWrapper<ExternalTxn>()
                .eq(ExternalTxn::getBranchCode, branchCode)
                .eq(ExternalTxn::getBizDate, bizDate));
        List<ReconDiff> diffs = diffMapper.selectList(new LambdaQueryWrapper<ReconDiff>()
                .eq(ReconDiff::getBranchCode, branchCode)
                .eq(ReconDiff::getBizDate, bizDate));
        long open = diffs.stream().filter(d -> ReconDiff.STATUS_OPEN.equals(d.getStatus())).count();
        long resolved = diffs.size() - open;

        BigDecimal totalIn = internals.stream().map(InternalTxn::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalEx = externals.stream().map(ExternalTxn::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 已匹配笔数取当天最近一个完成批次的口径
        Integer matched = 0;
        ReconBatch latest = batchMapper.selectOne(new LambdaQueryWrapper<ReconBatch>()
                .eq(ReconBatch::getBranchCode, branchCode)
                .eq(ReconBatch::getBizDate, bizDate)
                .eq(ReconBatch::getStatus, ReconBatch.STATUS_DONE)
                .orderByDesc(ReconBatch::getId)
                .last("LIMIT 1"));
        if (latest != null) {
            matched = latest.getMatchedCount();
        }

        LocalDateTime now = LocalDateTime.now();
        ReconStatement s = existing != null ? existing : new ReconStatement();
        s.setBranchCode(branchCode);
        s.setBizDate(bizDate);
        s.setStatus(ReconStatement.STATUS_GENERATED);
        s.setInternalCount(internals.size());
        s.setExternalCount(externals.size());
        s.setMatchedCount(matched);
        s.setDiffOpenCount((int) open);
        s.setDiffResolvedCount((int) resolved);
        s.setTotalInternalAmount(totalIn);
        s.setTotalExternalAmount(totalEx);
        s.setGapAmount(totalIn.subtract(totalEx));
        if (existing == null) {
            s.setStatementNo("RS" + now.format(NO_FMT)
                    + String.format("%04d", ThreadLocalRandom.current().nextInt(10000)));
            s.setGeneratedBy(com.evops.common.RequestContext.operator());
            s.setGeneratedTime(now);
            statementMapper.insert(s);
        } else {
            statementMapper.updateById(s);
        }
        return statementMapper.selectById(s.getId());
    }

    private ReconStatement findStatement(String branchCode, LocalDate bizDate) {
        return statementMapper.selectOne(new LambdaQueryWrapper<ReconStatement>()
                .eq(ReconStatement::getBranchCode, branchCode)
                .eq(ReconStatement::getBizDate, bizDate));
    }

    // =====================================================================
    // 查询
    // =====================================================================

    public List<ReconDiff> listDiffs(String branchCode, LocalDate bizDate, String status) {
        return diffMapper.selectList(new LambdaQueryWrapper<ReconDiff>()
                .eq(branchCode != null && !branchCode.trim().isEmpty(),
                        ReconDiff::getBranchCode, branchCode == null ? null : branchCode.trim())
                .eq(bizDate != null, ReconDiff::getBizDate, bizDate)
                .eq(status != null && !status.trim().isEmpty(),
                        ReconDiff::getStatus, status == null ? null : status.trim().toUpperCase())
                .orderByAsc(ReconDiff::getBizDate)
                .orderByDesc(ReconDiff::getId));
    }

    public ReconDiff getDiff(Long id) {
        ReconDiff diff = diffMapper.selectById(id);
        if (diff == null) {
            throw new BizException("差异记录不存在：" + id);
        }
        return diff;
    }

    public ReconStatement getStatement(Long id) {
        ReconStatement statement = statementMapper.selectById(id);
        if (statement == null) {
            throw new BizException("对账单不存在：" + id);
        }
        return statement;
    }

    public List<ReconStatement> listStatements(String branchCode, LocalDate bizDate) {
        return statementMapper.selectList(new LambdaQueryWrapper<ReconStatement>()
                .eq(branchCode != null && !branchCode.trim().isEmpty(),
                        ReconStatement::getBranchCode, branchCode == null ? null : branchCode.trim())
                .eq(bizDate != null, ReconStatement::getBizDate, bizDate)
                .orderByDesc(ReconStatement::getBizDate)
                .orderByDesc(ReconStatement::getId));
    }

    public List<ReconBatch> listBatches(String branchCode, LocalDate bizDate) {
        return batchMapper.selectList(new LambdaQueryWrapper<ReconBatch>()
                .eq(branchCode != null && !branchCode.trim().isEmpty(),
                        ReconBatch::getBranchCode, branchCode == null ? null : branchCode.trim())
                .eq(bizDate != null, ReconBatch::getBizDate, bizDate)
                .orderByDesc(ReconBatch::getId));
    }

    public List<InternalTxn> listInternal(String branchCode, LocalDate bizDate) {
        return internalMapper.selectList(new LambdaQueryWrapper<InternalTxn>()
                .eq(branchCode != null && !branchCode.trim().isEmpty(),
                        InternalTxn::getBranchCode, branchCode == null ? null : branchCode.trim())
                .eq(bizDate != null, InternalTxn::getBizDate, bizDate)
                .orderByAsc(InternalTxn::getId));
    }

    public List<ExternalTxn> listExternal(String branchCode, LocalDate bizDate) {
        return externalMapper.selectList(new LambdaQueryWrapper<ExternalTxn>()
                .eq(branchCode != null && !branchCode.trim().isEmpty(),
                        ExternalTxn::getBranchCode, branchCode == null ? null : branchCode.trim())
                .eq(bizDate != null, ExternalTxn::getBizDate, bizDate)
                .orderByAsc(ExternalTxn::getId));
    }

    /**
     * 日结对账覆盖的网点：当天有 RETURN/REVERSE 事件的订单归属网点，
     * 并集当天导入过外部流水的网点（保证只有单边账的网点也不被漏掉）。
     */
    public List<String> activeBranches(LocalDate bizDate) {
        Set<String> branches = new HashSet<>();
        LocalDateTime from = bizDate.atStartOfDay();
        LocalDateTime to = bizDate.plusDays(1).atStartOfDay();
        List<RentalOrderEvent> events = eventMapper.selectList(new LambdaQueryWrapper<RentalOrderEvent>()
                .in(RentalOrderEvent::getEventType,
                        RentalService.EventType.RETURN, RentalService.EventType.REVERSE)
                .ge(RentalOrderEvent::getBizTime, from)
                .lt(RentalOrderEvent::getBizTime, to));
        if (!events.isEmpty()) {
            orderMapper.selectBatchIds(events.stream()
                            .map(RentalOrderEvent::getOrderId).collect(Collectors.toSet()))
                    .stream()
                    .map(RentalOrder::getBranchCode)
                    .filter(c -> c != null && !c.trim().isEmpty())
                    .forEach(branches::add);
        }
        externalMapper.selectList(new LambdaQueryWrapper<ExternalTxn>()
                        .select(ExternalTxn::getBranchCode)
                        .eq(ExternalTxn::getBizDate, bizDate))
                .stream()
                .map(ExternalTxn::getBranchCode)
                .filter(c -> c != null && !c.trim().isEmpty())
                .forEach(branches::add);
        return new ArrayList<>(branches);
    }

    // =====================================================================
    // 配置
    // =====================================================================

    /** 当前生效匹配容差（配置单行 id=1，schema 初始化保证存在）。 */
    public BigDecimal currentTolerance() {
        ReconConfig config = configMapper.selectById(ReconConfig.SINGLETON_ID);
        return config == null || config.getMatchTolerance() == null
                ? BigDecimal.ZERO : config.getMatchTolerance();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
