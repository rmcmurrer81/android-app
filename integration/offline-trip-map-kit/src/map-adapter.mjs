/** Provider-neutral helpers for full street-map downloads and the built-in schematic fallback. */

const PROHIBITED_PUBLIC_OFFLINE_HOSTS = new Set([
  "tile.openstreetmap.org",
  "vector.openstreetmap.org"
]);

export function assertOfflineTilePolicy({ tileSource = "", offlineAllowed = false } = {}) {
  let host = "";
  try { host = new URL(tileSource).host.toLowerCase(); } catch { host = ""; }
  if (PROHIBITED_PUBLIC_OFFLINE_HOSTS.has(host)) {
    throw new Error(`${host} must not be bulk-downloaded for offline use; choose a provider that explicitly permits offline packages or self-host lawful tiles.`);
  }
  if (!offlineAllowed) {
    throw new Error("The selected map source has not been marked as permitting offline downloads.");
  }
  return true;
}

export function buildMapLibreRegionDefinition(bundle, {
  styleUrl,
  pixelRatio = 1,
  includeIdeographs = true
} = {}) {
  const request = bundle?.mapPackageRequest;
  if (!request?.bounds) throw new Error("Trip bundle does not have map bounds");
  if (!styleUrl && !request.styleUrl) throw new Error("A style URL or local style path is required");
  return {
    styleURL: styleUrl || request.styleUrl,
    bounds: request.bounds,
    minZoom: request.minZoom,
    maxZoom: request.maxZoom,
    pixelRatio,
    includeIdeographs,
    metadata: {
      schema: bundle.schema,
      tripId: bundle.tripId,
      personScopeId: bundle.personScopeId,
      title: bundle.title
    }
  };
}

export function buildPmtilesManifest(bundle, {
  archiveUrlOrPath,
  stylePath = "",
  attribution = "© OpenStreetMap contributors"
} = {}) {
  if (!bundle?.mapPackageRequest?.bounds) throw new Error("Trip bundle does not have map bounds");
  if (!archiveUrlOrPath) throw new Error("A PMTiles archive URL or local path is required");
  return {
    type: "pmtiles",
    archive: archiveUrlOrPath,
    stylePath,
    bounds: bundle.mapPackageRequest.bounds,
    minZoom: bundle.mapPackageRequest.minZoom,
    maxZoom: bundle.mapPackageRequest.maxZoom,
    attribution,
    tripId: bundle.tripId,
    personScopeId: bundle.personScopeId
  };
}

export function projectPoint(point, bounds, width = 1000, height = 600, padding = 48) {
  if (!bounds || !Number.isFinite(Number(point?.latitude)) || !Number.isFinite(Number(point?.longitude))) return null;
  const xSpan = Math.max(0.000001, bounds.east - bounds.west);
  const ySpan = Math.max(0.000001, bounds.north - bounds.south);
  const x = padding + ((Number(point.longitude) - bounds.west) / xSpan) * (width - padding * 2);
  const y = padding + ((bounds.north - Number(point.latitude)) / ySpan) * (height - padding * 2);
  return { x, y };
}

export function createSchematicRoute(anchors, bounds, width = 1000, height = 600) {
  return (anchors || [])
    .filter((row) => Number.isFinite(Number(row.latitude)) && Number.isFinite(Number(row.longitude)))
    .map((row) => ({ ...row, ...projectPoint(row, bounds, width, height) }));
}
