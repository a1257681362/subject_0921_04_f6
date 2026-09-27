package com.evops.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.evops.common.RequestContext;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 自动填充：
 * - 标准审计列 create_time / update_time；
 * - 业务四要素中的 operator / biz_time / request_no（每条写入都带当前请求上下文）。
 * version 由 @Version 乐观锁插件处理。
 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        fillIfNull(metaObject, "createTime", now);
        fillIfNull(metaObject, "updateTime", now);
        RequestContext.Ctx ctx = currentContext();
        if (ctx != null) {
            fillIfNull(metaObject, "operator", ctx.getOperator());
            fillIfNull(metaObject, "bizTime", ctx.getBizTime() != null ? ctx.getBizTime() : now);
            fillIfNull(metaObject, "requestNo", ctx.getRequestNo());
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        fillIfNull(metaObject, "updateTime", LocalDateTime.now());
        // 每次写入都以本次请求的操作人/请求号覆盖（实体从库里加载的旧值也要刷新）。
        // biz_time 保持首次写入值不变，各动作自己的业务时间由事件流水逐行记录。
        RequestContext.Ctx ctx = currentContext();
        if (ctx != null) {
            forceFill(metaObject, "operator", ctx.getOperator());
            forceFill(metaObject, "requestNo", ctx.getRequestNo());
        }
    }

    private RequestContext.Ctx currentContext() {
        try {
            return RequestContext.get();
        } catch (IllegalStateException ex) {
            return null;
        }
    }

    private void fillIfNull(MetaObject metaObject, String field, Object value) {
        if (metaObject.hasSetter(field) && metaObject.getValue(field) == null) {
            metaObject.setValue(field, value);
        }
    }

    private void forceFill(MetaObject metaObject, String field, Object value) {
        if (metaObject.hasSetter(field)) {
            metaObject.setValue(field, value);
        }
    }
}
