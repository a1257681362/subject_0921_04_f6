# EVOPS 基础工作区

这是 springboot的骨架项目。

技术栈：Java 8、Spring Boot 2.7、MyBatis-Plus、H2 本地文件数据库、Shiro、Thymeleaf。

启动前准备：

1. 执行 `mvn -q -DskipTests compile` 验证骨架构建。
2. 执行 `mvn spring-boot:run` 启动应用；Spring Boot 会自动执行 `src/main/resources/schema.sql`。
3. H2 数据文件默认写入工作区 `data/evops`。如需修改路径，编辑 `application.yml` 中的 `jdbc:h2:file:` 连接串。

工作区 `docs/schema/evops.sql` 是交付副本，应与工作区 `src/main/resources/schema.sql` 保持一致。

## 租车业务（rental-1）

### 已落地的三块

1. **车辆台账** `t_vehicle`（车牌唯一）
2. **租车单全量记录** `t_rental_order`（订单号唯一约束；全量留痕，禁止物理删除，已结算/已归档只许冲销）
3. **订单号 + 业务时间组合检索** `GET /api/orders?orderNo=&bizTimeFrom=&bizTimeTo=`

### 主流程状态机

```
下单 CREATED --交车--> RENTING --还车结算--> SETTLED --归档--> ARCHIVED
SETTLED / ARCHIVED --冲销--> REVERSED
```

- 交车 `POST /api/orders/{id}/pickup`、还车 `POST /api/orders/{id}/return`：必须登记油量表读数与车损照片。
- 每个业务动作的多表写入（订单/交接/事件/车辆状态）共用一个事务，中途失败全部回退。
- 全程留痕：事件流水 `t_rental_order_event`（CREATE/PICKUP/RETURN/ARCHIVE/REVERSE）。

### 写入约定

- 写请求（POST/PUT/DELETE）必须携带请求头：
  - `X-Operator` 操作人
  - `X-Request-No` 请求号（入口幂等键；重复提交 409 拦截，库层唯一约束兜底）
  - `X-Biz-Time` 业务时间（可选，`yyyy-MM-dd'T'HH:mm:ss`，缺省取服务端当前时间）
- 每条写入都带：操作人、业务时间、请求号、版本号（乐观锁自增）。
- 幂等记录 `t_idempotent_request`：PROCESSING/DONE/FAILED 三态；业务失败可同请求号重试。

### 接口速览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/vehicles | 车辆入账 |
| GET | /api/vehicles, /api/vehicles/{id} | 车辆查询 |
| POST | /api/orders | 下单（orderNo 可传可省，省略自动生成） |
| POST | /api/orders/{id}/pickup | 交车（油量+车损照片必填） |
| POST | /api/orders/{id}/return | 还车并结算（油量+车损照片必填） |
| POST | /api/orders/{id}/archive | 归档 |
| POST | /api/orders/{id}/reverse | 冲销（仅 SETTLED/ARCHIVED） |
| GET | /api/orders | 订单号+业务时间组合检索（分页） |
| GET | /api/orders/{id} | 详情：订单+交接+事件流水 |
| DELETE | /api/orders/{id} | 一律拒绝（禁止物理删除） |

### 测试

`mvn test`：9 个集成用例覆盖主流程、重复提交拦截、订单号唯一兜底、多表写入事务回滚、删除/冲销规则、组合检索、入参校验。

## 订单状态机（rental-state-machine-1）

背景：调度员带人现场处理交车，越级、回退、并行操作层出不穷，事后对不上账。本期把交车生命周期收进配置驱动的状态机：合法路径集中定义在库表，越级与回退按规则拒绝，每次流转全程留痕，并行流转只有一个成功，新增状态不改代码。

### 合法路径集中定义（配置驱动）

`t_rental_transition_rule` 集中定义全部合法流转路径（源状态 + 动作 -> 目标状态），每次流转按 (当前状态, 动作) 查规则，运行时生效：

- 主流程种子路径（`built_in=ON`，配置接口禁改）：`CREATED --PICKUP--> RENTING --RETURN--> SETTLED --ARCHIVE--> ARCHIVED`，`SETTLED/ARCHIVED --REVERSE--> REVERSED`；
- **越级与回退按规则拒绝**：未配置/已停用的路径一律 409，报文给出当前状态的合法出路（如 `当前状态 CREATED 不允许执行 RETURN；当前可执行：PICKUP->RENTING`）；
- **新增状态不改代码**：`POST /api/orders/state-machine/transitions` 插入规则行即生效（如 `SETTLED --RECHECK--> RECHECKED`），状态与动作名不区分大小写、落库统一大写；库层状态 CHECK 约束已退役，合法状态全集以规则表为准；
- 规则可停用（`enabled=OFF` 立即失效）；行上自带操作人/业务时间/请求号留痕。

### 两条流转通路（都过同一状态机裁决）

- **专用接口**（pickup/return/archive/reverse）：承载业务规则——交/还车必须登记油量表读数与车损照片、车辆状态联动、结算金额；保留动作 `PICKUP/RETURN/ARCHIVE/REVERSE` 的 `open_api` 恒为 OFF；
- **通用流转入口** `POST /api/orders/{id}/transitions {"action":"RECHECK"}`：只执行 `open_api=ON` 的自定义动作——新增状态/路径即配即用，不改代码；新状态也可配置接入既有专用接口（如配 `RECHECKED --ARCHIVE--> ARCHIVED` 后直接调 `/archive`）。

### 流转历史完整可追溯

`t_rental_order_transition` 每次成功流转写一行（含初始创建 `from_status=NULL`）：从哪到哪、动作、操作人、业务时间、请求号，与订单更新同一事务、失败一起回滚。链条逐行衔接（上行目标状态 = 下行源状态），末行目标状态即订单当前状态。`GET /api/orders/{id}` 详情同视图返回订单+交接+事件+流转，查什么都对得上；`GET /api/orders/{id}/transitions` 单查流转链。

### 并行流转只有一个成功

订单更新走 `version` 乐观锁并检查影响行数：多个现场终端同时流转同一订单，只有一个提交成功，其余 409（`订单已被并发修改，请刷新后重试`）整体回滚——交接、事件、流转历史不留半截。

### 接口速览（新增）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /api/orders/state-machine/transitions | 合法路径全量列表（含停用），集中定义可查 |
| POST | /api/orders/state-machine/transitions | 新增/调整流转路径（新增状态不改代码；内置路径禁改） |
| POST | /api/orders/{id}/transitions | 通用流转入口（仅 open_api=ON 的自定义动作） |
| GET | /api/orders/{id}/transitions | 流转历史（从哪到哪/谁/何时/哪个请求） |
| GET | /api/orders/{id} | 详情新增 transitions 字段（订单+交接+事件+流转同视图） |

### 测试

`mvn test`：新增 5 个集成用例覆盖越级/回退拒绝与出路提示、流转历史链条衔接与四要素留痕、4 线程并行交车只有一个成功（交接/事件/流转各只留一份）、配置新状态新路径不改代码即生效并接入既有专用接口、通用入口与规则管理保护（保留动作/内置路径/初始动作/自环/非法名/停用即失效）。

## 客户姓名拼音检索（rental-name-search-1）

背景：总部稽核要求客户姓名记不全也能检索。为租车单客户姓名建拼音索引，基于 pinyin4j 生成全拼与首字母，读路径经库表灰度开关控制，可在线即时回退。

### 检索能力

`GET /api/orders` 新增可选参数 `customerName`，与原订单号/状态/业务时间条件可任意组合：

- **全拼**：`zhangsanfeng`、`zhangsan`（记不全，前缀/片段可查）；
- **首字母**：`zsf`、`zs`；
- **原文直查**：中文 `张三`、外文 `john` 同样可查（索引冗余规范化原文）；
- **容错**：大小写不敏感；全角/半角经 NFKC 归一（`ＺＳＦ`=`zsf`）；自动压缩空格与标点；外文字母、数字原样保留（`张a三1` → 全拼 `zhangasan1`、首字母 `zas1`）；
- **多音字**取拼音库常用首读，全拼与首字母用同一读音，结果稳定；
- **相关度排序**：原文精确 > 全拼精确 > 首字母精确 > 原文前缀 > 全拼前缀 > 首字母前缀 > 原文包含 > 全拼包含 > 首字母包含；同分再按业务时间、id 倒序。

### 索引同步

- 派生索引表 `t_rental_order_name_index`，与订单一一对应（`order_id` 即主键）；
- **下单**：订单与索引在同一业务事务内写入，失败一起回滚；
- **改名**：`PUT /api/orders/{id}/customer`（携带写请求三件套）在同一事务内更新订单姓名、重建该单索引、留 `RENAME` 事件，中途失败全部回滚；
- **全量重建/自愈**：`POST /api/orders/name-index/rebuild` 以订单为准逐单重算覆盖；应用启动后自动跑一次自愈（失败不阻断启动）。

### 灰度开关与十分钟回退

复用库表灰度规则 `t_gray_rule`，新增场景 `CUSTOMER_NAME_SEARCH`（全局开关，`branch_code` 固定 `*`）：

- 默认无规则即**关闭**，读路径行为与升级前完全一致（`customerName` 入参被忽略）；
- 上线：`POST /api/gray/rules {"scene":"CUSTOMER_NAME_SEARCH","branchCode":"*","status":"ON"}`，运行时生效、无需重启；
- **回退**：值守发现异常，置 `"status":"OFF"`（或删除该规则）即立刻退回旧检索链路，无需重启、无需重新部署，满足上线当天十分钟内回退；
- 上线前先对存量执行一次 `POST /api/orders/name-index/rebuild`（应用启动也会自愈一次），确认索引齐全后再开开关。

### 接口速览（新增）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /api/orders?customerName= | 姓名原文/全拼/首字母检索（灰度开启时生效），相关度排序，可与既有条件组合 |
| PUT | /api/orders/{id}/customer | 客户改名，同事务同步拼音索引并留痕 |
| POST | /api/orders/name-index/rebuild | 拼音索引全量重建（存量初始化/兜底自愈） |

### 测试

`mvn test`：新增 7 个集成用例覆盖全拼/首字母及片段检索、大小写与全半角/空格标点容错、中文与中英数混合直查、相关度排序、改名同事务同步与留痕、灰度 OFF 回退、删索引后全量重建恢复。

## 网点树（branch-1）

背景：网点调度反馈旧处理方式忙时漏单、区域层级调整频繁。本期交付闭包表网点树；调度单可靠化另起迭代。

### 已落地的四块

1. **网点节点** `t_branch`（branch_code 唯一；parent_id=0 为根，允许森林；全量留痕禁止物理删除）
2. **闭包表** `t_branch_closure`（祖先-子孙全量链路含 depth=0 自闭环；任意层级查询均为单索引等值扫描，性能与树深无关）
3. **变更留痕** `t_branch_event`（CREATE/MOVE/SORT，试点观察期审计依据）
4. **灰度规则** `t_gray_rule`（scene + branch_code 唯一，`'*'` 表示全量）

### 子树迁移（原子）

`POST /api/branches/{id}/move`：节点带着子孙整体搬到新父节点下（newParentId=0 迁为根）。一个事务内：

1. 校验：目标父节点存在；禁止挂到自身或自己的子孙下（防成环）；禁止原地搬家；
2. 删旧链路：断开子树与旧外部祖先的全部链路（子树内部链路保留）；
3. 接新链路：新父祖先链 × 子树，深度重算；
4. 改 parent_id（乐观锁兜底并发搬家）；
5. 写 MOVE 事件。

中途任何失败整体回滚，不留半截链路。

### 同级排序

`sort_order` 配置列，小的在前、同值按 id；`PUT /api/branches/{id}/sort` 调整并留痕。

### 灰度（先试点再推全）

调度查询读路径（children/subtree/path）过灰度闸：全量规则 ON 直接放行；否则节点落在任一生效试点网点的子树内（含试点自身，经闭包表判定）放行，其余 409。运维写路径（建点/迁移/排序）不过闸。

- 试点：`POST /api/gray/rules {"branchCode":"试点网点编码","status":"ON"}`
- 观察一周，审计 `t_branch_event`；
- 推全：`POST /api/gray/rules {"branchCode":"*","status":"ON"}`，运行时生效、无需重启。

### 接口速览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/branches | 新建网点（branchCode/name 必填，parentId 缺省 0=根，sortOrder 缺省 0） |
| POST | /api/branches/{id}/move | 子树整体迁移（原子；0=迁为根；防成环） |
| PUT | /api/branches/{id}/sort | 同级排序配置 |
| GET | /api/branches/{id} | 节点详情（运维诊断，不过灰度闸） |
| GET | /api/branches/{id}/children | 直接子节点，按 sort_order 排序（灰度闸） |
| GET | /api/branches/{id}/subtree?maxDepth= | 任意层级后代（灰度闸） |
| GET | /api/branches/{id}/path | 到根路径，根在前（灰度闸） |
| DELETE | /api/branches/{id} | 一律拒绝（禁止物理删除） |
| POST | /api/gray/rules | 灰度规则新增/开关（同 scene+branchCode 更新状态） |
| GET | /api/gray/rules | 灰度规则列表 |

### 测试

`mvn test`：新增 9 个集成用例覆盖闭包链路正确性、同级排序、子树迁移与旧链路清理、迁为根、防成环/原地搬家拦截、迁移中途失败整体回滚、重复提交拦截、灰度试点到推全、删除拒绝与编码唯一兜底、乐观锁并发拦截。

## 网点对账（recon-1）

背景：网点新换了负责人，第一件事把资金账理清楚。把对账做成周期任务：凌晨自动拉双方流水勾稽，金额或状态不一致的进差异清单，调度员手工核销必须填原因，日结后出对账单；白天不敢动的配置（匹配容差）落变更单排队，夜间批处理统一生效，次日早晨凭作业运行记录核对结果。

### 数据模型

| 表 | 说明 |
| --- | --- |
| `t_recon_txn_internal` | 内部流水（本方账面）：按网点+业务日从租车单 RETURN/REVERSE 事件抽取，ref_no 即订单号 |
| `t_recon_txn_external` | 外部流水（渠道账面）：对账文件导入，external_ref_no 对齐订单号，同键重复导入覆盖 |
| `t_recon_batch` | 对账批次：一网点一业务日一次勾稽一行（RUNNING/DONE/FAILED），可重跑 |
| `t_recon_diff` | 差异清单：金额超差/状态不一致/单边账；OPEN/RESOLVED，核销原因必填 |
| `t_recon_statement` | 对账单：日结后一网点一业务日一份，GENERATED 可刷新，CONFIRMED 冻结 |
| `t_recon_config` | 当前生效配置（单行）：匹配容差 `match_tolerance`（含边界，不能为负） |
| `t_recon_config_change` | 配置变更单：PENDING 白天排队，夜间 APPLIED / REJECTED |
| `t_recon_job_run` | 周期作业运行记录：CONFIG_APPLY / DAILY_RECON 每次触发留痕 |

租车单新增可空 `branch_code`（归属网点，下单可选传，既有流程不受影响），对账按网点抽取内部流水。

### 勾稽规则

- **配对键**：业务参考号 + 业务大类（结算 SETTLE / 冲销退款 REVERSE）；外部类型归一（REFUND/REVERSE 归冲销类，其余归结算类）。
- **金额**：`|内部-外部| <= 匹配容差` 视为一致（容差含边界，默认 0，即必须分毫不差）。
- **状态**：渠道状态原文按大类归一（结算认 SUCCESS/SETTLED/PAID，冲销认 SUCCESS/REFUNDED/REFUND/REVERSED），认不出即状态不一致。
- **单边账**：只有一方有账 → INTERNAL_ONLY / EXTERNAL_ONLY。

### 重跑口径（同网点同业务日可安全重跑）

- 本次仍存在的 OPEN 差异原地刷新（最新批次与金额快照）；
- 本次已消失且**未经人工核销**的差异自动消解（resolve_type=AUTO）；
- **人工核销(MANUAL)结果永不翻案**；
- 批次头独立事务先落 RUNNING，勾稽主体失败整体回滚后批次以独立事务置 FAILED，不留半截账。

### 核销即时清零

调度员 `POST /api/recon/diffs/{id}/resolve` 核销，`reason` 必填（入参校验 + 服务层二次兜底）；提交事务后该差异立即从 OPEN 清单消失；若对账单已生成（未确认），对账单口径同步刷新。

### 日结对账单

凌晨 03:00 作业对前一业务日全部活跃网点（当天有结算/冲销事件或导入过外部流水的网点）逐点勾稽并出对账单；单网点失败不影响其余网点，失败点在作业摘要列明，白天修复后可手工重跑。对账单未确认可随重跑刷新；次日早晨核对无误 `confirm` 后冻结，冻结日拒绝再勾稽重跑。

### 配置夜间批处理

白天调整容差只登记 PENDING 变更单，生效值不变；02:00 批处理逐条校验：合法则改写生效配置置 APPLIED，非法（如负数）置 REJECTED 留驳回原因，单条失败不影响其余。应急可手工 `POST /api/recon/jobs/config-apply` 立即执行。

周期时点可在 `application.yml` 调整（`evops.recon.config-apply-cron` / `daily-recon-cron`），`evops.recon.enabled=false` 为应急总开关（不影响手工触发接口）。

### 接口速览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/recon/external-txns/import | 导入渠道对账文件（外部流水），同键覆盖 |
| GET | /api/recon/internal-txns, /external-txns | 双方流水查询（网点+业务日） |
| POST | /api/recon/run | 手工触发对账（补跑/重跑） |
| GET | /api/recon/batches | 对账批次列表 |
| GET | /api/recon/diffs?status=OPEN | 差异清单（OPEN 待核销 / RESOLVED 已清零） |
| POST | /api/recon/diffs/{id}/resolve | 手工核销（**原因必填**），核销即时清零 |
| POST | /api/recon/statements | 日结生成对账单（未确认可刷新，已确认拒绝） |
| GET | /api/recon/statements, /statements/{id} | 对账单查询 |
| POST | /api/recon/statements/{id}/confirm | 早晨核对后确认冻结 |
| GET | /api/recon/config | 当前生效匹配容差 |
| POST | /api/recon/config-changes | 提交配置变更（白天只排队 PENDING） |
| GET | /api/recon/config-changes | 变更单列表（PENDING/APPLIED/REJECTED） |
| POST | /api/recon/jobs/config-apply | 手工触发夜间配置批处理（应急） |
| POST | /api/recon/jobs/daily-recon?bizDate= | 手工触发日结对账（应急） |
| GET | /api/recon/job-runs?jobType= | 周期作业运行记录（次日早晨核对） |

所有写请求同样遵循三件套（`X-Operator` / `X-Request-No` / 可选 `X-Biz-Time`）与入口幂等。

### 测试

`mvn test`：新增 8 个集成用例覆盖全匹配出账、金额超差进差异与核销原因必填/即时清零、渠道状态更正后重跑自动消解、双边单边账、容差变更白天排队夜间生效后重跑消解、人工核销不翻案与对账单确认冻结、日结作业自动覆盖活跃网点出对账单、文件内重复流水拦截。

## 车载定位平台文件往来（telematics-1）

背景：租车业务与车载定位平台交换文件——我方导出交/还车交接数据（平台据此启停定位跟踪），平台回传设备回执由我方导入。旧方式出错要很久才能发现，本期统一为规范 CSV 往来：列与编码按对方规范、特殊字符转义、空值与空串区分、导出按日期归档、导入解析失败带行号、重复导入幂等。改动不动交车主链路（导出只读交接数据）。

### CSV 规范（与对方对齐的口径）

- **字符集**：UTF-8（`evops.telematics.charset` 可配，对方若要求 GBK 改配置即可，启动即校验字符集可用）；
- **行尾** CRLF，首行表头，列序固定；
- **转义**：字段含 `,` `"` 回车 换行 时双引号包围，字段内 `"` 双写；
- **空值与空串区分**：null = 空字段（两分隔符间无内容），空串 = `""`；
- **时间格式**：`yyyy-MM-dd HH:mm:ss`。

### 导出（我方 → 平台）

`POST /api/telematics/exports?bizDate=`（缺省当天）：抽取该业务日交接记录（按交接业务时间）联车辆表生成 CSV：

- 列（定序）：`order_no, plate_no, handover_type, handover_time, fuel_reading, damage_photos, remark`；
- 文件落盘 `data/telematics/export/yyyyMMdd/handovers_yyyyMMdd_HHmmss_xxxx.csv`（按业务日分目录归档），`t_telematics_export_file` 留归档记录（业务日/文件名/路径/行数/字符集）；
- 一业务日可重复导出（补发），每次生成新文件并留痕；落库失败时尽力清理已写文件，不留无记录孤儿文件。

### 导入（平台 → 我方）

`POST /api/telematics/imports`（multipart 上传）：

- 列（定序）：`plate_no, device_no, device_status, report_time, remark`；`device_status` 仅认 `ONLINE/OFFLINE`；
- **全或无**：任一行非法（列数不符/必填缺失/状态非法/时间格式错/文件内重复键）整单置 FAILED，**错误明细逐条带行号**（结构错误如引号未闭合按记录起始行报），业务行零写入；导入记录留痕可查明细；
- **重复导入幂等（三层）**：① 入口 `X-Request-No` 幂等（既有拦截器）；② 文件内容 SHA-256 唯一——同一文件重复导入直接返回首次结果（`duplicate=true`），不重复写业务行；③ 业务键 `(plate_no, device_no)` 唯一——不同文件重复上报同一设备按最新内容覆盖；
- **模板可下载**：`GET /api/telematics/imports/template`（表头 + 示例行，与解析口径一致）。

### 接口速览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/telematics/exports?bizDate= | 导出指定业务日交接数据并归档（缺省当天，可重复导出补发） |
| GET | /api/telematics/exports?bizDate= | 导出归档记录列表 |
| GET | /api/telematics/exports/{id}/download | 下载归档的导出文件 |
| GET | /api/telematics/imports/template | 下载导入模板（表头+示例行） |
| POST | /api/telematics/imports | 上传设备回执文件导入（全或无，失败带行号，重复导入幂等） |
| GET | /api/telematics/imports, /imports/{id} | 导入记录列表/详情（含失败行号明细） |
| GET | /api/telematics/device-reports?plateNo= | 设备回执查询 |

写请求同样遵循三件套（`X-Operator` / `X-Request-No` / 可选 `X-Biz-Time`）与入口幂等。

### 测试

`mvn test`：新增 6 个集成用例覆盖导出转义与空值/空串区分、CRLF 与 UTF-8、按日期归档落盘、模板下载、导入成功与同文件重复导入幂等、同设备不同文件覆盖、失败行号明细与全或无（合法行也不落库）、表头不符与引号未闭合报错。回归聚焦交车主链路：既有 RentalFlowIntegrationTest 9 用例不动、全量测试通过。
