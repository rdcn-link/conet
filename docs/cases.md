# Scientific cases

[中文](cases_zh.md) · [Home](../README.md)

## 1. Check dams within susceptible gullies

This case identifies **existing synthetic check-dam structures** within susceptible gullies. It does not design new dam sites. Select a required WGS84 bounding box `[west, south, east, north]`; the fixed [`job.yaml`](../cases/check-dams/job.yaml) supplies the processing graph and input variables. The Web backend discovers source dataset URIs from the Union catalog.

| Center | Data | Approximate volume |
|---|---|---:|
| Terrain and Geomorphology Data Center | DEM, catchments, complete channel geometry/topology | 265 MiB |
| Remote Sensing Data Center | RGB imagery and georeferencing control information | 530 MiB |
| Hydrology and Watershed Data Center | HydroID channel rasters | 177 MiB |

```mermaid
flowchart LR
    A[Terrain and Geomorphology Data Center: terrain] --> S[Slope and entropy]
    S --> H[Susceptible channels]
    B[Remote Sensing Data Center: imagery] --> Y[YOLO segmentation]
    C[Hydrology and Watershed Data Center: HydroID raster] --> O[Spatial overlay]
    H --> O
    Y --> O
    O --> M[Distribution map and GeoJSON]
```

The diagram represents data dependencies. Union chooses execution locations; the node publishing a dataset does not necessarily run the final overlay.

### Algorithms and defaults

1. **Gully slope:** `(upstream elevation − downstream elevation) / along-channel planar length`. It is a ratio, not an angle; 0.08 represents 8%.
2. **Geomorphic entropy:** normalized Shannon entropy of 16 equally spaced elevation bins per complete synthetic tile basin, `H = −Σ pᵢ ln(pᵢ) / ln(16)`. Constant elevation gives zero. This implementation is a reproducible demonstration metric, not a validated regional geomorphic model.
3. **Susceptibility:** seed slope 0.08–0.50 and entropy `< 0.2`; follow `NextDownID` up to 32 downstream hops, stopping at missing nodes/outlets and rejecting cycles. Analysis retains full topology/basin context; map geometries are clipped to the requested box.
4. **Detection:** real YOLO11n segmentation trained on synthetic dam motifs; 128-pixel windows, stride 96, confidence default 0.35, georeferencing checks, duplicate merging within 60 m.
5. **Overlay:** project detections to the HydroID raster and inspect a 7×7 neighborhood (`bias=3`). Select the nearest nonzero HydroID, mark multi-ID ambiguity, and accept only when that nearest ID belongs to the susceptible set. A non-nearest susceptible ID does not override the nearest ID.

The trained model is fixed between requests. Parameters and region selection change; the workflow definition does not. Sources stream as bounded members; source archives are currently fully consumed, not remotely indexed by region. Intermediate JSON has an 8 MiB bound. Raster members are limited to 48 MiB and three million cells. The final application stores results; this is distinct from intermediate workflow materialization.

### Results

- `distribution.svg`: distribution map containing JSON analysis metadata.
- `sites.geojson`: accepted candidate coordinates and provenance properties.
- `analysis.json`: candidates, rejection reasons, HydroIDs, metrics, model and parameters.
- `variables.yaml`: resolved variables used for this submission.

![Check-dam analysis interface](images/check-dams.png)

## 2. Three-observatory crossmatching and colors

Three fictional observatories provide g/r/i catalogs. Each has nine million rows split into 18 FITS shards, including source coordinates, flux, uncertainty and quality. Source IDs are synthetic audit labels, **not matching keys**.

The fixed [`job.yaml`](../cases/observatory/job.yaml) uses `CONE = [RA, Dec, radius]` in ICRS degrees (default `[150, 2, 0.15]`), `MATCH_RADIUS_ARCSEC` (default 0.8) and `MIN_SNR` (default 5). The supported cone radius is 0.001–1 degree, with at most 50,000 selected sources per band.

```mermaid
flowchart LR
    G[g catalog] --> GS[Cone and quality selection]
    R[r catalog] --> RS[Cone and quality selection]
    I[i catalog] --> IS[Cone and quality selection]
    GS --> X[Spherical unique crossmatch]
    RS --> X
    IS --> X
    X --> C[Calibrated colors and errors]
    C --> P[Sky plot and color-color plot]
```

Selection rejects bad quality, non-finite or nonpositive flux/error and low SNR. Magnitudes use `MAGZP − 2.5 log10(FLUX) + ZPCORR`; uncertainty is `(2.5/ln(10)) × FLUX_ERR/FLUX`. Matching uses ICRS spherical geometry and rejects multiple alternatives. All g–r, g–i and r–i pairwise separations must satisfy the matching radius.

Colors are `g−r` and `r−i`; their errors are quadrature sums. Illustrative red selection uses `g−r ≥ 1.5` and `r−i ≥ 0.8`; blue uses both colors `≤ 0`; other points are labeled stellar locus. These are display cuts, not astrophysical classifications. Catalogs are scanned in full; this implementation has no indexed remote cone reads.

The Web interface displays a sky plot, color–color plot, hover inspection and first-200-row table. Complete matches are downloadable as `matches.csv` and `analysis.json`, together with `variables.yaml`.

![Observatory analysis interface](images/observatory.png)

## Interactive visualization

App Builder renders configured map, table and download views for the check-dam results. Scientific operators and fixed Job definitions are shared with the other applications; there is no separate custom case Web frontend.

Astronomy plots use the actual matched sources on a dark coordinate grid. Star size reflects the synthetic g magnitude, and selecting a source in either chart or the table highlights it in both charts and displays photometry and matching details. The selection map displays matched-source positions after completion. No fabricated survey background is added.

### Tianditu geographic basemaps

Geographic maps in all three cases use the Tianditu browser SDK. Check-dam selection/results, emergency selection/results and observatory locations distinguish real geographic backgrounds from synthetic scientific features. Astronomy sky plots retain ICRS celestial coordinates. See [case maps](maps.md) for configuration and export behavior.

The selector initially selects and displays an illustrative Yellow River regional rectangle (95–120°E, 32–42°N), not an authoritative basin boundary. Drag in pan mode, use the wheel or +/− to zoom, double-click to zoom in, or switch to box selection. The data-coverage button selects and fits the available data footprint. Analysis uses only the intersection with the existing synthetic dataset; selecting a larger region does not add data.

### Precise polygon selection

Use **Polygon**, click at least three vertices, and choose **Finish drawing**. **Edit vertices** lets you drag existing vertices; **Undo vertex** removes the latest draft point, and Escape cancels a draft. The coordinate fields show the bounding rectangle; editing them replaces the selection with a rectangle. Rectangles are submitted as Polygons too.

The HTTP request accepts `region`, a WGS84 GeoJSON Polygon geometry, and `min_confidence`. It validates closed rings, finite coordinate pairs, positive area, self-intersections and intersection with the data footprint. Up to 256 coordinates across at most 16 rings are accepted. Holes are supported by the API; the drawing tool creates one exterior ring. Antimeridian-crossing selections are unsupported. Invalid polygons are rejected rather than silently replaced by their bounding rectangle.

```json
{"region":{"type":"Polygon","coordinates":[[[109.12,36.06],[109.19,36.06],[109.12,36.12],[109.12,36.06]]]},"min_confidence":0.35}
```

The fixed Job receives `REGION` and a derived `REGION_BBOX`. The envelope narrows imagery tile/window candidates; precise polygon intersection narrows windows further and point-in-polygon tests filter detections. Overlay independently rechecks each point. Exterior and hole boundaries are retained, while hole interiors are excluded. Complete terrain basins and channel topology remain available for entropy and downstream tracing. Only rendered channel geometry is clipped; selection must not truncate scientific context. Results and downloaded metadata retain the original polygon, and both map views draw its actual rings. The local operators use version `1.1`.

The observatory selector fits the initial cone ellipse into the canvas with a small margin. It supports +/− and wheel zoom, a pan mode, fitting the current selection and resetting to the example cone. Zoom and pan change only the view, not the submitted cone. Editing valid coordinates refits the view.

Application input validation, variable bindings, execution, results and usage counters are supplied by [App Builder](appbuilder.md). Each case declares its behavior in `application.yaml` and its graph in `job.yaml`.
