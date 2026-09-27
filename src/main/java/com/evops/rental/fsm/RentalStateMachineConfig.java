package com.evops.rental.fsm;

import com.evops.common.BizException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 交车生命周期状态机配置（{@code evops.rental.fsm.*}）。
 *
 * 合法路径集中在 application.yml 的 transitions 列表定义，代码不再硬编码状态判断：
 * <pre>
 * evops.rental.fsm:
 *   initial-status: CREATED
 *   transitions:
 *     - {action: PICKUP,  from: CREATED,  to: RENTING}
 *     - {action: RETURN,  from: RENTING,  to: SETTLED}
 *     - {action: ARCHIVE, from: SETTLED,  to: ARCHIVED}
 *     - {action: REVERSE, from: SETTLED,  to: REVERSED, forward: false}
 *     - {action: REVERSE, from: ARCHIVED, to: REVERSED, forward: false}
 * </pre>
 *
 * 越级（如 CREATED 直接 ARCHIVE）、回退（如 RENTING 退回 CREATED）、未知动作，
 * 都因“不在合法路径内”被同一条规则拒绝；新增状态/动作只加配置，不改代码。
 */
@Component
@ConfigurationProperties(prefix = "evops.rental.fsm")
public class RentalStateMachineConfig {

    /** 生命周期初始状态（下单落此状态） */
    private String initialStatus = "CREATED";

    /** 合法流转路径全集（顺序即现场生命周期顺序，视图按此返回） */
    private List<StateFlowRule> transitions = new ArrayList<>();

    /** 动作 -> 中文名（视图/报错可读；transitions[].name 优先） */
    private Map<String, String> actionNames = new LinkedHashMap<>();

    /** 状态 -> 中文名（视图可读，可空） */
    private Map<String, String> statusNames = new LinkedHashMap<>();

    /** 索引：action|from -> 规则 */
    private final Map<String, StateFlowRule> ruleIndex = new LinkedHashMap<>();

    /** 全部已知状态（规则端点 + initialStatus） */
    private final Set<String> knownStatuses = new LinkedHashSet<>();

    @PostConstruct
    public void validate() {
        ruleIndex.clear();
        knownStatuses.clear();
        if (initialStatus == null || initialStatus.trim().isEmpty()) {
            throw new IllegalStateException("evops.rental.fsm.initial-status 不能为空");
        }
        initialStatus = initialStatus.trim().toUpperCase();
        knownStatuses.add(initialStatus);

        for (StateFlowRule rule : transitions) {
            if (rule.getAction() == null || rule.getAction().trim().isEmpty()
                    || rule.getFrom() == null || rule.getFrom().trim().isEmpty()
                    || rule.getTo() == null || rule.getTo().trim().isEmpty()) {
                throw new IllegalStateException(
                        "状态机规则必须同时配置 action/from/to：" + rule);
            }
            rule.setAction(rule.getAction().trim().toUpperCase());
            rule.setFrom(rule.getFrom().trim().toUpperCase());
            rule.setTo(rule.getTo().trim().toUpperCase());
            knownStatuses.add(rule.getFrom());
            knownStatuses.add(rule.getTo());

            String key = key(rule.getAction(), rule.getFrom());
            StateFlowRule exists = ruleIndex.get(key);
            if (exists != null) {
                throw new IllegalStateException(String.format(
                        "状态机规则冲突：动作 %s 在状态 %s 下同时配置了 -> %s 与 -> %s",
                        rule.getAction(), rule.getFrom(), exists.getTo(), rule.getTo()));
            }
            ruleIndex.put(key, rule);
        }
        if (ruleIndex.isEmpty()) {
            throw new IllegalStateException("evops.rental.fsm.transitions 未配置任何合法流转路径");
        }
    }

    /**
     * 查合法路径：返回 action 在 from 状态下唯一允许的规则；不在配置内即拒绝
     * （越级、回退、未知动作同一出口）。
     */
    public StateFlowRule findRule(String action, String from) {
        if (action == null || from == null) {
            return null;
        }
        return ruleIndex.get(key(action.trim().toUpperCase(), from.trim().toUpperCase()));
    }

    /** 某状态下全部可执行动作（现场操作提示/视图用） */
    public List<StateFlowRule> availableActions(String status) {
        List<StateFlowRule> result = new ArrayList<>();
        if (status == null) {
            return result;
        }
        String current = status.trim().toUpperCase();
        for (StateFlowRule rule : transitions) {
            if (rule.getFrom().equals(current)) {
                result.add(rule);
            }
        }
        return result;
    }

    public String actionName(String action) {
        StateFlowRule first = null;
        for (StateFlowRule rule : transitions) {
            if (rule.getAction().equals(action)) {
                first = rule;
                break;
            }
        }
        if (first != null && first.getName() != null && !first.getName().trim().isEmpty()) {
            return first.getName().trim();
        }
        return actionNames.getOrDefault(action, action);
    }

    public String statusName(String status) {
        return statusNames.getOrDefault(status, status);
    }

    public String getInitialStatus() { return initialStatus; }
    public void setInitialStatus(String initialStatus) { this.initialStatus = initialStatus; }
    public List<StateFlowRule> getTransitions() { return transitions; }
    public void setTransitions(List<StateFlowRule> transitions) { this.transitions = transitions; }
    public Map<String, String> getActionNames() { return actionNames; }
    public void setActionNames(Map<String, String> actionNames) { this.actionNames = actionNames; }
    public Map<String, String> getStatusNames() { return statusNames; }
    public void setStatusNames(Map<String, String> statusNames) { this.statusNames = statusNames; }
    public Set<String> getKnownStatuses() { return Collections.unmodifiableSet(knownStatuses); }

    private static String key(String action, String from) {
        return action + "|" + from;
    }

    /** 流转不合法时的统一拒绝原因（越级/回退/未知动作均由此出口）。 */
    public String rejectReason(String action, String from) {
        String act = action == null ? "" : action.trim().toUpperCase();
        String cur = from == null ? "" : from.trim().toUpperCase();
        List<StateFlowRule> sameAction = new ArrayList<>();
        for (StateFlowRule rule : transitions) {
            if (rule.getAction().equals(act)) {
                sameAction.add(rule);
            }
        }
        if (sameAction.isEmpty()) {
            return "未知流转动作：" + act + "（合法动作以状态机配置为准：" + actionsText() + "）";
        }
        List<String> allowedFrom = new ArrayList<>();
        for (StateFlowRule rule : sameAction) {
            allowedFrom.add(rule.getFrom());
        }
        // 动作存在但当前状态不是其任一合法起始状态：越级或回退
        return "当前状态 " + cur + " 不允许执行 " + act + "（" + actionName(act)
                + "），越级/回退流转已拒绝；该动作仅允许状态：" + String.join("/", allowedFrom);
    }

    private String actionsText() {
        Set<String> actions = new LinkedHashSet<>();
        for (StateFlowRule rule : transitions) {
            actions.add(rule.getAction());
        }
        return String.join("/", actions);
    }

    /** 便捷断言：不合法直接抛业务冲突（409）。 */
    public StateFlowRule requireRule(String action, String from) {
        StateFlowRule rule = findRule(action, from);
        if (rule == null) {
            throw new BizException(rejectReason(action, from));
        }
        return rule;
    }
}
