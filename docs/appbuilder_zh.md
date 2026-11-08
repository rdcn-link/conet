# 配置式应用开发

表格预览最多显示 10 行数据，并标明总行数与当前预览行数。完整结果下载保留全部数据行。

已发布应用统一列在主导航的“应用服务”页面（`/services/`），首页展示协作网络与数据中心。

[English](appbuilder.md) · [首页](../README_zh.md)

CONET App Builder 使用应用定义和固定的 XStream Job 发布分析应用。发布后的应用表单可以匿名浏览；开发、执行和资源管理均无需登录。Union 凭据与网络端点由 CONET 部署配置提供，不需要在浏览器中输入。

## 发布应用

1. 从门户的“应用工作台”进入 `/appbuilder/`。
2. 加载三个已发布应用之一作为模板。
3. 点击“新建应用”创建草稿，或选择现有应用后点击“编辑”。“复制应用”创建独立草稿，不影响原应用。通过“应用信息”“输入表单”“变量绑定”“结果与下载”“运行设置”标签页配置应用；通过“流程设计”打开 XStream Job Designer；“YAML 源码”同步显示配置。
4. 点击“校验配置”检查两份定义，点击“预览表单”查看生成的输入界面；预览不提交任务。
5. 点击“发布应用”。返回的 `/apps/<id>/` 链接立即生效，无需重启服务器；可以直接分享给需要运行分析的访客。

发布页是面向访客的独立应用界面，展示应用标识、参数表单、分析进度、结果视图、下载和折叠的历史记录，不包含工作台入口、YAML 编辑器或开发看板。布局适配桌面与手机，“分享应用”提供当前应用地址。

门户首页读取同一份已发布应用注册表。每张应用卡片采用配置中的名称、描述、封面和标签，直接指向 `/apps/<id>/`。发布和更新应用即可同步目录，无需编辑首页 HTML。

已发布定义在服务重启后保留。修改应用前先加载最新定义，版本校验可避免覆盖其他编辑者的更改。有排队或执行中任务的应用不能被替换。应用 ID 决定稳定的访问地址；更改 ID 会创建另一个应用。

```mermaid
flowchart LR
  C[应用 YAML] --> V[校验并发布]
  J[固定 Job YAML] --> V
  V --> P[独立应用地址]
  P --> F[生成输入表单]
  F --> B[类型化变量绑定]
  B --> U[Union 提交]
  U --> S[状态与取消]
  U --> R[成果解码]
  R --> D[配置视图与下载]
```

## 可视化编辑

“应用信息”“输入表单”“变量绑定”“结果与下载”“运行设置”与“流程设计”并列显示，不再嵌套一层导航。控件类型和结果展示形式通过带图标的卡片选择，并提供样式示意。推荐顺序为：设计 Job → 配置输入表单 → 绑定变量 → 设置结果。字段和结果组件使用带图标的条目列表，选择后在单层面板中编辑，旁边提供样式示意；数值与开关使用各自的控件，不需要逐项指定数据类型。字段分组在同一面板内通过路径导航切换，不叠加配置框。结果分为“展示组件”“下载文件”和“汇总指标”；选项及指标按行编辑，数据列用逗号或换行分隔。“更多设置”收纳元数据来源和投影等专业配置；源码中的其他属性会保留。

变量列表从 Job 的完整标量引用（例如 `$MIN_SNR`）自动提取，不从注释或普通描述文字提取。变量名只读；选择表单字段、固定值、数据集 Item、上下文元数据或选区边界框作为来源。表单字段和已配置的数据集直接下拉选择。未绑定项有状态提示，Job 修改后列表同步更新。未被当前 Job 使用的旧绑定单独列出，可主动清理。允许任意编辑顺序，发布前会检查缺少的绑定及不存在的表单字段。

“流程设计”在独立窗口打开现有的 XStream Job Designer，导入当前 Job。完成后点击“应用此 Job 并返回”，将流程带回当前草稿。应用的输入表单、变量绑定与结果展示保留。切换到另一应用后，旧窗口的回传不会覆盖新草稿。浏览器须允许弹出窗口；也可下载 Job YAML 后手动导入。

部署管理员在 `conet.yaml` 配置：

```yaml
appbuilder:
  designer_url: http://10.0.82.103:8840/
  chat:
    codex_config: /home/cnic/.codex/config.toml
```

Designer 通过新窗口通信，校验来源、窗口和随机通道，不需要改变其禁止 iframe 嵌入的设置。

切换到“YAML 源码”会序列化当前可视化模型；切换回来会解析源码并重建模型，保留其他配置字段。可视化编辑不提交 Job，预览、校验和发布使用当前模型。

### 应用展示图片

基本信息支持填写图片 URL 或上传 PNG/JPEG 图片，最大 2 MiB、1600 万像素。上传图片保存在 `.state/appbuilder/media/`，使用基于内容摘要的地址。`presentation.cover` 接受本地资源或 HTTPS 图片地址；省略时使用公共默认图片。内置案例各有自己的示意图。`presentation.tags` 最多 8 项，每项最多 32 个字符。

```yaml
presentation:
  cover: /assets/observatory-cover.svg
  tags: [Multi-band catalogs, Color analysis]
  notice: Synthetic data demonstration.
```

## 定义文件

每个内置案例包含 `application.yaml` 和 `job.yaml`。Scala 子模块 `conet-appbuilder` 负责校验、变量绑定、Union 执行、排队、历史、取消、成果与使用统计。三个现有案例保留专业地图和天区图，复用与生成应用相同的浏览器运行库和后端。

完整示例：

- [淤地坝](../cases/check-dams/application.yaml)：多边形与置信度输入，SVG metadata 解码，地图、表格与下载。
- [天文台](../cases/observatory/application.yaml)：天区圆锥、匹配半径和信噪比输入，JSON 解码，天区与颜色散点图及 CSV。
- [应急](../cases/emergency/application.yaml)：多边形、嵌套事件字段和可用性选择，来源状态、空间结果与影响要素表。

可复制天文台定义，修改 `id` 和 `title`，保留 `data.scope: observatory`。`data.scope` 选择管理员配置的数据上下文及其 manifest；目前可选 `check-dams`、`observatory`、`emergency`。新增数据上下文需要在 CONET 配置其数据和 manifest；基于已有上下文发布新分析无需修改服务器代码。

### 输入

`inputs` 是有序列表，每项包含 `name`、`label`、`widget`，以及可选 `required`（默认 `true`）。未声明的输入字段会被拒绝。

| 控件 | 值 | 配置 |
|---|---|---|
| `number` | 有限 JSON 数字 | `min`、`max`、`default` |
| `select` | 字符串 | `options: [{value: value, label: Label}]` |
| `text` | 字符串 | `max_length`，默认 4096 |
| `datetime` | 带时区的 ISO 8601 字符串 | 浏览器表单使用 UTC |
| `polygon` | WGS84 GeoJSON Polygon 几何 | `coverage_manifest`、`initial_manifest` |
| `cone` | `[ra, dec, radius]`，单位度 | `min_radius`、`max_radius` |
| `object` | 嵌套输入对象 | `fields`；可选 `point_fields`、`coverage_manifest` |

`default` 提供服务端字面默认值。`default_manifest` 通过 JSON Pointer 从数据 manifest 取得默认值。`initial_manifest` 初始化浏览器表单，不会把必填项变成可选项。多边形输入支持天地图、逐点绘制及 GeoJSON 编辑；地图加载失败时仍能编辑坐标。多边形必须闭合、有效、非空且与配置覆盖区相交，最多 256 个顶点和 16 个环。圆锥坐标与数值范围均经过服务端校验。

### 变量绑定

每个变量必须恰好声明一个来源。名称遵循 Job 变量语法 `[A-Za-z_][A-Za-z0-9_]*`：

```yaml
variables:
  CONE: {input: cone}
  EVENT: {input: event}
  EVENT_TIME: {input: event.time}
  REGION_BBOX: {bbox: region}
  OBSERVATION_TIME: {manifest: /event_time}
  CATALOG_URI: {dataset: g-band, item: catalog.bundle}
  MODE: {value: analysis}
```

只保留 Job 需要的绑定。输入对象和数组保留 JSON 类型。`dataset` 根据 Union 目录中的上下文元数据查找数据集，再追加经过校验的相对 Item 路径。客户端不能选择凭据、文件系统输出路径或提交端点。Job 使用 `read_item@1.0` 等具名仓库算子，只提供一个输出流。

### 成果

`result.decoder` 可选 `json` 或 `svg-metadata`。后者读取 SVG 中唯一的 `<metadata>` JSON 对象，并禁用外部实体。`result.views` 支持 `metrics`、`table`、`scatter`、`geojson`。视图 `path` 和指标路径使用 JSON Pointer。表格可通过 `project` 投影嵌套对象、通过 `exclude` 排除匹配行，并声明 `columns`；最多预览 200 行。散点图配置 `x`、`y` 和可选 `reverse_x`。GeoJSON 地图支持点、线、多边形及其 Multi 几何。

`result.artifacts` 通过 `id`、`label`、`file` 和 `format`（`raw`、`json`、`csv`）声明下载。JSON 成果选择 `path`；CSV 成果选择行数组、列及可选投影和过滤。每次成功分析还生成 `analysis.json` 与 `variables.yaml`。仅成功任务的已声明成果可下载，以附件方式返回。`result.summary` 将汇总名称映射到 `{path: /field}` 或 `{path: /rows, operation: count}`。

`limits.max_queued` 限制每个应用已受理的排队/运行任务数，默认 4，最大 100。`limits.max_result_bytes` 限制结果流，默认 8 MiB，最大 64 MiB。每个应用串行处理分析。失败和取消会清除不完整成果。重启时未完成任务标记为 interrupted；再次提交前请在 Union 看板核对原任务。

## 对话式应用配置

编辑草稿时，点击“对话生成配置”。助手主动分轮提问，用户回答用途、用户输入和成果展示等需求；至少两轮回答后生成完整应用 YAML。现有表单、数据与结果字段会作为上下文。提议通过服务端校验后展示；点击“应用到草稿”才更新编辑区，发布仍需单独操作。

助手仅调整应用配置，不编写 Job，也不改变数据上下文或现有变量绑定；可以为尚未绑定的 Job 变量新增输入及绑定。先使用 Job Designer 确定流程，再通过表单编辑变量绑定。空 Job 可先访谈梳理需求，但不能生成可发布提议。重新开始会清空当前访谈；关闭窗口保留本轮对话；草稿变化后再次打开会开始新访谈。对话在浏览器内存中保留，刷新后清空。

`codex_config` 指向服务端可读的 Codex TOML，使用其中选定的 `model`、`model_provider`、提供方 `base_url` 和 `wire_api: responses`。凭据从提供方的 `experimental_bearer_token` 或 `env_key` 指定的服务端环境变量读取。浏览器仅收到助手是否可用，不接收连接地址、账号或密钥；不要将包含密钥的配置提交到仓库。模型服务接收应用草稿、Job 定义及当前对话，不接收执行数据、Token 或 CA 文件。单次请求最长 120 秒，同时最多 2 个请求，每轮访谈最多 12 次回答。失败时保留草稿，允许重试。

## 使用统计

工作台展示持久化计数，也可通过 `GET /api/appbuilder/statistics` 查询：

| 计数 | 规则 |
|---|---|
| 应用提交次数 | 每次受理提交计一次，包括随后失败或取消的任务；校验失败、队列满而被拒绝的请求不计。 |
| 数据集使用次数 | 对应 `read_item` 步骤被观察到运行或终态时，同一应用任务内每个数据集 URI 计一次。 |
| 算子调用次数 | 每个被观察到执行的步骤及其执行尝试计一次；两个步骤使用相同算子计两次。 |
| 节点接收任务次数 | 按 Union 执行记录中确认的节点身份与执行 ID 计一次；仅规划了分配不计。 |

统计范围为通过 CONET 应用提交的任务，不包含所有直接提交给 Union 的任务。事件去重后保存在任务记录中，轮询和服务重启不会增加计数。缺少执行观察数据的旧记录只计应用提交次数。节点观察使用配置中的 Union 看板；观察不可用时不会猜测接收次数。保留任务记录才能保留统计。

## 存储与 API

- `.state/appbuilder/definitions/<id>/`：已发布应用及不可变 Job 定义文件。
- `.state/appbuilder/runs/<id>/`：新发布应用的历史与成果。
- `.state/cases/<id>/`：内置应用的历史、数据与成果。

| 方法与路由 | 用途 |
|---|---|
| `GET /api/appbuilder/settings` | Designer 地址与对话助手可用状态，不含凭据 |
| `POST /api/appbuilder/chat` | `{application_yaml, job_yaml, messages}` 多轮访谈，消息仅含 user/assistant role 与 content，最大请求 512 KiB |
| `GET /api/appbuilder/apps` | 已发布应用及其地址 |
| `GET /api/appbuilder/apps/<id>` | 应用定义、Job、修订标识与地址 |
| `POST /api/appbuilder/validate` | 校验 `{application_yaml, job_yaml}`，可选 `inputs` |
| `POST /api/appbuilder/apps` | 发布同样的请求；更新时附上当前 `revision` |
| `GET /api/appbuilder/statistics` | 使用计数 |
| `POST /api/appbuilder/convert` | 将 `{application, job}` 对象或 YAML 字符串转换为类型化 JSON 和 YAML，不发布 |
| `POST /api/appbuilder/media` | 上传 `{content_base64}` PNG/JPEG 封面 |
| `GET /apps/<id>/api/config` | 表单定义、Job 及公开数据 manifest |
| `POST /apps/<id>/api/preview` | 校验输入并查看输入变量值，不提交任务 |
| `POST /apps/<id>/api/jobs` | 提交分析输入对象 |
| `GET /apps/<id>/api/jobs[/<run-id>]` | 历史或任务状态/结果 |
| `POST /apps/<id>/api/jobs/<run-id>/cancel` | 取消排队/执行中的分析 |

开发配置请求最多 256 KiB；图片上传端点接受最多 3,000,000 字节请求，用于上传 2 MiB 图片；分析输入最多 16 KiB。跨来源修改请求会被拒绝。本阶段不设置身份认证，能访问工作台的人均可发布应用、提交和取消分析；根据部署需要限制网络暴露范围。

## 扩展成果解码

在独立 Scala/JVM 插件中实现 `conet.appbuilder.ResultDecoder`，提供唯一 `id` 与 `decode(bytes): JsonNode`。在 `META-INF/services/conet.appbuilder.ResultDecoder` 登记实现类全名，将 JAR 和依赖加入 CONET 运行时 classpath，并重启服务。配置 `result.decoder` 为插件 ID。内置和外部解码器采用相同的 ServiceLoader 契约，无需修改集中注册表。契约测试应覆盖无效载荷、受限输出及供配置视图使用的 JSON 文档。

嵌入页面的自定义浏览器成果组件可使用 `ConetAppBuilder.registerView(type, renderer)`。托管编辑器目前校验四种内置视图类型。数据生成和领域算法独立于应用构建器。

## 构建与验证

```bash
sbt -batch 'appbuilder/test' 'hosting/test' test stage
bin/conet restart-web
python3 scripts/smoke_web.py
python3 scripts/smoke_appbuilder.py
python3 scripts/smoke_appbuilder.py
python3 scripts/smoke_appbuilder_browser.py --chromium /path/to/chromium
```

重启命令仅更新门户及应用 HTTP 服务，保留运行中的数据节点和 Union。


## 分析产品

参阅[发布和分享分析产品](products_zh.md)，了解结果自动说明、多方署名与许可、独立分享页、存储、到期和撤回。

[工作空间与共享托管](workspaces_zh.md)

## 应用交付与文件输入

先设计流程，再配置表单、变量绑定和结果视图。应用以可导入导出的[应用程序包](application-packages_zh.md)交付。[文件输入](file-inputs_zh.md)将受限浏览器上传绑定到独立运行的 DACP Item。`examples/uploaded-csv/` 提供 CSV 预览、字段统计和下载。CONET 包含八个 Node 成员：七个科学数据中心与一个共享托管中心，由单独的 Union 协调。

## 应用界面主题

在“应用信息”中通过五张页面缩略预览卡片选择界面主题，或在 YAML 中设置 `presentation.theme`。可选 `coast`（海湾青）、`forest`（山野绿）、`indigo`（星空靛）、`amber`（暖阳金）和 `rose`（珊瑚红），默认 `coast`。主题统一调整页面背景、标题、标记、按钮和下载入口，随应用程序包保存。淤地坝、天文和应急示例分别使用山野绿、星空靛和暖阳金。所有发布应用统一使用 `/apps/<id>/`；旧案例专用页面和独立 HTTP 监听入口不提供服务。

## 发布页面展示

应用页面用配置的封面和标签展示应用场景，随后显示输入、执行状态和结果。在较大屏幕中，多边形选区和参数并列展示。主题作用于标题、表单强调色、状态区域和下载入口。提交分析或显式打开已有运行之前，结果区保持空白。页面可独立分享，不包含应用设计导航。

## 背景图与外观推荐

在“应用信息”中编辑名称、描述和标签，即可查看推荐的主题、背景图及缩略图。推荐通过主题关键词和输入能力匹配内置视觉方案，不会将描述发送到模型，也不生成照片。“应用整套推荐”仅修改草稿；预览并调整各图片后，再发布应用。

背景图库提供“跟随主题”“无背景”和五张内置背景。也可上传 PNG/JPEG 图片（每张不超过 2 MiB），或填写 HTTPS 图片地址。缩略图用于应用卡片及介绍区，背景图用于发布页面，可读的内容区域显示在其上。

```yaml
presentation:
  theme: indigo
  background: /assets/background-indigo.svg
  cover: /assets/observatory-cover.svg
  tags: [Multiband catalogs, Color analysis]
```

省略 `background` 时跟随主题，设置 `background: null` 时使用纯色背景。导出应用程序包时，会嵌入上传的背景图和缩略图；HTTPS 图片保留外部地址，须能公开访问。

发布页面根据输入类型布局：地图保留宽选区，文件类应用的参数与结果并列。ICRS 选区提供坐标示意及缩放；结果表格提供限量滚动预览；散点图提供坐标轴、缩放/复位和悬停读数。地理底图连接失败时，地图明确显示经纬度选区视图，不将坐标示意冒充地理影像。

“开始分析”在请求提交、排队及执行期间保持禁用，完成、失败、超时或取消后恢复。状态查询暂时失败时，按钮仍保持禁用，客户端继续重试查询。
