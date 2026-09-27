package com.evops.rental;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BizException;
import com.evops.rental.dto.TransitionRuleRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 订单状态机（配置驱动）：交车生命周期的唯一流转裁判。
 *
 * 合法路径集中定义在库表 t_rental_transition_rule，每次流转按 (当前状态, 动作)
 * 查规则（运行时生效，与灰度规则同口径）：
 * - 未配置/已停用的路径一律拒绝——越级与回退按规则拒绝，报出当前状态合法出路；
 * - 每次成功流转写一行 t_rental_order_transition（从哪到哪/动作/谁/何时/哪个请求），
 *   与订单更新同一事务，失败一起回滚，流转历史完整可追溯；
 * - 订单更新走 version 乐观锁并检查影响行数：并行流转同一订单只有一个成功，
 *   其余 409 整体回滚，不留半截现场；
 * - 新增状态/路径只插规则行，不改代码：自定义动作经通用流转入口即配即用；
 *   保留动作（PICKUP/RETURN/ARCHIVE/REVERSE）由专用接口承载业务规则
 *   （交接登记/车辆联动/结算），open_api 恒 OFF，通用入口不可执行；
 * - 内置主流程路径（built_in=ON，库表种子）不允许配置接口增改，防现场误配。
 */
@Service
public class OrderStateMachine {

    /** 保留动作：专用接口承载业务规则（交接登记/车辆联动/结算），通用入口不开放 */
    public static final Set<String> RESERVED_ACTIONS =
            new HashSet<>(Arrays.asList(Action.PICKUP, Action.RETURN, Action.ARCHIVE, Action.REVERSE));

    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Z0-9_]{1,16}$");
    private static final String ON = "ON";
    private static final String OFF = "OFF";

    private final RentalOrderMapper orderMapper;
    private final TransitionRuleMapper ruleMapper;
    private final OrderTransitionMapper transitionMapper;

    public OrderStateMachine(RentalOrderMapper orderMapper,
                             TransitionRuleMapper ruleMapper,
                             OrderTransitionMapper transitionMapper) {
        this.orderMapper = orderMapper;
        this.ruleMapper = ruleMapper;
        this.transitionMapper = transitionMapper;
    }

    /** 状态机动作常量（t_rental_transition_rule.action） */
    public static final class Action {
        public static final String CREATE = "CREATE";
        public static final String PICKUP = "PICKUP";
        public static final String RETURN = "RETURN";
        public static final String ARCHIVE = "ARCHIVE";
        public static final String REVERSE = "REVERSE";

        private Action() {}
    }

    // ---- 流转执行 ----

    /**
     * 校验 (当前状态, 动作) 是否有合法出路，返回目标状态。
     * 越级与回退在此按规则拒绝，并报出当前状态的合法出路。
     */
    public String requireTarget(String fromStatus, String action) {
        return requireRule(fromStatus, action).getToStatus();
    }

    /**
     * 专用接口流转：状态机校验 -> 乐观锁更新订单 -> 写流转历史。
     * 参与调用方事务，任何一步失败整体回滚。
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderTransition apply(RentalOrder order, String action, String detail) {
        TransitionRule rule = requireRule(order.getStatus(), action);
        return doApply(order, rule, detail);
    }

    /**
     * 通用流转入口：仅执行 open_api=ON 的自定义动作（新增状态/路径不改代码即生效）。
     * 保留动作恒 OFF，必须走专用接口（交接登记等业务规则不会被绕过）。
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderTransition applyOpen(RentalOrder order, String action, String detail) {
        TransitionRule rule = requireRule(order.getStatus(), action);
        if (!ON.equals(rule.getOpenApi())) {
            throw new BizException("动作 " + action + " 未开放通用流转入口，请走专用业务接口");
        }
        return doApply(order, rule, detail);
    }

    /** 初始创建流转：from_status = NULL -> CREATED。 */
    public OrderTransition recordInitial(Long orderId, String detail) {
        return recordTransition(orderId, Action.CREATE, null, RentalService.OrderStatus.CREATED, detail);
    }

    /** 流转历史：按发生顺序全程可追溯。 */
    public List<OrderTransition> history(Long orderId) {
        return transitionMapper.selectList(new LambdaQueryWrapper<OrderTransition>()
                .eq(OrderTransition::getOrderId, orderId)
                .orderByAsc(OrderTransition::getId));
    }

    // ---- 规则管理（合法路径集中定义，新增状态不改代码） ----

    /** 全部流转规则（含停用），按源状态、动作排序。 */
    public List<TransitionRule> listRules() {
        return ruleMapper.selectList(new LambdaQueryWrapper<TransitionRule>()
                .orderByAsc(TransitionRule::getFromStatus)
                .orderByAsc(TransitionRule::getAction));
    }

    /**
     * 新增或调整流转路径：同 (from_status, action) 已存在则更新。
     * 内置主流程路径（built_in=ON）禁改；保留动作 open_api 恒 OFF；
     * CREATE 为初始创建动作，不属于流转，禁止配置。
     */
    @Transactional(rollbackFor = Exception.class)
    public TransitionRule upsertRule(TransitionRuleRequest request) {
        String fromStatus = normalizeName(request.getFromStatus(), "源状态");
        String action = normalizeName(request.getAction(), "流转动作");
        String toStatus = normalizeName(request.getToStatus(), "目标状态");
        if (Action.CREATE.equals(action)) {
            throw new BizException("CREATE 为初始创建动作，不属于流转，不允许配置路径");
        }
        if (fromStatus.equals(toStatus)) {
            throw new BizException("源状态与目标状态不能相同：" + fromStatus);
        }
        String enabled = normalizeFlag(request.getEnabled(), "enabled");
        // 保留动作由专用接口承载业务规则，通用入口恒不开放
        String openApi = RESERVED_ACTIONS.contains(action) ? OFF : ON;

        TransitionRule existing = ruleMapper.selectOne(new LambdaQueryWrapper<TransitionRule>()
                .eq(TransitionRule::getFromStatus, fromStatus)
                .eq(TransitionRule::getAction, action));
        if (existing != null) {
            if (ON.equals(existing.getBuiltIn())) {
                throw new BizException("内置主流程路径不允许配置修改：" + fromStatus + " --" + action + "--> "
                        + existing.getToStatus());
            }
            existing.setToStatus(toStatus);
            existing.setOpenApi(openApi);
            existing.setEnabled(enabled);
            existing.setRemark(trimToNull(request.getRemark()));
            int updated = ruleMapper.updateById(existing);
            if (updated == 0) {
                throw new BizException("流转规则已被并发修改，请刷新后重试：" + fromStatus + "/" + action);
            }
            return ruleMapper.selectById(existing.getId());
        }

        TransitionRule rule = new TransitionRule();
        rule.setFromStatus(fromStatus);
        rule.setAction(action);
        rule.setToStatus(toStatus);
        rule.setOpenApi(openApi);
        rule.setEnabled(enabled);
        rule.setBuiltIn(OFF);
        rule.setRemark(trimToNull(request.getRemark()));
        ruleMapper.insert(rule);   // (from_status, action) 唯一约束兜底并发
        return rule;
    }

    // ---- 内部辅助 ----

    /** 查生效规则；未命中按规则拒绝，报出当前状态合法出路。 */
    private TransitionRule requireRule(String fromStatus, String action) {
        List<TransitionRule> enabledRules = ruleMapper.selectList(new LambdaQueryWrapper<TransitionRule>()
                .eq(TransitionRule::getEnabled, ON));
        TransitionRule hit = null;
        boolean actionKnown = false;
        List<String> available = new ArrayList<>();
        for (TransitionRule rule : enabledRules) {
            if (rule.getAction().equals(action)) {
                actionKnown = true;
            }
            if (rule.getFromStatus().equals(fromStatus)) {
                available.add(rule.getAction() + "->" + rule.getToStatus());
                if (rule.getAction().equals(action)) {
                    hit = rule;
                }
            }
        }
        if (hit != null) {
            return hit;
        }
        if (!actionKnown) {
            throw new BizException("未定义的流转动作：" + action);
        }
        throw new BizException("订单当前状态 " + fromStatus + " 不允许执行 " + action
                + "（越级/回退已按规则拒绝）；当前可执行："
                + (available.isEmpty() ? "无（已到终态）" : String.join("、", available)));
    }

    /** 乐观锁更新订单并写流转历史；并行流转同一订单只有一个成功。 */
    private OrderTransition doApply(RentalOrder order, TransitionRule rule, String detail) {
        String fromStatus = order.getStatus();
        order.setStatus(rule.getToStatus());
        int updated = orderMapper.updateById(order);
        if (updated == 0) {
            throw new BizException("订单已被并发修改，请刷新后重试：" + order.getId());
        }
        return recordTransition(order.getId(), rule.getAction(), fromStatus, rule.getToStatus(), detail);
    }

    private OrderTransition recordTransition(Long orderId, String action,
                                             String fromStatus, String toStatus, String detail) {
        OrderTransition transition = new OrderTransition();
        transition.setOrderId(orderId);
        transition.setAction(action);
        transition.setFromStatus(fromStatus);
        transition.setToStatus(toStatus);
        transition.setDetail(detail);
        transitionMapper.insert(transition);
        return transition;
    }

    private String normalizeName(String value, String label) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (!NAME_PATTERN.matcher(normalized).matches()) {
            throw new BizException(label + "须为 1-16 位大写字母/数字/下划线：" + value);
        }
        return normalized;
    }

    private String normalizeFlag(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            return ON;
        }
        String flag = value.trim().toUpperCase();
        if (!ON.equals(flag) && !OFF.equals(flag)) {
            throw new BizException(label + " 仅支持 ON/OFF：" + value);
        }
        return flag;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
