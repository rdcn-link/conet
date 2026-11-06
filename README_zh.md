# CONET

[English](README.md)

CONET 通过 DACP Union 连接数据中心，提供中文科学案例界面和实时运行大屏。三个本地 DACP 节点发布六个数据集。两个案例向**同一个 Union** 提交固定的变量化作业，由 Union 选择执行位置，XStream 执行各步骤。

- **淤地坝案例：**框选区域，识别合成影像中的坝体，结合易发山洪沟道筛选有效候选，下载分布图和 GeoJSON。
- **天文案例：**选择天区锥形区域，匹配合成 g/r/i 波段 FITS 星表，查看颜色和误差，下载匹配星表。
- **运行大屏：**展示成员健康状态、上报的 CPU/堆内存、任务参与次数、已观测的节点间任务载荷、案例活动和成果数量。不可用的指标明确显示为未知。

所有科学数据、天文台和坝体目标均为合成示例，不用于灾害预测、工程建议或天体物理分类。

```mermaid
flowchart LR
    Visitor[中文门户] --> Dam[淤地坝案例界面]
    Visitor --> Astro[天文案例界面]
    Dam --> U[DACP Union]
    Astro --> U
    U <--> A[西北科学数据中心：地形与 g 星表]
    U <--> B[华北科学数据中心：影像与 r 星表]
    U <--> C[西南科学数据中心：沟道栅格与 i 星表]
    U --> Screen[实时运行大屏]
```

## 快速入口

1. 按[部署指南](docs/deployment_zh.md)安装依赖、生成数据、训练合成模型并构建运行环境。
2. 启动共享网络和应用：

```bash
export DACP_HOME=/path/to/dacp-codex
.venv/bin/python manage.py up
.venv/bin/python manage.py status
```

3. 打开 `http://<machine-ip>:8870/`，大屏位于 `/screen`。脚本会输出本机地址。两个案例入口为 `/cases/check-dams/` 和 `/cases/observatory/`。案例后端只在回环地址的 8871/8872 端口监听，由门户提供同源访问。
4. 选择区域并提交分析。页面显示真实 Union 执行状态，完成后的成果在重启后仍可查看。
5. 仅停止 CONET 的进程：

```bash
.venv/bin/python manage.py down
```

CONET 不管理其他独立运行的 DACP、Union 或官网服务，启动时也不部署远程服务。

## 指南

| 主题 | 文档 |
|---|---|
| 安装、配置、数据生成和启停 | [部署指南](docs/deployment_zh.md) |
| 输入、算法、输出和科学限制 | [科学案例](docs/cases_zh.md) |
| 案例注册和观测接口 | [平台与扩展契约](docs/platform_zh.md) |
| 测试、实测结果和算子提交状态 | [验证记录](docs/verification_zh.md) |

## 数据与源码

默认生成的两类输入合计约 **1.99 GB**：淤地坝数据 1,018,060,800 字节，天文数据约 972 MB（2700 万行）。`.state/` 保存数据、模型权重、凭据、日志、生成的算子和结果，已被 Git 忽略；运行环境依赖 wheel 文件也被忽略。Git 仅保存源码、配置、测试、轻量文档截图和生成脚本。

## 测试

```bash
.venv/bin/python -m unittest discover -s tests -v
.venv/bin/python scripts/smoke_web.py --url http://127.0.0.1:8870
```

冒烟测试会提交两个真实作业，需要已启动 CONET，并准备好数据和运行环境。
