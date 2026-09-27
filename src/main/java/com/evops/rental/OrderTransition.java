package com.evops.rental;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

/**
 * 订单流转历史：每次状态流转一行（含初始创建 from_status=NULL）。
 * 与订单更新同一事务写入，链条逐行衔接、末行即订单当前状态，全程可追溯。
 */
@TableName("t_rental_order_transition")
public class OrderTransition extends BizEntity {

    private Long orderId;

    /** 流转动作（CREATE 为初始创建） */
    private String action;

    /** 源状态（NULL = 初始创建） */
    private String fromStatus;

    /** 目标状态 */
    private String toStatus;

    private String detail;

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getFromStatus() { return fromStatus; }
    public void setFromStatus(String fromStatus) { this.fromStatus = fromStatus; }
    public String getToStatus() { return toStatus; }
    public void setToStatus(String toStatus) { this.toStatus = toStatus; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
