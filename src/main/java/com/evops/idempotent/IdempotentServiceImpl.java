package com.evops.idempotent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class IdempotentServiceImpl implements IdempotentService {

    private final IdempotentRequestMapper mapper;

    public IdempotentServiceImpl(IdempotentRequestMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotentGate register(String requestNo, String operator) {
        IdempotentRequest row = new IdempotentRequest();
        row.setRequestNo(requestNo);
        row.setStatus("PROCESSING");
        row.setOperator(operator);
        row.setCreateTime(LocalDateTime.now());
        row.setUpdateTime(LocalDateTime.now());
        try {
            mapper.insert(row);
            return IdempotentGate.accepted();
        } catch (DuplicateKeyException dup) {
            // 已有记录，按既有状态判定
        }

        IdempotentRequest existed = mapper.selectById(requestNo);
        if (existed == null) {
            // 理论不可达：插入冲突却查不到。兜底再试一次。
            mapper.insert(row);
            return IdempotentGate.accepted();
        }
        switch (existed.getStatus()) {
            case "DONE":
                return IdempotentGate.alreadyDone(existed.getResponseBody());
            case "PROCESSING":
                return IdempotentGate.inProgress();
            case "FAILED":
                int updated = mapper.update(null, new LambdaUpdateWrapper<IdempotentRequest>()
                        .eq(IdempotentRequest::getRequestNo, requestNo)
                        .eq(IdempotentRequest::getStatus, "FAILED")
                        .set(IdempotentRequest::getStatus, "PROCESSING")
                        .set(IdempotentRequest::getOperator, operator)
                        .set(IdempotentRequest::getUpdateTime, LocalDateTime.now()));
                return updated == 1 ? IdempotentGate.retryAccepted() : IdempotentGate.inProgress();
            default:
                return IdempotentGate.inProgress();
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDone(String requestNo) {
        mapper.update(null, new LambdaUpdateWrapper<IdempotentRequest>()
                .eq(IdempotentRequest::getRequestNo, requestNo)
                .set(IdempotentRequest::getStatus, "DONE")
                .set(IdempotentRequest::getUpdateTime, LocalDateTime.now()));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String requestNo) {
        mapper.update(null, new LambdaUpdateWrapper<IdempotentRequest>()
                .eq(IdempotentRequest::getRequestNo, requestNo)
                .eq(IdempotentRequest::getStatus, "PROCESSING")
                .set(IdempotentRequest::getStatus, "FAILED")
                .set(IdempotentRequest::getUpdateTime, LocalDateTime.now()));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long count() {
        return mapper.selectCount(new LambdaQueryWrapper<>());
    }
}
