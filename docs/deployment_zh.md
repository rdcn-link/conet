# 部署与数据生成

[English](deployment.md) · [首页](../README_zh.md)

## 前置条件

使用 Linux、Python 3.12、Java 17、OpenSSL、Docker 和已构建的 DACP 分发目录。建议至少预留 10 GB 磁盘和约 8 GB 可用内存；模型训练与镜像构建还需要额外临时空间。`manage.py` 使用 `sudo -n` 启动 Docker 和节点进程，需要配置合适的本机管理权限。这是面向内网的演示部署，不包含公众用户账号体系。

`DACP_HOME` 指向包含以下已暂存程序的 DACP 项目目录：

```text
dacp-server/target/universal/stage/bin/dacp-server
dacp-server/target/universal/stage/bin/dacp-admin
dacp-union-server/target/universal/stage/bin/dacp-union-server
dacp-union-server/target/universal/stage/bin/union-admin
```

SDK 应与节点使用相同的 DACP 修订版本。节点执行还需要 XStream 开发安装提供的基础镜像 `xstream/operator-python:1.0`。

```bash
export DACP_HOME=/path/to/dacp-codex
python3.12 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt "$DACP_HOME/dacp-client"
```

主机依赖使用 PyArrow 23.0.1，与所安装的 DACP Python 客户端一致。算子容器的锁定依赖单独使用 PyArrow 26.0.1。不要把算子运行环境的依赖锁文件安装到主机 SDK 环境中。

## 生成两个 GB 级数据集

在 CONET 项目根目录运行。生成器会拒绝已存在的输出目录；应保留已有数据或选择新目录，不要覆盖。

```bash
mkdir -p .state/cases/check-dams .state/cases/observatory
.venv/bin/python cases/check-dams/generate_data.py \
  .state/cases/check-dams/data --tile-size 1700 --grid 4 --resolution 5
.venv/bin/python cases/observatory/generate_data.py \
  .state/cases/observatory/data --rows 9000000 --chunk-rows 500000
```

第一个命令生成 16 个空间瓦片，以 5 米分辨率覆盖约 34 × 34 千米，总量 **1,018,060,800 字节**。第二个命令生成**每个天文台 900 万行**、总计 2700 万行，每个波段分为 18 个 FITS 分片，总量约 972 MB。生成器使用确定性的合成随机种子。输入 bundle 包含未压缩的 TAR 成员，采用 `.bundle` 扩展名，以字节流而非压缩包目录视图发布。

当前本机安装通过被 Git 忽略的 `.state/` 软链接复用已生成的输入目录。源码不依赖旧案例项目路径；全新安装可用上述命令独立生成数据。

## 训练合成分割模型

使用单独的训练环境，避免修改主机 SDK 依赖：

```bash
python3.12 -m venv .state/training-venv
.state/training-venv/bin/python -m pip install \
  --index-url https://download.pytorch.org/whl/cpu torch==2.7.1+cpu torchvision==0.22.1+cpu
.state/training-venv/bin/python -m pip install --no-deps \
  -r cases/check-dams/runtime/other-requirements.txt
.state/training-venv/bin/python cases/check-dams/train_model.py \
  --output .state/cases/check-dams/model --epochs 80
```

训练采用虚构坝体图案和真实 YOLO11n 分割网络。模型卡写入 `model.pt` 同级目录。较高的合成验证集分数不能证明真实遥感影像上的识别精度。模型权重、训练样本和训练过程均被 Git 忽略。

## 构建运行环境

```bash
.venv/bin/python scripts/prepare_runtimes.py
```

脚本将精确版本的 wheel 下载到被忽略的 `cases/*/runtime/wheels/`，再构建 `xstream/check-dams-demo:1.0` 和 `xstream/observatory-demo:1.0`。镜像构建阶段离线安装依赖；作业运行时无需从外网下载模型或软件包。

## 配置与启停

`conet.yaml` 定义门户地址和端口、案例标识与目录、回环后端端口，以及中心和数据集说明。每个数据集必须有标题和描述。现有案例使用中心标识 `a`、`b`、`c`，数据目录名和源元数据也遵循该约定。案例依据数据集描述中的 `case` 和 `center` 路由，不依赖 Union 重命名后的数据集名称。

```bash
export DACP_HOME=/path/to/dacp-codex
.venv/bin/python manage.py up
.venv/bin/python manage.py status
.venv/bin/python manage.py down
```

启动会在 `.state/network/` 创建持久 CA、各成员及 Union 的独立证书、Union 授权和 runner 账号。七个节点和 Union 使用本机可用端口，从现有案例清单和分析参数生成四个小型参考数据集，等待十个数据集全部可见后，启动两个回环案例后端和 8870 端口的门户。节点和 Union 看板在动态端口上监听所有网卡，地址写入 `.state/network/connection.json`。门户应仅向可信网络开放，或置于已有访问边界之后。门户不会把服务 Token 或私钥发给浏览器。

进程 PID 和应用启动时间均会记录。停止时检查进程归属，只向所管理的进程组发信号；启动失败会回收其已启动进程。科学输入数据和完成的结果在停止后保留。日志位于 `.state/*.log` 和 `.state/network/*.log`。

## 提交算子审核

验证通过后运行：

```bash
.venv/bin/python scripts/publish_operators.py \
  --url http://localhost:8820 \
  --admin-password-file /secure/repository-password \
  --token-file /secure/submitter.token
```

非本机访问必须使用 HTTPS。管理员权限用于注册两个不可变的科学执行环境，提交者 Token 用于上传七个仅源码算子。仓库使用已有 Python/Arrow 2.0 构建工具将源码编译为算子包。检测模型是不可变的运行环境资源，不放进源码包。运行环境归档可能包含训练权重，但不得放进 Git。脚本**不会**审批或指定可信度；构建成功后仍须管理员审核。CONET 当前执行管理员安装的本地版本，与仓库审批状态独立。
