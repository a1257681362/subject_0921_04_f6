package com.evops.rental;

import java.util.List;

/**
 * 租车单详情：订单本体 + 全部交接记录 + 全部事件流水（全量记录视图）。
 */
public class RentalOrderDetail {

    private RentalOrder order;
    private List<RentalHandover> handovers;
    private List<RentalOrderEvent> events;

    public RentalOrderDetail(RentalOrder order,
                             List<RentalHandover> handovers,
                             List<RentalOrderEvent> events) {
        this.order = order;
        this.handovers = handovers;
        this.events = events;
    }

    public RentalOrder getOrder() { return order; }
    public List<RentalHandover> getHandovers() { return handovers; }
    public List<RentalOrderEvent> getEvents() { return events; }
}
