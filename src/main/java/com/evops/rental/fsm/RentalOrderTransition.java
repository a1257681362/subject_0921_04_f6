package com.evops.rental.fsm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

/**
 * 租车单状态流转历史（交车生命周期审计链）。
 *
 * 每一次成功流转在业务事务内落一行：动作、起止状态、版本前后、关联交接（可空）、
 * 操作人/业务时间/请求号（BizEntity 四要素）。状态机只配置不写死，
 * 动作与状态均为字符串，新增状态不改表结构、不改代码。
 *
 * 与 t_rental_order_event 的分工：event 记业务动作叙述（含改名等非状态动作）；
 * transition 只记状态机流转，字段结构化，查任何一次状态变化都能对上行。
 */
@TableName("t_rental_order_transition")
public class RentalOrderTransition extends BizEntity {

    private Long orderId;

    /** 流转动作（状态机配置中的 action） */
    private String action;

    /** 起始状态 */
    private String fromStatus;

    /** 目标状态 */
    private String toStatus;

    /** 流转前订单版本（乐观锁） */
    private Integer fromVersion;

    /** 流转后订单版本 */
    private Integer toVersion;

    /** 关联交接记录 id（交车/还车等带交接的动作；纯状态动作为空） */
    private Long handoverId;

    /** 现场备注/原因（如冲销原因） */
    private String remark;

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getFromStatus() { return fromStatus; }
    public void setFromStatus(String fromStatus) { this.fromStatus = fromStatus; }
    public String getToStatus() { return toStatus; }
    public void setToStatus(String toStatus) { this.toStatus = toStatus; }
    public Integer getFromVersion() { return fromVersion; }
    public void setFromVersion(Integer fromVersion) { this.fromVersion = fromVersion; }
    public Integer getToVersion() { return toVersion; }
    public void setToVersion(Integer toVersion) { this.toVersion = toVersion; }
    public Long getHandoverId() { return handoverId; }
    public void setHandoverId(Long handoverId) { this.handoverId = handoverId; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
