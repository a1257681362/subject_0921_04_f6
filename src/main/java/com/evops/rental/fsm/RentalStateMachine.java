package com.evops.rental.fsm;

import com.evops.common.BizException;
import com.evops.rental.RentalOrder;
import com.evops.rental.RentalOrderMapper;
import org.springframework.stereotype.Component;

/**
 * 租车单状态机引擎：合法路径判定集中在 {@link RentalStateMachineConfig}，
 * 引擎只做三件事——校验、CAS 状态推进、同事务写流转历史。
 *
 * 并发安全：状态更新走 MyBatis-Plus 乐观锁，
 * {@code UPDATE ... SET status=?, version=version+1 WHERE id=? AND version=?}。
 * 两个并行流转（如现场重复点交车、调度与网点同时操作）只有影响行数=1 的那一个成功，
 * 另一个影响行数=0，按并发冲突抛错，整个业务事务回滚（交接/事件/历史都不落半截）。
 */
@Component
public class RentalStateMachine {

    private final RentalStateMachineConfig config;
    private final RentalOrderMapper orderMapper;
    private final RentalOrderTransitionMapper transitionMapper;

    public RentalStateMachine(RentalStateMachineConfig config,
                              RentalOrderMapper orderMapper,
                              RentalOrderTransitionMapper transitionMapper) {
        this.config = config;
        this.orderMapper = orderMapper;
        this.transitionMapper = transitionMapper;
    }

    /**
     * 在当前订单上执行动作。调用方必须在业务事务内，并已完成动作自身的业务校验
     * （油量/照片、车辆在库等）与业务行写入（交接记录）；本方法负责状态推进与留痕。
     *
     * @param order      当前订单（状态为流转前状态，携带乐观锁版本）
     * @param action     流转动作，需在状态机配置内
     * @param handoverId 关联交接 id（无交接的动作传 null）
     * @param remark     现场备注/原因（可空）
     * @return 已落库的流转历史行
     */
    public RentalOrderTransition fire(RentalOrder order, String action, Long handoverId, String remark) {
        StateFlowRule rule = config.requireRule(action, order.getStatus());

        String fromStatus = order.getStatus();
        Integer fromVersion = order.getVersion();
        order.setStatus(rule.getTo());

        int affected = orderMapper.updateById(order);
        if (affected == 0) {
            // 并行流转的败者：版本已被成功者推进，CAS 不命中
            throw new BizException("订单状态已被其他操作变更，本次 " + action.trim().toUpperCase()
                    + " 流转未生效（并发冲突），请刷新后重试；订单：" + order.getId());
        }

        RentalOrderTransition transition = new RentalOrderTransition();
        transition.setOrderId(order.getId());
        transition.setAction(rule.getAction());
        transition.setFromStatus(fromStatus);
        transition.setToStatus(rule.getTo());
        transition.setFromVersion(fromVersion);
        // 乐观锁插件更新成功后会把实体版本回填为自增后的值
        transition.setToVersion(order.getVersion());
        transition.setHandoverId(handoverId);
        String trimmedRemark = remark == null ? null : remark.trim();
        transition.setRemark(trimmedRemark == null || trimmedRemark.isEmpty() ? null : trimmedRemark);
        transitionMapper.insert(transition);
        return transition;
    }

    /** 不改状态，仅取某状态下当前可执行的合法动作（现场提示/视图）。 */
    public java.util.List<StateFlowRule> availableActions(String status) {
        return config.availableActions(status);
    }
}
