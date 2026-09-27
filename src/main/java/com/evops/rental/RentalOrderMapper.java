package com.evops.rental;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface RentalOrderMapper extends com.baomidou.mybatisplus.core.mapper.BaseMapper<RentalOrder> {

    /**
     * 客户姓名检索（拼音索引已在 Service 侧灰度闸后进入）。
     * 关键词在 Service 侧经 PinyinNames.normalize 归一，此处与索引三列做包含匹配：
     * normalized_name 命中中文/外文原文，full_pinyin 命中全拼，initials 命中首字母。
     *
     * 相关度评分 rel_score 放在内层派生表、排序放外层：精确(原文&gt;全拼&gt;首字母)
     * &gt; 前缀(原文&gt;全拼&gt;首字母) &gt; 包含(原文&gt;全拼&gt;首字母)；同分按业务时间、id 倒序。
     * 这样 MyBatis-Plus 分页插件生成 count 语句时（SELECT COUNT(*) FROM (内层) t）
     * ORDER BY 在外层被剥离，内层只剩纯投影，可被聚合合法包裹（否则 ORDER BY 非分组列在
     * H2 下报 90016）。其余订单号/状态/业务时间条件与原组合检索一致。
     */
    @Select("<script>"
            + "SELECT t.* FROM ("
            + "  SELECT o.*, CASE"
            + "    WHEN ix.normalized_name = #{kw} THEN 100"
            + "    WHEN ix.full_pinyin = #{kw} THEN 90"
            + "    WHEN ix.initials = #{kw} THEN 85"
            + "    WHEN ix.normalized_name LIKE CONCAT(#{kw}, '%') THEN 80"
            + "    WHEN ix.full_pinyin LIKE CONCAT(#{kw}, '%') THEN 60"
            + "    WHEN ix.initials LIKE CONCAT(#{kw}, '%') THEN 55"
            + "    WHEN ix.normalized_name LIKE CONCAT('%', #{kw}, '%') THEN 40"
            + "    WHEN ix.full_pinyin LIKE CONCAT('%', #{kw}, '%') THEN 30"
            + "    WHEN ix.initials LIKE CONCAT('%', #{kw}, '%') THEN 25"
            + "    ELSE 20 END AS rel_score"
            + "  FROM t_rental_order o"
            + "  JOIN t_rental_order_name_index ix ON ix.order_id = o.id"
            + "  WHERE (ix.normalized_name LIKE CONCAT('%', #{kw}, '%')"
            + "      OR ix.full_pinyin LIKE CONCAT('%', #{kw}, '%')"
            + "      OR ix.initials LIKE CONCAT('%', #{kw}, '%'))"
            + "  <if test='orderNo != null'> AND o.order_no LIKE CONCAT('%', #{orderNo}, '%') </if>"
            + "  <if test='status != null'> AND o.status = #{status} </if>"
            + "  <if test='bizTimeFrom != null'> AND o.biz_time &gt;= #{bizTimeFrom} </if>"
            + "  <if test='bizTimeTo != null'> AND o.biz_time &lt;= #{bizTimeTo} </if>"
            + ") t"
            + " ORDER BY t.rel_score DESC, t.biz_time DESC, t.id DESC"
            + "</script>")
    IPage<RentalOrder> searchByName(IPage<RentalOrder> page,
                                    @Param("kw") String kw,
                                    @Param("orderNo") String orderNo,
                                    @Param("status") String status,
                                    @Param("bizTimeFrom") LocalDateTime bizTimeFrom,
                                    @Param("bizTimeTo") LocalDateTime bizTimeTo);
}
