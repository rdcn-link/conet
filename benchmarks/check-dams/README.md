# Check-dam placement benchmark

[中文](README_zh.md) · [Measured report](report.md)

Compare the live CONET check-dam application with **download all three source bundles first, then run the same Job locally**. CONET uses its normal DACP/Union path. The centralized baseline downloads through plain FTP only; it does not use the DACP client or protocol to read source content. Existing nodes, Union, the application and local XStream share one physical host; no geographical-network benefit is assumed or simulated.

## Prerequisites

This harness targets Linux.

- The existing CONET network and published `check-dams` application are running. No app analysis should already be queued or running.
- Generated synthetic data, the trained synthetic detector, operator packages and Docker runtime images are already available. Input preparation and image/model building are outside timing.
- DACP Server has been staged. The local runner loads `xstream.runner.Main` from **the same server-distribution JARs as the nodes**, rather than another XStream build.
- Python with PyYAML; the harness uses the standard-library `ftplib` for all source downloads. An extracted vsftpd executable is required (default: `$HOME/.local/share/dacp-transfer-tools/extracted/usr/sbin/vsftpd`). Three temporary, read-only FTP servers bind to loopback and expose the same directories as the source nodes. No existing FTP service is changed. Java and noninteractive `sudo -n` access to Docker-backed local execution are required, matching the existing node runtime permissions. Review commands before running; no credentials are printed.
- Allow over 2 GiB free disk space. Use a new output directory under ignored `.state/`; connection details, run logs and full analysis outputs are not public benchmark artifacts.

## Run

From the CONET checkout:

```bash
/home/cnic/dacp-codex/.venv/bin/python benchmarks/check-dams/compare.py \
  --dacp-home /home/cnic/dacp-codex \
  --url http://127.0.0.1:8870 \
  --output "$PWD/.state/benchmarks/check-dams-final" \
  --warmups 1 --repeats 3

/home/cnic/dacp-codex/.venv/bin/python -m unittest discover \
  -s benchmarks/check-dams -p 'test_compare.py'
```

Use `--warmups 0 --repeats 1` for a paired smoke run. Default timeout is 600 seconds per analysis/local execution. A failed or mismatching run stops the batch; results are retained for diagnosis. New analyses appear in normal CONET and Union history, and count as real app usage. Existing history is not removed.

## Measurement contract

1. Use the live published Job and default first-tile polygon, with confidence 0.35. The synthetic manifest defines the three original bundle sizes and SHA-256 digests (about 1.02 GB total). Plain FTP is unencrypted, while CONET retains its normal TLS; this security difference is explicit and the benchmark uses only synthetic data.
2. CONET: submit through its HTTP app API, wait for completion, fetch the SVG and validate its full analysis metadata. This includes app scheduling, Union planning, remote execution and result packaging. Retain actual Union physical plans for placement verification.
3. Download/local: concurrently download three complete original bundles from three dedicated source FTP endpoints using anonymous binary-mode FTP, hash them, write and close local files (without fsync), then run the unchanged Job in standalone XStream. Only the three source URIs change to `context:` URIs. All parameters and algorithms are identical. Local results are also flushed and closed (without fsync) and validated. A fresh download directory is used per trial; downloads cannot overlap with computation.
4. The local computation time includes JVM/catalog startup, operator loading and process/container startup. CONET uses existing server JVMs; its execution containers are created normally. This startup asymmetry is disclosed and must not be mistaken for a pure transport effect. A persistent-local-engine workload would be a separate experiment.
5. Methods are interleaved with a fixed random seed; use one warmup and three measured trials per method. OS caches are not cleared. Running services and shared-host activity can affect results. The baseline is allowed the same dependency-parallel Job, not an artificially sequential Python rewrite.
6. End-to-end time stops after canonical full-result validation. Compare every analysis metadata field, not just accepted-site counts: same-host mode requires exact values, while remote mode uses the explicit cross-CPU float tolerances documented below. SVG hashes are also retained; key ordering does not affect canonical JSON hashes. Invalid source hashes or different results fail the test.
7. Report download and local computation separately. A low inter-node payload claim requires inspecting the physical-plan observations; it cannot be inferred from the final SVG size, and this harness does not measure TCP wire bytes.

`results.json` contains trial timings and verification hashes. Full run details and physical plans remain in the private output directory. Do not upload generated GB-scale data, credentials or private state. Stopping the harness cancels the current app analysis on handled errors; local timeout signals only its isolated launcher group. Hard kills may require checking the specific run's Job and containers manually.

FTP fixture setup is excluded from timing; FTP connect/login, all file transfers, hashing, writes and file close are included in download time. Servers stop in `finally`. The FTP configuration/chroot directory require root ownership, and process-group termination uses `sudo -n`; no system account is created. Pass `--vsftpd /path/to/vsftpd` to override the extracted executable. Ubuntu extraction: `apt-get download vsftpd`, then `dpkg-deb -x vsftpd_*.deb extracted`.

## Generate the report

The publisher needs Matplotlib from `requirements.txt`; the measurement harness itself only needs PyYAML. Install dependencies in a separate environment.

```bash
python3 -m venv .state/benchmark-venv
.state/benchmark-venv/bin/pip install -r benchmarks/check-dams/requirements.txt
.state/benchmark-venv/bin/python -m unittest discover -s benchmarks/check-dams -p 'test_*.py'
.state/benchmark-venv/bin/python benchmarks/check-dams/publish.py \
  .state/benchmarks/check-dams-final/results.json
```

The publisher writes bilingual reports, charts and sanitized measurements only when all measured/warmup trials are complete, every download uses FTP and full results match.

## Public-WAN deployment

[WAN measurements](remote/report.md) keep the original [single-host measurements](report.md) separate. Three source nodes, Union, CONET and three read-only FTP endpoints run on `39.106.98.67`; the client downloads and runs centralized XStream on the local machine. The three remote centers share one physical server. Existing websites and services are left unchanged.

Stage the exact node/Union/CONET distributions, `cases/check-dams` (as `conet/check-dams`), `web`, dereferenced operator library, original data/manifest, and an extracted `vsftpd` binary in a new dedicated remote directory. Export/import the exact runtime with `docker save` / `docker load`; do not rebuild an approximate image. Verify original bundle digests against the manifest and image/JAR/package digests against the client. Never copy Codex/API configuration. The fixture generates its own private CA and runner credentials.

The isolated fixture needs root, Java, Docker, Python/PyYAML, OpenSSL, iptables, sudo and the binary's shared libraries. On Snap Docker use `/var/snap/docker/common/conet-benchmarks/check-dams-20261108-wan` so bind mounts and its private JVM temporary directory are accessible. It contains `node/`, `union/`, `conet/target/universal/stage/`, `conet/check-dams/`, `conet/web/`, `operators/`, `data/{a,b,c}/`, `data/manifest.json`, `vsftpd` and `remote_fixture.py`.

Run on the remote host, replacing the allowed client IP when necessary:

```bash
python3 remote_fixture.py start \
  --root /var/snap/docker/common/conet-benchmarks/check-dams-20261108-wan \
  --public-ip 39.106.98.67 --client-ip 60.245.194.13
```

Internal DACP ports are `28701–28703`, Union `28710`, dashboards `28711–28714`, all bound to loopback. Public ports are CONET `28720`, FTP controls `28731–28733` and passive transfers `28740–28757`. Scoped firewall rules admit loopback and the specified client only. These ports must also be allowed by the server's cloud firewall. Nodes publish synthetic source datasets with anonymous read access; FTP is anonymous and read-only. No system FTP service is installed. Plain FTP and HTTP must only carry these synthetic benchmark inputs/results.

Create a private local `remote-config.json`:

```json
{
  "connection": {"node_urls": {"a": "dacp://127.0.0.1:28701", "b": "dacp://127.0.0.1:28702", "c": "dacp://127.0.0.1:28703"}},
  "ftp": {"host": "39.106.98.67", "ports": {"a": 28731, "b": 28732, "c": 28733}},
  "network": "Public WAN client-to-server; three remote centers colocated; no shaping",
  "environment": {"host": "39.106.98.67", "logical_cpus": 8, "memory_gib": 15}
}
```

On the client:

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

The remote mode compares every analysis field with explicit cross-CPU floating-point tolerances: longitude/latitude `1e-9` degrees, projected coordinates/pixel boxes `1e-4` native units, confidence `1e-6`, other floats `1e-8`. Types, integer IDs, text, list lengths/order and selection decisions remain exact. Raw result hashes and every differing float path/value difference are retained. The initial strict-hash preflight found a `9.54e-6` meter coordinate difference; its diagnostic runs are retained privately and excluded from the subsequent complete batch. This is numerical equivalence within declared bounds, not byte equality.

Remote and client hardware differ (8 vs 16 logical CPUs), both share resources with other services, and the client starts a fresh runner JVM. Union may choose a different physical placement from the earlier local experiment. Inspect actual plans and output payloads for this batch; do not reuse the local batch's traffic figures. No source input is downloaded through DACP. The app's HTTP control and final-result retrieval cross the WAN; inter-node traffic remains on the remote server.

After measurement run `remote_fixture.py stop` with the same three options on the remote host. It checks registered process start times, signals only its own process groups and removes only its scoped firewall rules. Data and distributions remain available for a rerun. The benchmark services are temporary, not public demo deployments.
