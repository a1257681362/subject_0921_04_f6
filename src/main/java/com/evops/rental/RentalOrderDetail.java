package com.evops.rental;

import java.util.List;

/**
 * 租车单详情：订单本体 + 全部交接记录 + 全部事件流水 + 状态流转历史（全量记录视图）。
 */
public class RentalOrderDetail {

    private RentalOrder order;
    private List<RentalHandover> handovers;
    private List<RentalOrderEvent> events;
    private List<com.evops.rental.fsm.RentalOrderTransition> transitions;

    public RentalOrderDetail(RentalOrder order,
                             List<RentalHandover> handovers,
                             List<RentalOrderEvent> events,
                             List<com.evops.rental.fsm.RentalOrderTransition> transitions) {
        this.order = order;
        this.handovers = handovers;
        this.events = events;
        this.transitions = transitions;
    }

    public RentalOrder getOrder() { return order; }
    public List<RentalHandover> getHandovers() { return handovers; }
    public List<RentalOrderEvent> getEvents() { return events; }
    public List<com.evops.rental.fsm.RentalOrderTransition> getTransitions() { return transitions; }
}
