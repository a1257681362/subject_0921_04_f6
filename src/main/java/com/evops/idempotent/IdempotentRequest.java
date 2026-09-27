package com.evops.idempotent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 入口幂等请求记录。状态机：
 * PROCESSING 处理中（登记即独立事务提交，拦截并发重复提交）
 *   -> DONE 成功 / FAILED 失败（失败后允许同一请求号重试）
 */
@TableName("t_idempotent_request")
public class IdempotentRequest {

    @TableId(type = IdType.INPUT)
    private String requestNo;

    /** PROCESSING / DONE / FAILED */
    private String status;

    private String operator;

    private String responseBody;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public String getRequestNo() { return requestNo; }
    public void setRequestNo(String requestNo) { this.requestNo = requestNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
