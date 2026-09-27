package com.evops.gray;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.gray.dto.GrayRuleRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 灰度规则管理：按 (scene, branch_code) 新增或开关。
 * 规则变更走入口幂等拦截，行上自带操作人/业务时间/请求号留痕。
 */
@Service
public class GrayRuleService {

    private final GrayRuleMapper grayRuleMapper;

    public GrayRuleService(GrayRuleMapper grayRuleMapper) {
        this.grayRuleMapper = grayRuleMapper;
    }

    /** 新增或调整灰度规则：同 (scene, branch_code) 已存在则更新状态。 */
    @Transactional(rollbackFor = Exception.class)
    public GrayRule upsert(GrayRuleRequest request) {
        String scene = StringUtils.hasText(request.getScene())
                ? request.getScene().trim() : GrayRule.SCENE_BRANCH_TREE;
        String branchCode = request.getBranchCode().trim();

        GrayRule existing = grayRuleMapper.selectOne(new LambdaQueryWrapper<GrayRule>()
                .eq(GrayRule::getScene, scene)
                .eq(GrayRule::getBranchCode, branchCode));
        if (existing != null) {
            existing.setStatus(request.getStatus());
            grayRuleMapper.updateById(existing);
            return grayRuleMapper.selectById(existing.getId());
        }

        GrayRule rule = new GrayRule();
        rule.setScene(scene);
        rule.setBranchCode(branchCode);
        rule.setStatus(request.getStatus());
        grayRuleMapper.insert(rule);   // (scene, branch_code) 唯一约束兜底并发
        return rule;
    }

    public List<GrayRule> list(String scene) {
        return grayRuleMapper.selectList(new LambdaQueryWrapper<GrayRule>()
                .eq(StringUtils.hasText(scene), GrayRule::getScene,
                        scene == null ? null : scene.trim())
                .orderByAsc(GrayRule::getScene)
                .orderByAsc(GrayRule::getBranchCode));
    }
}
