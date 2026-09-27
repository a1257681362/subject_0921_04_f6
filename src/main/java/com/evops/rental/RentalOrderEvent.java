package com.evops.rental;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

/**
 * 订单事件流水：CREATE/PICKUP/RETURN/SETTLE/ARCHIVE/REVERSE 全程留痕。
 */
@TableName("t_rental_order_event")
public class RentalOrderEvent extends BizEntity {

    private Long orderId;

    private String eventType;

    private String detail;

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
