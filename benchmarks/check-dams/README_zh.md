# 淤地坝计算位置对比测试

[English](README.md) · [实测报告](report_zh.md)

比较 CONET 在线淤地坝应用与“**先完整下载三个源数据包，再用同一个 Job 在本地执行**”。CONET 使用其正常的 DACP/Union 路径；集中处理基线仅通过普通 FTP 下载源文件，不使用 DACP 客户端或协议读取源数据。现有节点、Union、应用及本地 XStream 均位于同一物理机，不假设或模拟跨地域网络收益。

## 前提

此工具面向 Linux。

- 现有 CONET 网络和已发布的 `check-dams` 应用正在运行，没有该应用正在排队或执行的分析。
- 合成数据、训练好的合成识别模型、算子包和 Docker 运行镜像已就绪。生成数据、构建镜像和模型不计时。
- DACP Server 已执行 stage。本地 runner 从**与节点相同的服务器分发 JAR** 加载 `xstream.runner.Main`，不用另一套 XStream 构建。
- Python 环境具有 PyYAML；所有源文件下载均使用标准库 `ftplib`。需要已解压的 vsftpd 可执行文件（默认 `$HOME/.local/share/dacp-transfer-tools/extracted/usr/sbin/vsftpd`）。三个临时只读 FTP 服务绑定回环地址，发布与源节点相同的目录，不修改已有 FTP 服务。需要 Java，以及通过非交互 `sudo -n` 执行基于 Docker 的本地任务的权限，与现有节点的运行权限一致。运行前审查命令，不输出凭据。
- 保留超过 2 GiB 磁盘空间。在忽略的 `.state/` 下创建新的输出目录；连接信息、运行日志和完整分析结果不作为公开测试附件。

## 执行

在 CONET 项目根目录运行：

```bash
/home/cnic/dacp-codex/.venv/bin/python benchmarks/check-dams/compare.py \
  --dacp-home /home/cnic/dacp-codex \
  --url http://127.0.0.1:8870 \
  --output "$PWD/.state/benchmarks/check-dams-final" \
  --warmups 1 --repeats 3

/home/cnic/dacp-codex/.venv/bin/python -m unittest discover \
  -s benchmarks/check-dams -p 'test_compare.py'
```

成对冒烟测试使用 `--warmups 0 --repeats 1`。每次分析/本地执行默认超时 600 秒。失败或结果不一致即停止测试，并保留诊断材料。新分析会出现在正常的 CONET 和 Union 历史中，并计为真实应用使用；不删除已有历史。

## 测量约定

1. 使用线上已发布的 Job、默认第一块瓦片的多边形和 0.35 识别置信度。合成数据清单定义三个原始数据包的大小和 SHA-256 摘要，总量约 1.02 GB。普通 FTP 不加密，CONET 仍使用正常 TLS；明确披露安全差异，测试仅使用本机合成数据。
2. CONET 路径：通过应用 HTTP API 提交，等待完成，取回 SVG，校验其中完整的分析元数据。包含应用调度、Union 规划、远程执行及成果打包。保存真实 Union 物理计划以核实分配位置。
3. 集中处理路径：使用匿名、二进制模式 FTP，从三个专用的源 FTP 服务并行下载完整原始包，校验、写入并关闭文件（不调用 fsync），然后用独立 XStream 执行原 Job。仅将三项源 URI 改为 `context:` URI；参数和算法均相同。本地成果同样刷新并关闭（不调用 fsync）并校验。每次新建下载目录，下载和计算不能重叠。
4. 本地计算耗时包含 JVM/目录启动、算子加载及进程/容器启动。CONET 使用已有服务器 JVM，其执行容器正常创建。报告明确披露这一启动差异，不能解释成纯传输收益；常驻本地引擎属于另一种测试。
5. 用固定随机种子交错执行，每种方法预热一次、正式测三次。不清理操作系统页缓存。常驻服务和同机其他活动可能影响结果。本地同样执行允许依赖并行的 Job，不人为改成串行 Python 程序。
6. 端到端耗时在完成规范化全量结果校验后结束。比较分析元数据的所有字段，不仅比较有效点数：同机模式要求值完全相同，远程模式使用下文明确的跨 CPU 浮点容差；另保留 SVG 摘要，JSON 键顺序不影响规范化摘要。源文件摘要错误或结果不一致均导致失败。
7. 单独报告下载和本地计算时间。跨节点负载是否较少须检查物理计划观测，不能根据最终 SVG 大小推断；此工具不测量 TCP 线速字节。

`results.json` 保存各次耗时及校验摘要，完整运行信息和物理计划保留在私有输出目录。不要上传生成的 GB 级数据、凭据及私有状态。可处理的异常会取消当前应用分析；本地超时仅向本次独立启动的进程组发信号。强制杀死测试进程后，可能需要手动核对该次 Job 和容器。

FTP 服务准备不计时；FTP 连接/登录、全部文件传输、摘要校验、写入与关闭文件 均计入下载耗时。服务在 `finally` 中关闭。FTP 配置及 chroot 目录要求归 root 所有，进程组终止使用 `sudo -n`，不新建系统账号。通过 `--vsftpd /path/to/vsftpd` 指定其他解压后的可执行文件。Ubuntu 解压方法：`apt-get download vsftpd`，然后 `dpkg-deb -x vsftpd_*.deb extracted`。

## 生成报告

报告工具需要 `requirements.txt` 中的 Matplotlib；测量工具本身只依赖 PyYAML。可在独立虚拟环境安装依赖。

```bash
python3 -m venv .state/benchmark-venv
.state/benchmark-venv/bin/pip install -r benchmarks/check-dams/requirements.txt
.state/benchmark-venv/bin/python -m unittest discover -s benchmarks/check-dams -p 'test_*.py'
.state/benchmark-venv/bin/python benchmarks/check-dams/publish.py \
  .state/benchmarks/check-dams-final/results.json
```

仅当所有正式、预热样本完整，所有下载均为 FTP，且完整结果一致时才生成中英文报告、图表及脱敏测量。

## 公网部署测试

[公网测试报告](remote/report_zh.md)与原先的[同机测试报告](report_zh.md)分别保存。三个源数据节点、Union、CONET 及三个只读 FTP 服务部署在 `39.106.98.67`；客户端在本机下载并执行集中式 XStream。三个远程中心仍共用一台物理服务器，不改动既有官网及服务。

在新的远程专用目录中准备完全相同的节点、Union、CONET 分发包、`cases/check-dams`（保存为 `conet/check-dams`）、`web`、解引用后的算子库、原始数据及清单和提取出的 `vsftpd` 可执行文件。使用 `docker save` / `docker load` 导出导入相同镜像，不重新构建近似镜像。校验原始数据与清单摘要，以及镜像、JAR、算子包与客户端的一致性。不要复制 Codex/API 配置。测试服务自行生成私有 CA 和运行凭据。

远程环境需要 root、Java、Docker、Python/PyYAML、OpenSSL、iptables、sudo 和 FTP 可执行文件所需的动态库。使用 Snap Docker 时，采用 `/var/snap/docker/common/conet-benchmarks/check-dams-20261108-wan`，确保容器挂载及独立的 JVM 临时目录可访问。目录内包含 `node/`、`union/`、`conet/target/universal/stage/`、`conet/check-dams/`、`conet/web/`、`operators/`、`data/{a,b,c}/`、`data/manifest.json`、`vsftpd` 和 `remote_fixture.py`。

在远程服务器执行，必要时替换允许访问的客户端 IP：

```bash
python3 remote_fixture.py start \
  --root /var/snap/docker/common/conet-benchmarks/check-dams-20261108-wan \
  --public-ip 39.106.98.67 --client-ip 60.245.194.13
```

内部 DACP 端口为 `28701–28703`，Union 为 `28710`，看板为 `28711–28714`，均只监听回环地址。公网端口为 CONET `28720`、FTP 控制连接 `28731–28733`、被动传输 `28740–28757`。限定范围的防火墙规则只允许回环及指定客户端访问，云防火墙也须允许相关端口。节点以匿名可读方式发布合成数据，FTP 匿名且只读，不安装系统级 FTP 服务。明文 FTP 和 HTTP 仅用于本测试的合成数据及结果。

在本机创建私有的 `remote-config.json`：

```json
{
  "connection": {"node_urls": {"a": "dacp://127.0.0.1:28701", "b": "dacp://127.0.0.1:28702", "c": "dacp://127.0.0.1:28703"}},
  "ftp": {"host": "39.106.98.67", "ports": {"a": 28731, "b": 28732, "c": 28733}},
  "network": "Public WAN client-to-server; three remote centers colocated; no shaping",
  "environment": {"host": "39.106.98.67", "logical_cpus": 8, "memory_gib": 15}
}
```

在客户端执行：

```bash
/home/cnic/dacp-codex/.venv/bin/python benchmarks/check-dams/compare.py \
  --remote-config .state/benchmarks/remote-upload/remote-config.json \
  --url http://39.106.98.67:28720 \
  --output .state/benchmarks/check-dams-wan-verified-20261108 \
  --warmups 1 --repeats 3 --timeout 1200
.state/benchmark-venv/bin/python benchmarks/check-dams/publish.py \
  .state/benchmarks/check-dams-wan-verified-20261108/results.json \
  --output benchmarks/check-dams/remote
```

远程模式逐字段检查分析结果，跨 CPU 浮点绝对容差为：经纬度 `1e-9` 度、投影坐标及像素框 `1e-4` 原单位、置信度 `1e-6`、其他浮点数 `1e-8`。类型、整数标识、文本、列表长度与顺序及筛选结论必须完全一致。保留原始结果摘要和每个差异浮点字段的路径及差值。最初严格摘要预检查发现约 `9.54e-6` 米坐标差异，其诊断运行保存在私有目录，不计入后续完整批次。这里检验的是明确边界内的数值等价，不是字节完全相同。

远程与本机硬件不同（8 对 16 个逻辑 CPU），均与其他服务共享资源；本机每次启动独立 runner JVM。Union 可能采用与此前同机测试不同的物理放置，应检查本批次的真实计划与输出负载，不沿用之前的流量统计。源输入不通过 DACP 下载。应用 HTTP 控制与最终结果读取经过公网，节点间传输仍在远程服务器内。

测量结束后，在远程使用相同三个参数执行 `remote_fixture.py stop`。它验证已登记进程的启动时间，只终止自身进程组并删除自身的限定防火墙规则；数据和分发包保留以便复测。这是临时测试服务，不是公开演示部署。
