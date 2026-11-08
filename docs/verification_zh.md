# 验证记录

[English](verification.md) · [首页](../README_zh.md)

## 共享网络执行

本机使用 Java 17、Scala CONET 服务、一个 Union、七个成员、十四个数据集及完整合成输入完成验证。请求通过真实的 Scala DACP SDK 提交，并在 DACP/Union/XStream 服务中执行。

| 案例 | 已验证 Union 作业 | 结果 |
|---|---|---|
| 淤地坝 | `7023b606-3042-44ae-90d6-0a3c01ec3946` | 首个瓦片；3 个有效位置，4 个剔除位置 |
| 天文台 | `1dd3a51b-1e2d-4e37-8c7f-6069eb6e70cb` | 天区 `[150,2,0.15]`；3118 条三波段匹配 |
| 地震 | `85c54dbd-f7e6-4340-aedc-28a07d6637da` | 疑似影响 208.5 km²、14 个聚落 |
| 泥石流 | `6a97c6bb-f9f0-4f9e-ba4d-4c1405724c7c` | 疑似影响 75 km²、4 个聚落 |

影像缺失和水文过期情形返回不完整结果，未知指标保持为空。精确三角形选择得到 2 个淤地坝位置，对应矩形基线为 3 个。这些是确定性的合成数据检查，不代表真实准确度或性能结论。

## 自动化检查

```bash
sbt test stage
.venv/bin/python -m unittest discover -s tests -v
.venv/bin/python scripts/smoke_web.py --url http://127.0.0.1:8870
.venv/bin/python scripts/smoke_appbuilder.py --url http://127.0.0.1:8870
.venv/bin/python scripts/smoke_appbuilder.py --url http://127.0.0.1:8870
```

六项 Scala 测试覆盖多边形有效性与覆盖范围、数值校验、无别名变量展开、HTTP 路由、跨站拒绝、重启中断记录、数据集目录约束、数据量去重、链接资源打包及打包失败时的原子保护。二十四项 Python 科学计算测试覆盖流分块、栅格处理、多边形裁剪、天文交叉匹配，以及应急结果的来源和缺测语义。

真实测试提交三个案例并下载成果。执行 `bin/conet down` 后再 `bin/conet up`，验证自有进程停止及完整网络启动；`restart-web` 保留运行中的网络。浏览器检查覆盖门户、大屏、三个案例页面及物理计划查看器，未发现 JavaScript 异常。生成数据、模型、凭据和结果不纳入 Git。

## 算子仓库

七个源码提交已被本机算子仓库接收，均完成静态分析和隔离构建，全部仍为**待管理员审核**，尚不可公开下载。提交脚本未指定可信度或分类。

| 算子 | 提交 ID |
|---|---|
| `check_dam_detect@1.0` | `793e745c-3a3f-4f40-9616-d7a5b3f10af8` |
| `check_dam_map@1.0` | `203c790d-d857-4d01-8db7-466573500b7d` |
| `check_dam_overlap@1.0` | `06d87932-5f9a-4d2a-8183-506a3c972af0` |
| `check_dam_susceptibility@1.0` | `bd018150-7620-411e-8a8d-9750b3b96f26` |
| `check_dam_terrain@1.0` | `97ec93f8-880c-41b2-85d1-5e9efd195c05` |
| `observatory_crossmatch@1.0` | `865a9020-a9ac-4c14-814f-39158a4402ee` |
| `observatory_select@1.0` | `080ed50c-4256-49d5-bade-44afc3670612` |

已注册执行环境 `conet-geospatial@1.0` 和 `conet-astronomy@1.0`，前者在 `/opt/models/check-dam.pt` 中包含合成检测模型权重；源码包仅含代码。本地应用目前使用管理员安装的算子包。审批后再从公开仓库下载运行是独立验证项，目前未声称完成。

## 参考网站

已通过 HTTPS/HTTP、配置代理、直连和 Chromium 多次重试 `https://conet.4fair.cn/`。代理请求出现 TLS/502 错误，直连超时，未能查看参考页面。大屏采用用户提供的参考截图中的地球与侧栏构图，指标来自真实 Union 观测，节点示意位置有明确标注。

## 多边形选区验证

使用部署所用的 Python 环境运行 `python scripts/smoke_appbuilder.py --url http://127.0.0.1:8870`。脚本验证拒绝自相交请求，通过共享 Union 对首个瓦片分别执行矩形和三角形分析，并逐点验证结果位于各自 Polygon 内。矩形得到 **3** 个有效点，三角形得到 **2** 个。十九项契约测试通过，包含外接矩形误包含、边界点保留、投影后的孔洞保留和空间叠加的包含检查。浏览器验证覆盖多边形绘制、完成及顶点编辑。

五个 `1.1` 版本淤地坝算子已以仅源码形式提交，仍待管理员审批。提交标识分别为 `1b44925a-24a0-4802-a235-e25fd36033b2`（detect）、`bd86c3d9-53b5-489b-bcb1-40dcd0338132`（map）、`68c0ba4b-574c-4d8e-8830-7cb781097e8d`（overlap）、`835040fb-8da6-4ff3-bc8b-10b427906294`（susceptibility）和 `71cea7dc-c7fc-4805-9c34-d0f076de97da`（terrain）。本地执行使用已安装的算子包，未声称完成公开下载执行验证。

## 七中心网络

七个配置成员均观测到已获准，发布十个数据集，各中心文件数据量均非零。扩展后重新执行两个真实案例：淤地坝得到 3 个有效候选点，天文得到 3118 条匹配。浏览器在 1920×1080、1440×900、1366×768 下验证七个节点条目、七个在线标记、标注矩形无重叠以及页面无溢出。十九项契约测试通过。六个科学输入分布在三个学科数据中心及三个地域天文台，另一个中心发布空间参考数据。

## App Builder 验证

```bash
sbt -batch 'appbuilder/test' 'hosting/test' test stage
python3 scripts/smoke_web.py
python3 scripts/smoke_appbuilder.py
python3 scripts/smoke_appbuilder.py
python3 scripts/smoke_appbuilder_browser.py --chromium /path/to/chromium
```

应急检查的 Python 环境需要 `shapely`；浏览器检查需要 `playwright` 和 Chromium。App Builder 测试覆盖类型化变量绑定、输入限制、安全成果路径、XML 实体拒绝、CSV 导出、取消、队列限制、历史恢复、感知执行重试的事件去重，以及带修订保护的发布。浏览器检查覆盖生成表单、初始结果为空、历史选择、成果视图和编辑器预览。天地图组件在独立 frame 中运行，其事件处理不会干扰应用控件。

真实共享 Union 执行得到 3 个有效淤地坝候选点和 3118 个天文匹配源。应急检查通过地震、泥石流分析以及影像缺失和水文过期情形。仅通过天文配置发布的第四个应用成功得到 3118 个匹配源，成果及四类使用计数均经过验证；重启 Web 服务后再次检查了已发布定义及统计的持久性。

门户卡片来自已发布应用注册表，包含各应用的描述与封面图。应用可视化编辑与 YAML 同步；Job 在独立窗口的 XStream Job Designer 中编辑。`scripts/smoke_appbuilder_visual.py --chromium /path/to/chromium` 验证显式编辑、复制、Designer 回传、YAML 往返、动态应用服务列表一致性及桌面/手机布局。发布后的访客页不展示工作台导航或 YAML 源码。


## App Builder 编辑验证

子模块和门户共通过 21 项独立 Scala 测试，包括使用模拟 Responses 服务检查服务端凭据、多轮问答后的提议生成、配置校验、数据绑定保护及不会隐式发布。浏览器编辑测试覆盖显式新建/编辑/复制、XStream Designer 导入回传、YAML 往返和独立应用的桌面/手机页面。可选的 `--chat` 使用部署配置中的真实模型连接完成三次请求的访谈，并将提议应用到草稿，不发布应用。

公开页面测试替换外部地图 iframe，独立验证表单、历史和预览控件，不代表实时地图连接验证。此前完成的科学案例执行验证单独保留；编辑测试不重新提交分析任务。


`scripts/smoke_appbuilder_configuration.py` 验证紧凑字段与分组编辑、从 Job 值提取变量、缺失绑定、来源选择、结果配置及 YAML 往返，不发布应用。

## 共享存储与产品发布



新天文分析返回 **3,118 条匹配**。检查了未发布归档、独立分享页、公开元数据及说明报告和获准 CSV。通过 DACP bytes 视图读取 **729,598 字节**，SHA-256 与成果记录一致；records 视图返回 3,118 行。真实提交给 Union 的 `read_item@1.0` Job 读取同一托管结果并返回 3,118 行，Job 为 `74a92337-dba9-4b8b-a8b5-cb260d1c2eee`。撤回另一份测试发布后，其 Catalog 条目消失，分享 API 返回 HTTP 410。测试读取账号仅授予 `reader`，验证后停用。这些结果是合成演示数据，不代表科学有效性验证。

```bash
python3 scripts/smoke_workspaces.py --url http://127.0.0.1:8870
python3 scripts/smoke_products.py --output /tmp/product-run.json
python3 scripts/smoke_products_browser.py --run /tmp/product-run.json --output /tmp/product-share.json
python3 scripts/smoke_hosted_products.py --product /tmp/product-share.json --root /path/to/conet --dacp-home /path/to/dacp-codex
```


## 免登录工作空间检查

Scala 测试覆盖匿名应用开发与存储、旧认证接口移除、单用户共享存储、来源校验、目录穿越及符号链接防护、配额、不可变发布快照和只读结果归档。运行 `scripts/smoke_workspaces.py` 可免凭据进行本地 HTTP 与浏览器检查。

## 应用程序包与文件输入

`sbt test` 覆盖程序包往返、不安全或过大的 ZIP 条目、文件引用校验、独立运行的不可变输入、跨应用引用拒绝、大小及扩展名限制、过期清理和非公开输入 Catalog 访问。表格插件测试覆盖 CSV 引号、多行字段、缺失值及表头冲突。`python3 scripts/smoke_uploaded_csv.py` 导入加载 CSV 程序包，通过浏览器上传文件并提交真实 Union Job，验证表格、统计、全部下载、程序包导出及桌面/手机布局。示例输出三行五列。Catalog 实现改变时须重新部署共享托管插件，原有七个数据节点与 Union 可继续运行。
