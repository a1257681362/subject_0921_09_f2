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

## F2：素材流水与多规格转档（f2-asset-transcode-1）

美工传一张原图，横竖两版常规规格自动出，不用再手工导七八个版本。

业务口径：

- **素材档案按文件指纹认人**：原图算 SHA-256 指纹，`fingerprint_sha256` 唯一；同一张图重复上传（哪怕改了文件名）只累加 `upload_count`，**不另开档、不重跑转档**。
- **横竖两版常规规格自动出**：横版 `16:9`、竖版 `9:16`，均居中裁切不变形，**长边压进限定像素**（默认 1280px，出标准件 1280×720 / 720×1280）。
- **体积超标再压一轮质量**：产物先按首轮质量（0.85）出 JPEG，超过体积上限（默认 300KB）就每轮降一档质量（步长 0.15，下限 0.50），取体积最小一轮；压到下限仍超标也交差，任务不算失败。规格/长边/上限/质量档均在 `application.yml` 的 `asset.*` 配置。
- **结果记进素材档案、标清用途**：每个产物一行，`usage` 记规格标识（信息流横版 `feed_landscape` / 信息流竖版 `feed_portrait`），并记尺寸、长边、体积、质量轮次、最终质量。
- **多规格转档版本**：上传首轮记 v1（来源 UPLOAD）；手动再跑不抹历史，另起一版（来源 RETRY），各版本产物独立可翻。
- **多规格转档状态**：任务 `PENDING 待处理 / PROCESSING 转档中 / SUCCESS 成功 / FAILED 失败`，状态同时挂在版本和任务上；**失败原因落库**（如"图片格式不支持或文件已损坏"），前端轮询即可见，美工不干等；失败可一键重新转档。
- 上传接口立即返回任务号，转档在后台线程池异步执行（事务提交后触发，避免后台读不到未提交任务）。
- 原图与产物落盘 `asset.storage-root`（默认 `./data/assets`，原图按指纹存、产物按 素材/版本/规格 存），库里存相对路径。

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
| 原图上传（指纹去重，自动转档） | `POST /api/assets/upload`（multipart：file、operatorName、remark） |
| 素材翻查 / 详情 / 档案全量 | `GET /api/assets?keyword=&status=`、`/api/assets/{id}`、`/api/assets/{id}/detail` |
| 当前转档规格 | `GET /api/assets/specs` |
| 转档状态轮询 / 重新转档 | `GET /api/assets/jobs/{jobId}`、`POST /api/assets/{id}/retry` |
| 产物下载 / 原图下载 | `GET /api/assets/files/{fileId}/download`、`/api/assets/{id}/original` |

演示界面：启动后浏览器打开根路径 `/`（周例会演示用，六个页签覆盖全流程）。

## 启动

1. 执行 `mvn -q -DskipTests compile` 验证骨架构建。
2. 执行 `mvn spring-boot:run` 启动应用；Spring Boot 会自动执行 `src/main/resources/schema.sql`。
3. H2 数据文件默认写入工作区 `data/evops`。如需修改路径，编辑 `application.yml` 中的 `jdbc:h2:file:` 连接串。
4. 验证：`mvn test`（11 项底账口径测试 + 7 项素材转档测试，独立内存 H2）。

表结构交付副本：`docs/schema/evops.sql`（容器内根目录 `../../docs/schema` 不可写，交付副本置于工作区内），应与 `src/main/resources/schema.sql` 保持一致。
