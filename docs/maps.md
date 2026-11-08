# Case maps

The three case applications use the **Tianditu JavaScript API 4.0** for geographic maps:

- Check dams: analysis-region selection and result-map basemaps. Rectangle selection, polygon drawing and vertex editing use WGS84 coordinates.
- Emergency response: geographic basemap, region selection and impact overlays. Before/after imagery and the comparison slider display the case's synthetic source imagery.
- Observatory: an interactive geographic map shows the three stations' configured demonstration locations. Sky selection and source/colour plots retain ICRS celestial coordinates; geographic maps do not represent the sky.

The shared browser loader is `web/assets/tianditu.js`. Set its browser-application Key for the deployment, and configure permitted domains in the Tianditu console. Browser Keys are visible to visitors. Visitors need network access to the Tianditu API and tile services. The UI reports SDK loading failures; it does not substitute another map provider. CONET does not proxy or cache geographic tiles.

Case inputs and generated disaster features are synthetic. The station positions are illustrative configuration values, not verified observatory sites. The basemap does not validate scientific results.

PNG downloads from a geographic-basemap view contain the **analysis overlay only**, labeled accordingly; Tianditu tiles are not included. Synthetic imagery/terrain views can be exported with their local imagery. Use the browser's screenshot or printing tools to capture the full geographic map.

Local entry points:

- <http://10.0.82.103:8870/apps/check-dams/>
- <http://10.0.82.103:8870/apps/observatory/>
- <http://10.0.82.103:8870/apps/emergency/>

Restart the local Web processes after changing the loader or backend routes:

```bash
bin/conet restart-web
```

This does not deploy remote websites or restart the data nodes.

Opening or refreshing a case does not automatically restore a historical analysis; results remain empty. A new submission displays its results when complete. Users may explicitly select a historical job to inspect its results. Starting another analysis clears the previous result to avoid confusing outputs.
