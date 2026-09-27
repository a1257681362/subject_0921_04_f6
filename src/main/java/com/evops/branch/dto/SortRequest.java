package com.evops.branch.dto;

import javax.validation.constraints.NotNull;

/**
 * 同级排序配置请求：sort_order 小的在前，同值按 id。
 */
public class SortRequest {

    @NotNull(message = "排序值不能为空")
    private Integer sortOrder;

    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}
