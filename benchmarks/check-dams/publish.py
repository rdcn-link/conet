#!/usr/bin/env python3
"""Render a verified FTP/local versus CONET batch without exposing private state."""
import argparse
import json
from pathlib import Path
import statistics
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

HERE=Path(__file__).resolve().parent
METHODS=['conet-union','ftp-download-local']

def validate(data):
    cfg=data['config'];samples=data['samples']
    if cfg.get('download_protocol')!='FTP (unencrypted)':raise ValueError('FTP download baseline required')
    if cfg.get('disk_sink')!='write-and-close; no fsync':raise ValueError('Ordinary write/close baseline required')
    required={(method,repeat) for method in METHODS for repeat in range(-cfg['warmups'],cfg['repeats'])}
    actual=[(s['method'],s['repeat']) for s in samples]
    if len(actual)!=len(required) or set(actual)!=required:raise ValueError('Incomplete or duplicate trials')
    hashes=set()
    for sample in samples:
        if sample['state']!='succeeded':raise ValueError('Failed measurements cannot be published')
        if sample['warmup']!=(sample['repeat']<0):raise ValueError('Incorrect warmup flag')
        hashes.add(sample['result_sha256'])
        if sample['method']=='ftp-download-local':
            if len(sample['downloads'])!=3 or any(d['protocol']!='ftp' for d in sample['downloads']):raise ValueError('All downloads must use FTP')
            for download in sample['downloads']:
                source=data['sources'][download['source']]
                if any(source[k]!=download[k] for k in ['bytes','sha256']):raise ValueError('Source integrity mismatch')
        elif {p['center'] for p in sample['placements']}!={'a','b','c'}:raise ValueError('Missing center placement')
    if cfg.get('result_comparison')=='cross-cpu-full-structure-v1':
        for sample in samples:
            comparison=sample.get('comparison_to_reference',{})
            if comparison.get('policy')!='cross-cpu-full-structure-v1':raise ValueError('Missing full-structure comparison')
            differences=comparison.get('differences',[])
            if comparison.get('different_floats')!=len(differences):raise ValueError('Incomplete numeric comparison evidence')
            if any(d['absolute_difference']>d['tolerance'] or d['tolerance']>1e-4 for d in differences):raise ValueError('Excessive numerical differences')
    elif len(hashes)!=1:raise ValueError('Results are not identical')
    return data

def publish(path, destination=HERE):
    destination.mkdir(parents=True,exist_ok=True)
    data=validate(json.loads(path.read_text()));samples=data['samples'];cfg=data['config']
    measured=[s for s in samples if not s['warmup']]
    (destination/'results.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
    out=destination/'figures';out.mkdir(exist_ok=True)
    def vals(method,field):return [s[field] for s in measured if s['method']==method]
    def median(method,field):return statistics.median(vals(method,field))
    total=sum(s['bytes'] for s in data['sources'].values());conet=median('conet-union','wall_s');local=median('ftp-download-local','wall_s')
    remote=median('conet-union','observed_remote_output_payload_bytes')
    reference=measured[0]
    for zh in [False,True]:
        suffix='_zh' if zh else ''
        plt.rcParams.update({'font.family':'WenQuanYi Zen Hei' if zh else 'DejaVu Sans','svg.fonttype':'path'})
        labels=['CONET / Union','FTP + 本地 XStream' if zh else 'FTP + local XStream']
        fig,ax=plt.subplots(figsize=(8,4.4));ys=[conet,local]
        lo=[y-min(vals(m,'wall_s')) for m,y in zip(METHODS,ys)];hi=[max(vals(m,'wall_s'))-y for m,y in zip(METHODS,ys)]
        ax.bar(labels,ys,color=['#14866b','#657d9b'],width=.5,yerr=[lo,hi],capsize=5)
        for i,y in enumerate(ys):ax.text(i,y+max(ys)*.025,f'{y:.2f} s',ha='center')
        ax.set_ylabel('端到端耗时（秒）' if zh else 'End-to-end time (seconds)');ax.set_ylim(0,max(ys+ [max(vals(m,'wall_s')) for m in METHODS])*1.18)
        ax.grid(axis='y',alpha=.2);ax.set_axisbelow(True);fig.tight_layout();fig.savefig(out/('wall'+suffix+'.svg'));plt.close(fig)
        fig,ax=plt.subplots(figsize=(8,4.4))
        a=median('ftp-download-local','download_s');b=median('ftp-download-local','compute_s');c=median('ftp-download-local','validation_s')
        bottom=0
        for value,label,color in [(a,'FTP 下载、校验、写文件' if zh else 'FTP download, verify, close','#c8a45c'),(b,'本地引擎启动与计算' if zh else 'Local engine startup and computation','#657d9b'),(c,'结果读取与校验' if zh else 'Read and validate result','#83b5c1')]:
            ax.bar(['FTP + local XStream'],[value],bottom=bottom,color=color,label=label,width=.45);bottom+=value
        ax.set_ylabel('分阶段耗时（秒）' if zh else 'Stage time (seconds)');ax.legend(loc='upper left',bbox_to_anchor=(1,1));ax.grid(axis='y',alpha=.2);ax.set_axisbelow(True)
        fig.tight_layout();fig.savefig(out/('stages'+suffix+'.svg'));plt.close(fig)
        lines=[('# 淤地坝案例：CONET 与 FTP 集中处理对比' if zh else '# Check-dam analysis: CONET versus FTP download-first processing'),'',('[English](report.md)' if zh else '[中文](report_zh.md)'),'',data['timestamp'],'',
        ('**已有服务均部署在同一台机器，未限速、未模拟 WAN；数据为合成数据。结果比较现有 CONET 应用与先 FTP 下载再本地执行的完整路径，不能解释成纯协议或真实跨地域性能。**' if zh else '**All existing services share one host, without bandwidth shaping or WAN simulation. Data are synthetic. This compares the existing CONET application with FTP download-first local execution, not protocols in isolation or real geographical-center performance.**'),'']
        if 'remote_environment' in data:
            lines[-2]=('**三个数据节点、Union 和 CONET 部署于 39.106.98.67；FTP 下载和集中计算在客户端本机。数据为合成数据，未限速。真实公网链路连接客户端与远程服务器；三个数据中心仍同机，并非三个地理分布中心。**' if zh else '**Three data nodes, Union and CONET run on 39.106.98.67; FTP downloads and centralized computation run on the local client. Data are synthetic, without bandwidth shaping. Client-to-server traffic uses the public WAN; the three centers remain colocated, not geographically distributed.**')
            lines += [('## 测试环境' if zh else '## Environment'),'', '```json',json.dumps(dict(client=data['host'],remote=data['remote_environment']),ensure_ascii=False,indent=2),'```','',('远程与本地硬件不同，且远程服务器存在其他服务负载。此结果衡量两种部署路径的整体效果，不能单独归因于协议或引擎。CONET 的 HTTP 控制与最终结果经过公网，节点之间仍走远程服务器本机网络。' if zh else 'Client and server hardware differ, and unrelated services share the remote server. This measures entire deployment paths, not a protocol or engine in isolation. CONET HTTP control and final results cross the WAN; inter-node transfers remain local to the remote host.'),'']
        lines += [('## 相同任务、两条路径' if zh else '## One task, two execution paths'),'','```mermaid','flowchart LR',
            ('    A[地形中心] --> AT[地形指标与易发沟道]' if zh else '    A[Terrain center] --> AT[Terrain metrics and susceptible channels]'),
            ('    B[遥感中心] --> BT[YOLO 候选识别]' if zh else '    B[Imagery center] --> BT[YOLO candidate detection]'),
            ('    C[水文中心] --> U[遥感节点：空间叠加与制图]' if zh else '    C[Hydrology center] --> U[Imagery node: spatial overlap and map]'),
            '    AT --> U','    BT --> U',('    Planner[Union 规划] -.-> AT' if zh else '    Planner[Union planning] -.-> AT'),'    Planner -.-> BT','    Planner -.-> U',('    U --> Result[通过校验的分析结果]' if zh else '    U --> Result[Validated analysis result]'),
            ('    A --> FTP[FTP：下载全部三个原始包]' if zh else '    A --> FTP[FTP: download all three original bundles]'),
            '    B --> FTP','    C --> FTP',('    FTP --> Local[本地 XStream：同一 Job]' if zh else '    FTP --> Local[Local XStream: identical Job]'),'    Local --> Result','```','',
            ('图示为当前执行路径；Union 负责规划，各数据节点执行分配给它们的步骤。具体分配见下表。' if zh else 'The diagram shows the current execution paths: Union plans, and data nodes execute their assigned steps. Exact placement is listed below.'),'']
        lines += [('## 输入与结果一致性' if zh else '## Inputs and result equality'),'']
        names={'TERRAIN_URI':('地形数据','Terrain'),'IMAGERY_URI':('遥感影像','Imagery'),'CHANNEL_GRIDS_URI':('沟道栅格','Channel grids')}
        for key,source in data['sources'].items():lines += [f"- {names[key][0 if zh else 1]}: `{source['item']}`, {source['bytes']:,} bytes; SHA-256 `{source['sha256']}`."]
        lines += ['',(f"源数据共 {total:,} 字节（{total/1e9:.3f} GB，{total/1048576:.1f} MiB）。分析范围为默认第一块瓦片多边形，识别置信度 0.35；三个源包均完整保留，未为本地路径裁剪。" if zh else f"Source data total {total:,} bytes ({total/1e9:.3f} GB, {total/1048576:.1f} MiB). The analysis uses the default first-tile polygon and confidence 0.35. All original bundles are retained, without cropping for the local path."),'','```json',json.dumps(data['inputs'],ensure_ascii=False,indent=2),'```','',
            (f"所有运行的完整规范化分析 JSON 摘要一致：`{reference['result_sha256']}`。每次均有 {reference['candidate_count']} 个候选、{reference['valid_sites']} 个有效点、{reference['rejected']} 条剔除记录，推理 {reference['windows_inferred']} 个窗口。校验包含分析元数据的全部字段，不仅是数量。" if zh else f"Every run has the same canonical full-analysis JSON digest: `{reference['result_sha256']}`. Each has {reference['candidate_count']} candidates, {reference['valid_sites']} valid sites, {reference['rejected']} rejection records and {reference['windows_inferred']} inference windows. Validation includes all analysis metadata fields, not just counts."),'',
            ('## 实测耗时' if zh else '## Measured time'),' ',
            (f"每条路径预热 {cfg['warmups']} 次、正式运行 {cfg['repeats']} 次，按固定随机种子交错执行。表中为中位数及最小—最大值，误差范围不是置信区间。" if zh else f"Each path has {cfg['warmups']} warmups and {cfg['repeats']} measured runs, interleaved with a fixed random seed. Values are medians and min–max ranges, not confidence intervals."),'',
            ('| 路径 | 端到端中位耗时 | 最小—最大 |' if zh else '| Path | Median end-to-end | Min–max |'),'|---|---:|---:|']
        for method,label in zip(METHODS,labels):lines += [f"| {label} | {median(method,'wall_s'):.3f} s | {min(vals(method,'wall_s')):.3f}–{max(vals(method,'wall_s')):.3f} s |"]
        lines += ['',(f"集中处理 / CONET 耗时比：**{local/conet:.2f}×**。大于 1 表示 CONET 较快，小于 1 表示集中处理较快。" if zh else f"Centralized / CONET time ratio: **{local/conet:.2f}×**. Above 1 means CONET is faster; below 1 means centralized processing is faster."),'',f'![{"端到端耗时" if zh else "End-to-end time"}](figures/wall{suffix}.svg)','',
            ('### 集中处理阶段' if zh else '### Centralized stages'),' ',
            ('| 阶段 | 中位耗时 |' if zh else '| Stage | Median time |'),'|---|---:|',
            f"| {'FTP 并行下载、摘要校验、写入与关闭文件' if zh else 'Parallel FTP download, hash, write and close'} | {a:.3f} s |",
            f"| {'本地引擎启动与计算、成果写入' if zh else 'Local engine startup, computation and output write'} | {b:.3f} s |",
            f"| {'读取并校验完整分析结果' if zh else 'Read and validate full analysis result'} | {c:.3f} s |",'',
            ('各阶段独立取中位数，相加未必等于总耗时中位数。' if zh else 'Independent stage medians need not sum to the median total.'),'',f'![{"集中处理阶段耗时" if zh else "Centralized stage times"}](figures/stages{suffix}.svg)','',
            ('## 实际计算位置与跨节点负载' if zh else '## Actual placement and remote payload'),' ',
            ('| 节点 | 步骤 |' if zh else '| Center | Steps |'),'|---|---|']
        centers={'a':('地形地貌数据中心','Terrain center'),'b':('遥感观测数据中心','Imagery center'),'c':('水文流域数据中心','Hydrology center')}
        s=next(s for s in measured if s['method']=='conet-union')
        for p in s['placements']:lines += [f"| {centers[p['center']][0 if zh else 1]} | "+', '.join('`'+x+'`' for x in p['steps'])+' |']
        lines += ['',(f"CONET 节点输出端口记录的跨节点有效负载中位数为 {remote:,.0f} 字节；集中路径每次 FTP 下载 {total:,} 字节。前者仍包含完整沟道栅格的跨节点传输，不能宣称只传小结果；二者也不是 TCP/TLS 线速字节统计。最终 SVG 为 {reference['result_bytes']:,} 字节，不代表整个协作过程的传输量。" if zh else f"Median observed remote payload across CONET node output ports is {remote:,.0f} bytes, versus {total:,} FTP source bytes per centralized run. CONET still transfers the full channel grid between nodes; it does not transmit only small results. These are not TCP/TLS wire-byte measurements. The final SVG is {reference['result_bytes']:,} bytes and is not the traffic total of the collaboration."),'',
            ('## 测量边界' if zh else '## Measurement boundaries'),' ',
            ('- CONET 从提交应用请求计时，包含应用排队、Union 规划、执行和结果打包，直到获取 SVG 并校验。\n- 集中路径从三个 FTP 连接开始计时，三个下载并行且每次重新下载，完整下载/校验/文件关闭 后才启动本地计算，不重叠下载与计算。\n- 原 Job、参数、算子包和引擎 JAR 相同，只有三项 URI 改成本地 context:。本地不人为串行化 Job。\n- 本地每次启动独立 JVM；CONET 的服务器 JVM 常驻。算子进程/容器均正常启动，本地计算阶段包含引擎启动时间。\n- FTP 是明文；CONET 使用现有 TLS。网络安全条件不同，应结合应用场景解释。\n- 数据、模型和镜像预先生成，不计入分析；不清理 OS 缓存，现有服务共享 CPU/内存/磁盘。\n- 只测这一个区域、当前实现和同机部署。不能将实测耗时或负载外推为 WAN 加速倍数，也不将下载集中后重复分析的成本等同于每次重新下载。' if zh else '- CONET timing starts at the application request and includes queuing, Union planning, execution and packaging, ending after SVG retrieval and validation.\n- Centralized timing starts before the three parallel FTP connections; each trial downloads anew. All downloads, verification and file close finish before local computation starts.\n- The Job, parameters, operator packages and engine JARs match; only three URIs become local context: references. Local dependency parallelism is preserved.\n- Local execution launches a fresh JVM, while CONET server JVMs persist. Operator processes/containers start normally; local computation includes engine startup.\n- FTP is unencrypted; CONET uses its existing TLS. Security conditions differ and must be interpreted with the workload.\n- Data, models and images are prepared outside timing. OS caches are not cleared; existing services share CPU, memory and disk.\n- This tests one region and the current implementation on one host. Timings and payloads cannot establish a WAN speedup or describe repeated local analysis after downloading once.'),'',
            ('## 逐次结果与复现' if zh else '## Individual trials and reproduction'),' ',
            ('| 正式轮次 | CONET 秒 | FTP 集中处理秒 |' if zh else '| Measured repeat | CONET s | FTP centralized s |'),'|---:|---:|---:|']
        for repeat in range(cfg['repeats']):
            pair={s['method']:s for s in measured if s['repeat']==repeat}
            lines += [f"| {repeat+1} | {pair['conet-union']['wall_s']:.3f} | {pair['ftp-download-local']['wall_s']:.3f} |"]
        wins=sum(next(s['wall_s'] for s in measured if s['method']=='conet-union' and s['repeat']==r)<next(s['wall_s'] for s in measured if s['method']=='ftp-download-local' and s['repeat']==r) for r in range(cfg['repeats']))
        lines += ['',(f"三组成对正式运行中，CONET 有 {wins} 组较快，集中处理有 {cfg['repeats']-wins} 组较快。样本数量有限且范围重叠，中位数比不是稳定加速保证，也不是统计显著性结论。" if zh else f"Of {cfg['repeats']} paired measured runs, CONET is faster in {wins} and centralized processing in {cfg['repeats']-wins}. The small sample and overlapping ranges do not establish a stable speedup or statistical significance."),'']
        if 'remote_environment' in data:
            replacement=(f"所有运行都进行了完整字段、类型、顺序和列表长度的校验。整数、字符串和筛选结论完全一致；跨 CPU 浮点字段按明确容差比较：经纬度 1e-9 度、投影坐标和像素框 1e-4 原单位、置信度 1e-6、其他浮点数 1e-8。保留每次原始 JSON 摘要和差异路径，不宣称逐字节相同。各次均有 {reference['candidate_count']} 个候选、{reference['valid_sites']} 个有效点、{reference['rejected']} 条剔除记录、{reference['windows_inferred']} 个推理窗口。" if zh else f"All runs validate every field, type, list length and order. Integers, strings and selection decisions match exactly; cross-CPU floating values use explicit absolute tolerances: longitude/latitude 1e-9 degrees, projected/pixel positions 1e-4 native units, confidence 1e-6, and other floats 1e-8. Original JSON hashes and difference paths are retained; results are not claimed to be byte-identical. Every run has {reference['candidate_count']} candidates, {reference['valid_sites']} valid sites, {reference['rejected']} rejections and {reference['windows_inferred']} inference windows.")
            lines=[replacement if line.startswith(('所有运行的完整规范化分析 JSON','Every run has the same canonical')) else line for line in lines]
            if all('spatial_overlap' in next(p['steps'] for p in x['placements'] if p['center']=='c') for x in measured if x['method']=='conet-union'):
                lines=[line.replace('遥感节点：空间叠加与制图','水文节点：空间叠加与制图').replace('Imagery node: spatial overlap and map','Hydrology node: spatial overlap and map').replace('前者仍包含完整沟道栅格的跨节点传输，不能宣称只传小结果','本批次叠加分析放在沟道栅格所在节点，避免传输完整沟道栅格；前次同机测试的放置不同，流量不可直接等同').replace('CONET still transfers the full channel grid between nodes; it does not transmit only small results.','In this batch overlap runs on the channel-grid node, avoiding a full channel-grid transfer. Placement differs from the previous local batch, so its traffic cannot be assumed identical.') for line in lines]
            lines=[line.replace('只测这一个区域、当前实现和同机部署。不能将实测耗时或负载外推为 WAN 加速倍数','只测这一个区域、当前实现和本次远程部署。不能将实测耗时或负载外推为普遍的 WAN 加速倍数').replace('This tests one region and the current implementation on one host. Timings and payloads cannot establish a WAN speedup','This tests one region and the current implementation in this remote deployment. Timings and payloads cannot establish a universal WAN speedup').replace('样本数量有限且范围重叠','样本数量有限').replace('The small sample and overlapping ranges','The small sample') for line in lines]
        lines += ['',('[执行方法与前提](README_zh.md) · [原始脱敏测量](results.json)' if zh else '[Reproduction and prerequisites](README.md) · [Sanitized raw measurements](results.json)'),'']
        text='\n'.join(lines).replace('\n \n','\n\n')
        if destination.resolve()!=HERE.resolve():text=text.replace('(README.md)','(../README.md)').replace('(README_zh.md)','(../README_zh.md)')
        (destination/('report'+suffix+'.md')).write_text(text)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('results',type=Path);p.add_argument('--output',type=Path,default=HERE);args=p.parse_args();publish(args.results,args.output)
