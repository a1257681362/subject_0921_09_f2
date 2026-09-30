# EVOPS 基础工作区

这是 springboot 的骨架项目。

技术栈：Java 8、Spring Boot 2.7、MyBatis-Plus、H2 本地文件数据库、Shiro、Thymeleaf。

## F1：户外广告点位与上刊底账（f1-ledger-1）

围绕"点位档案 → 按周档期 → 上刊单"立底账，本版跑通挂牌、上刊、落架、翻查；结算不在本轮范围。

业务口径：

- **点位档案**：编号、尺寸、朝向、商圈缺一不可；点位编号唯一，重复存不进去。
- **档期按周排**：ISO 周（周一至周日），同一点位同一周只能挂一张画面。
- **上刊单**：画面挂在点位上，状态 `HUNG 挂起 / TAKEN_DOWN 落架 / OFFSET 已对冲`；单号 `PO+序列` 全局唯一。
- **改错不抹记录**：原单置"已对冲"并释放档期，另补一张对冲单挂同一根单（原单、对冲单可按根链翻查）。
- **随单留底**：每步动作写事件留底——经手人、发生日、事件时刻、单据快照；支持挂起中单据的版本选择与回退（回退也留一条事件，关闭单拒绝回退）。
- **关闭即冻结**：落架/对冲后的单据不能静默修改、重复落架或回退。
- **跨月归属**：归属期间 `biz_period` 按发生日落库固化，不随查询日漂移。
- **外部回执**：挂回原单据；回执指向对冲单时自动回溯到根单。
- **月度档期周历**：`/api/ledger/calendar?period=yyyy-MM` 一眼看哪块屏哪周空着。

账号：演示账号 `demo / demo`，骨架账号 `bootstrap / bootstrap`。

## F2：素材流水与多规格转档（f2-asset-1）

把美工“一张图导七八个版本”的活自动化掉，原图传上来即可：

- **素材档案按指纹认档**：上传先算 SHA-256，同一张图重复上传（哪怕改了文件名）认回原档案，**不另开档**。
- **横竖两版常规规格自动出**：横版主投放（长边 1920px、≤300KB）、竖版主投放（长边 1080px、≤200KB）；长边等比压进限定像素，小图不放大。
- **体积超标再压一轮质量**：JPEG 质量按 0.85 → 0.70 → 0.55 → 0.40 逐轮下压，第一份达标即用；压到最低仍超标，该版本记失败。
- **不硬裁硬拉**：原图朝向不符的规格记 `SKIPPED 不适用` 并写明原因（横图不出竖版、竖图不出横版）。
- **产出记进素材档案、标清用途**：每个版本一行，带用途、规格、产出尺寸/体积、质量轮次、状态，成品与原图均可下载。
- **后台异步转档，不干等**：上传立即返回；美工在列表/详情看 `CONVERTING 转档中 / READY 全部就绪 / PARTIAL 部分失败 / FAILED 全部失败`，转档中页面自动刷新。
- **失败原因看得见**：逐版本失败原因（含各轮体积）直接显示，未产出不让下载并提示原因；`PARTIAL/FAILED` 可一键**失败重跑**（只重转失败版本）。
- 文件仓默认在工作区 `data/assets`（`evops.asset.storage-dir` 可调），上传上限 20MB。

## 接口一览（均在 /api 下，除 health/login 外需登录）

| 动作 | 接口 |
|---|---|
| 登录/登出/当前人 | `POST /api/login`、`POST /api/logout`、`GET /api/me` |
| 点位挂牌/翻查/详情 | `POST /api/sites`、`GET /api/sites`、`GET /api/sites/{id}` |
| 上刊 | `POST /api/orders` |
| 落架 / 对冲 / 留痕更正 / 版本回退 | `POST /api/orders/{orderNo}/take-down`、`/offset`、`/edit`、`/rollback` |
| 单据翻查/详情/事件留底/根单链 | `GET /api/orders`、`/api/orders/{orderNo}`、`/events`、`/chain` |
| 外部回执 | `POST /api/receipts`、`GET /api/receipts/order/{orderNo}` |
| 月度档期周历/点位档期流水/底账版本 | `GET /api/ledger/calendar`、`/api/ledger/sites/{siteNo}/timeline`、`/api/ledger/versions` |
| 素材上传（multipart 字段 file） | `POST /api/assets/upload` |
| 素材翻查/详情（含转档版本） | `GET /api/assets`、`GET /api/assets/{assetNo}` |
| 失败重跑 / 下载原图或成品 | `POST /api/assets/{assetNo}/retry`、`GET /api/assets/{assetNo}/download?usage=ORIGINAL\|FEED_HORIZONTAL\|FEED_VERTICAL` |

演示界面：启动后浏览器打开根路径 `/`（周例会演示用，七个页签覆盖底账全流程与素材多规格转档）。

## 启动

1. 执行 `mvn -q -DskipTests compile` 验证骨架构建。
2. 执行 `mvn spring-boot:run` 启动应用；Spring Boot 会自动执行 `src/main/resources/schema.sql`。
3. H2 数据文件默认写入工作区 `data/evops`。如需修改路径，编辑 `application.yml` 中的 `jdbc:h2:file:` 连接串。
4. 验证：`mvn test`（11 项底账口径测试 + 9 项素材转档口径测试，独立内存 H2）。

表结构交付副本：`docs/schema/evops.sql`（容器内根目录 `../../docs/schema` 不可写，交付副本置于工作区内），应与 `src/main/resources/schema.sql` 保持一致。
