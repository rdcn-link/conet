# Deployment and data generation

[中文](deployment_zh.md) · [Home](../README.md)

## Prerequisites

Use Linux, Python 3.12, Java 17, OpenSSL, Docker and built DACP distribution directories. Reserve at least 10 GB free disk and approximately 8 GB available memory. Model training and image construction require additional temporary disk space. `manage.py` uses `sudo -n` for Docker and node process startup; configure suitable local administrative permissions. This is an internal-network demonstration deployment without a public-user account system.

`DACP_HOME` points to a DACP checkout containing staged programs:

```text
dacp-server/target/universal/stage/bin/dacp-server
dacp-server/target/universal/stage/bin/dacp-admin
dacp-union-server/target/universal/stage/bin/dacp-union-server
dacp-union-server/target/universal/stage/bin/union-admin
```

Install the SDK from the same DACP revision. Node execution also needs the base `xstream/operator-python:1.0` image supplied by the XStream development installation.

```bash
export DACP_HOME=/path/to/dacp-codex
python3.12 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt "$DACP_HOME/dacp-client"
```

Host dependencies use PyArrow 23.0.1 to match the installed DACP Python client. Operator container locks separately use PyArrow 26.0.1. Do not install operator runtime locks into the host SDK environment.

## Generate the two GB-scale datasets

Run from the CONET project root. Generators reject an existing output directory; preserve existing data or select a new directory rather than overwriting it.

```bash
mkdir -p .state/cases/check-dams .state/cases/observatory
.venv/bin/python cases/check-dams/generate_data.py \
  .state/cases/check-dams/data --tile-size 1700 --grid 4 --resolution 5
.venv/bin/python cases/observatory/generate_data.py \
  .state/cases/observatory/data --rows 9000000 --chunk-rows 500000
```

The first command creates 16 spatial tiles covering approximately 34 × 34 km at 5 m resolution, totaling **1,018,060,800 bytes**. The second creates **9 million rows per observatory**, 27 million in total, in 18 FITS shards per band, totaling approximately 972 MB. Generators use deterministic synthetic seeds. Input bundles contain uncompressed TAR members and use the `.bundle` extension to expose a raw byte stream rather than an archive-folder view.

The local working installation reuses existing generated input directories through ignored `.state/` symbolic links. No source file depends on those old project paths; a fresh installation generates its own data with the commands above.

## Train the synthetic segmentation model

Create a separate training environment to avoid changing the host SDK dependencies:

```bash
python3.12 -m venv .state/training-venv
.state/training-venv/bin/python -m pip install \
  --index-url https://download.pytorch.org/whl/cpu torch==2.7.1+cpu torchvision==0.22.1+cpu
.state/training-venv/bin/python -m pip install --no-deps \
  -r cases/check-dams/runtime/other-requirements.txt
.state/training-venv/bin/python cases/check-dams/train_model.py \
  --output .state/cases/check-dams/model --epochs 80
```

Training uses fabricated dam motifs and a real YOLO11n segmentation network. The model card is written next to `model.pt`. High synthetic validation scores do not establish accuracy on real satellite imagery. Weights, training examples and training runs are ignored by Git.

## Prepare runtimes

```bash
.venv/bin/python scripts/prepare_runtimes.py
```

This downloads exact wheel versions into ignored `cases/*/runtime/wheels/`, then builds `xstream/check-dams-demo:1.0` and `xstream/observatory-demo:1.0`. Image builds install dependencies offline. Running Jobs needs no external model or package download.

## Configure and run

`conet.yaml` defines the portal address/port, case identifiers and directories, loopback backend ports, and center/dataset descriptions. Each dataset requires a title and description. The included cases use center IDs `a`, `b`, `c`; their data-directory names and source metadata follow that convention. Case routing uses the `case` and `center` fields in dataset descriptions, not renamed Union dataset names.

```bash
export DACP_HOME=/path/to/dacp-codex
.venv/bin/python manage.py up
.venv/bin/python manage.py status
.venv/bin/python manage.py down
```

Startup creates one persistent CA, unique certificates for each member and Union, Union grants and a runner account under `.state/network/`. It creates three nodes and a Union with available local ports, waits for all six datasets, starts two loopback case backends, then the portal on port 8870. The node/Union dashboards bind to all interfaces on dynamically allocated ports; their URLs are in `.state/network/connection.json`. Expose the portal only on a trusted network or put it behind your normal access boundary. The portal never sends service Tokens or private keys to browsers.

All process IDs and application start times are recorded. Shutdown checks ownership before signaling only those process groups. Startup failure rolls back processes it started. Scientific input data and completed results survive shutdown. Logs are `.state/*.log` and `.state/network/*.log`.

## Submit operators for review

After verification, run:

```bash
.venv/bin/python scripts/publish_operators.py \
  --url http://localhost:8820 \
  --admin-password-file /secure/repository-password \
  --token-file /secure/submitter.token
```

HTTPS is required outside localhost. Administrator access registers two immutable scientific execution runtimes; a submitter Token uploads seven source-only operators. Existing Python/Arrow 2.0 build tooling compiles source into packages. The detector model is an immutable runtime asset, not a source archive member. Runtime archives may contain trained weights; do not put them into Git. The script does **not** approve submissions or assign trust scores. Successful builds remain pending until an administrator reviews them. CONET currently executes its administrator-installed local versions, independently of repository approval.
