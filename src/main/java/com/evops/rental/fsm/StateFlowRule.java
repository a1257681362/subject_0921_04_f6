package com.evops.rental.fsm;

/**
 * 单条合法流转规则（配置驱动，集中定义于 application.yml）。
 *
 * 一条规则 = 动作 action 在起始状态 from 下允许流转到目标状态 to。
 * 同一动作可配多条起始状态（如 REVERSE：SETTLED / ARCHIVED -> REVERSED）。
 * 新增状态或动作只需加配置，不改代码；通用流转接口 POST /api/orders/{id}/transitions 直接承载。
 */
public class StateFlowRule {

    /** 流转动作（PICKUP/RETURN/ARCHIVE/REVERSE/...），同一动作可配多个 from */
    private String action;

    /** 起始状态 */
    private String from;

    /** 目标状态 */
    private String to;

    /**
     * 是否正向流转：true=沿生命周期向前，false=规则显式允许的逆向动作。
     * 仅用于合法路径视图标注；是否放行只认 from/to，规则之外一律拒绝（越级、回退皆在其中）。
     */
    private boolean forward = true;

    /** 动作中文名（视图/报错可读） */
    private String name;

    /** 规则备注（现场口径，视图返回） */
    private String note;

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }
    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }
    public boolean isForward() { return forward; }
    public void setForward(boolean forward) { this.forward = forward; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
