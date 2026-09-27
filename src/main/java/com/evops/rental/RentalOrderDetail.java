package com.evops.rental;

import java.util.List;

/**
 * 租车单详情：订单本体 + 全部交接记录 + 全部事件流水 + 全部状态流转（全量记录视图）。
 * 交接、事件、流转、订单当前状态同视图返回，查什么都对得上。
 */
public class RentalOrderDetail {

    private RentalOrder order;
    private List<RentalHandover> handovers;
    private List<RentalOrderEvent> events;
    private List<OrderTransition> transitions;

    public RentalOrderDetail(RentalOrder order,
                             List<RentalHandover> handovers,
                             List<RentalOrderEvent> events,
                             List<OrderTransition> transitions) {
        this.order = order;
        this.handovers = handovers;
        this.events = events;
        this.transitions = transitions;
    }

    public RentalOrder getOrder() { return order; }
    public List<RentalHandover> getHandovers() { return handovers; }
    public List<RentalOrderEvent> getEvents() { return events; }
    public List<OrderTransition> getTransitions() { return transitions; }
}
