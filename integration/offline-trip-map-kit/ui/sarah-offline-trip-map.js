import { calculateOfflineReadiness } from "../src/offline-trip-map-kit.mjs";
import { createSchematicRoute } from "../src/map-adapter.mjs";

/**
 * Dependency-free custom element. It always provides a schematic offline map;
 * a host can mount MapLibre/PMTiles/another lawful full map into the map slot.
 */
export class SarahOfflineTripMap extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({ mode: "open" });
    this._bundle = null;
    this._activeAnchorId = "";
  }

  set bundle(value) {
    this._bundle = value || null;
    this.render();
  }

  get bundle() {
    return this._bundle;
  }

  connectedCallback() {
    this.render();
  }

  render() {
    if (!this.shadowRoot) return;
    if (!this._bundle) {
      this.shadowRoot.innerHTML = `${styles()}<section class="empty"><strong>Offline Trip Map</strong><span>Set the element's <code>bundle</code> property to a sarah-offline-trip-map-v1 object.</span></section>`;
      return;
    }
    const bundle = this._bundle;
    const readiness = bundle.readiness || calculateOfflineReadiness(bundle);
    const route = createSchematicRoute(bundle.anchors, bundle.mapPackageRequest?.bounds, 1000, 600);
    const selected = bundle.anchors?.find((row) => row.id === this._activeAnchorId) || route[0] || null;

    this.shadowRoot.innerHTML = `${styles()}
      <main>
        <header>
          <div>
            <p class="eyebrow">AVAILABLE OFFLINE</p>
            <h1>${escapeHtml(bundle.title || "Trip")}</h1>
            <p>${escapeHtml([bundle.destination, dateRange(bundle)].filter(Boolean).join(" · "))}</p>
          </div>
          <div class="readiness" aria-label="Offline readiness ${readiness.score} percent">
            <strong>${readiness.score}%</strong><span>${escapeHtml(readiness.state.replaceAll("_", " "))}</span>
          </div>
        </header>

        <section class="layout">
          <article class="map-card">
            <div class="card-head">
              <div><h2>Trip map</h2><p>Works as a schematic offline map even before a street-map package is connected.</p></div>
              <button id="download-map" type="button">Download full map</button>
            </div>
            <div id="map-surface" part="map-surface" aria-label="Offline trip map">
              ${renderSvg(route, selected?.id)}
              <slot name="full-map"></slot>
            </div>
            ${selected ? renderSelected(selected) : ""}
          </article>

          <aside>
            <section class="panel"><h2>Bookings & trip facts</h2>${renderAnchorList(bundle.tripFacts || [], "No plane, hotel, ticket, or transport details have been added yet.")}</section>
            <section class="panel"><h2>Recommended for you</h2>${renderRecommendations(bundle.recommendations || [])}</section>
            <section class="panel"><h2>Personal notes</h2>${renderAnchorList(bundle.personalNotes || [], "No personal notes yet.")}
              <form id="note-form"><label for="note">Add an offline note</label><textarea id="note" maxlength="2000" placeholder="Example: Ask the hotel about an early breakfast."></textarea><button type="submit">Save note</button></form>
            </section>
          </aside>
        </section>

        <section class="status-grid">
          ${(readiness.checks || []).map((row) => `<div class="status ${row.ready ? "ok" : "warn"}"><span>${row.ready ? "✓" : "!"}</span><div><strong>${escapeHtml(row.label)}</strong><small>${row.ready ? "Ready offline" : row.optional ? "Optional" : "Needs attention"}</small></div></div>`).join("")}
        </section>
      </main>`;

    this.shadowRoot.querySelectorAll("[data-anchor-id]").forEach((element) => {
      element.addEventListener("click", () => {
        this._activeAnchorId = element.getAttribute("data-anchor-id") || "";
        const anchor = bundle.anchors?.find((row) => row.id === this._activeAnchorId) || null;
        this.dispatchEvent(new CustomEvent("sarah-place-selected", { detail: { anchor }, bubbles: true, composed: true }));
        this.render();
      });
    });

    this.shadowRoot.getElementById("download-map")?.addEventListener("click", () => {
      this.dispatchEvent(new CustomEvent("sarah-map-download-requested", {
        detail: { tripId: bundle.tripId, personScopeId: bundle.personScopeId, request: bundle.mapPackageRequest },
        bubbles: true,
        composed: true
      }));
    });

    this.shadowRoot.getElementById("note-form")?.addEventListener("submit", (event) => {
      event.preventDefault();
      const field = this.shadowRoot.getElementById("note");
      const body = String(field?.value || "").trim();
      if (!body) return;
      this.dispatchEvent(new CustomEvent("sarah-note-added", {
        detail: { tripId: bundle.tripId, personScopeId: bundle.personScopeId, note: { body } },
        bubbles: true,
        composed: true
      }));
      field.value = "";
    });
  }
}

if (!customElements.get("sarah-offline-trip-map")) {
  customElements.define("sarah-offline-trip-map", SarahOfflineTripMap);
}

function renderSvg(route, selectedId) {
  if (!route.length) return `<div class="no-map">Add coordinates to the hotel, airport, tickets, notes, or recommendations to create the offline map.</div>`;
  const polyline = route.map((row) => `${row.x},${row.y}`).join(" ");
  return `<svg viewBox="0 0 1000 600" role="img" aria-label="Schematic itinerary map">
    <defs><pattern id="grid" width="50" height="50" patternUnits="userSpaceOnUse"><path d="M 50 0 L 0 0 0 50" fill="none" stroke="currentColor" stroke-opacity=".08" stroke-width="1"/></pattern></defs>
    <rect width="1000" height="600" fill="url(#grid)"/>
    ${route.length > 1 ? `<polyline points="${polyline}" fill="none" stroke="currentColor" stroke-opacity=".38" stroke-width="8" stroke-linecap="round" stroke-linejoin="round"/>` : ""}
    ${route.map((row, index) => `<g class="pin ${row.id === selectedId ? "selected" : ""}" data-anchor-id="${escapeAttr(row.id)}" tabindex="0" role="button" aria-label="${escapeAttr(row.title)}">
      <circle cx="${row.x}" cy="${row.y}" r="${row.id === selectedId ? 20 : 15}"/>
      <text x="${row.x}" y="${row.y + 5}" text-anchor="middle">${index + 1}</text>
      <text class="pin-label" x="${row.x}" y="${row.y - 27}" text-anchor="middle">${escapeHtml(shorten(row.title, 30))}</text>
    </g>`).join("")}
  </svg>`;
}

function renderSelected(anchor) {
  const body = anchor.offlineBody || anchor.subtitle || "";
  return `<div class="selected-card"><span class="kind">${escapeHtml(anchor.kind)}</span><div><strong>${escapeHtml(anchor.title)}</strong><p>${escapeHtml(body)}</p>${anchor.checkedAt ? `<small>Last checked ${escapeHtml(anchor.checkedAt)}</small>` : ""}</div></div>`;
}

function renderAnchorList(rows, emptyText) {
  if (!rows.length) return `<p class="muted">${escapeHtml(emptyText)}</p>`;
  return `<div class="list">${rows.map((row) => `<button type="button" data-anchor-id="${escapeAttr(row.id)}"><span class="icon">${icon(row.kind)}</span><span><strong>${escapeHtml(row.title)}</strong><small>${escapeHtml(row.subtitle || row.startTime || row.offlineBody || "Saved offline")}</small></span></button>`).join("")}</div>`;
}

function renderRecommendations(rows) {
  if (!rows.length) return `<p class="muted">No personalized recommendations are saved yet.</p>`;
  return `<div class="list">${rows.slice(0, 8).map((row) => `<button type="button" data-anchor-id="place-${escapeAttr(row.id)}"><span class="score">${Math.round((row.score || 0) * 100)}</span><span><strong>${escapeHtml(row.title)}</strong><small>${escapeHtml((row.reasons || []).join(" · "))}</small></span></button>`).join("")}</div>`;
}

function styles() {
  return `<style>
    :host{display:block;font-family:Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;color:#101828;background:#f5f7fb}
    *{box-sizing:border-box} main{max-width:1480px;margin:auto;padding:24px} header{display:flex;justify-content:space-between;gap:24px;align-items:center;padding:24px;border-radius:24px;background:#fff;box-shadow:0 10px 35px rgba(16,24,40,.08);margin-bottom:20px} h1{font-size:clamp(28px,4vw,48px);margin:2px 0} h2{font-size:18px;margin:0 0 6px} p{margin:4px 0;line-height:1.45}.eyebrow{font-size:12px;font-weight:800;letter-spacing:.16em}.readiness{min-width:105px;aspect-ratio:1;border-radius:50%;display:grid;place-content:center;text-align:center;border:9px solid #12b76a;background:#ecfdf3}.readiness strong{font-size:26px}.readiness span{font-size:11px;text-transform:capitalize}.layout{display:grid;grid-template-columns:minmax(0,1.7fr) minmax(320px,.8fr);gap:20px}.map-card,.panel{background:#fff;border-radius:24px;box-shadow:0 10px 35px rgba(16,24,40,.07);overflow:hidden}.map-card{padding:18px}.card-head{display:flex;justify-content:space-between;gap:16px;align-items:center;margin-bottom:14px}.card-head p,.muted{color:#667085;font-size:14px}button{font:inherit}.card-head button,form button{border:0;background:#101828;color:white;padding:11px 15px;border-radius:12px;font-weight:700;cursor:pointer}#map-surface{min-height:440px;position:relative;border-radius:18px;overflow:hidden;background:linear-gradient(145deg,#eef2f6,#dce7ee);color:#344054}svg{display:block;width:100%;height:auto;min-height:440px}.pin{cursor:pointer;outline:none}.pin circle{fill:#175cd3;stroke:white;stroke-width:5;filter:drop-shadow(0 3px 5px rgba(16,24,40,.25))}.pin.selected circle{fill:#f79009}.pin text:not(.pin-label){fill:white;font-size:13px;font-weight:800}.pin-label{font-size:18px;font-weight:750;paint-order:stroke;stroke:white;stroke-width:7;stroke-linejoin:round;fill:#101828}.selected-card{display:flex;gap:12px;padding:14px 4px 0}.selected-card .kind{height:max-content;background:#eff4ff;color:#3538cd;padding:5px 9px;border-radius:999px;font-size:11px;font-weight:800;text-transform:uppercase}.selected-card small{color:#667085}aside{display:grid;gap:20px;align-content:start}.panel{padding:18px}.list{display:grid;gap:9px;margin-top:12px}.list button{display:grid;grid-template-columns:42px 1fr;gap:10px;text-align:left;align-items:start;width:100%;border:1px solid #eaecf0;background:#fff;border-radius:14px;padding:11px;cursor:pointer}.list button:hover{border-color:#84adff;background:#f5f8ff}.list strong,.list small{display:block}.list small{color:#667085;margin-top:3px;line-height:1.35}.icon,.score{display:grid;place-content:center;width:38px;height:38px;border-radius:12px;background:#f2f4f7;font-weight:800}.score{background:#ecfdf3;color:#067647}form{display:grid;gap:8px;margin-top:14px}form label{font-size:13px;font-weight:750}textarea{min-height:86px;resize:vertical;border:1px solid #d0d5dd;border-radius:12px;padding:11px;font:inherit}.status-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:10px;margin-top:20px}.status{display:flex;gap:10px;background:white;padding:13px;border-radius:14px;box-shadow:0 5px 18px rgba(16,24,40,.05)}.status>span{display:grid;place-content:center;width:28px;height:28px;border-radius:50%;font-weight:900}.status.ok>span{background:#dcfae6;color:#067647}.status.warn>span{background:#fef0c7;color:#b54708}.status small{display:block;color:#667085}.no-map,.empty{min-height:300px;display:grid;place-content:center;text-align:center;padding:30px;color:#667085}.empty{gap:8px;background:white;border-radius:18px;margin:24px}.empty strong{font-size:24px}
    @media(max-width:900px){main{padding:12px}.layout{grid-template-columns:1fr}header{align-items:flex-start}.readiness{min-width:86px}.card-head{align-items:flex-start;flex-direction:column}#map-surface,svg{min-height:320px}.pin-label{font-size:14px}}
  </style>`;
}

function icon(kind) {
  return ({ flight: "✈", airport: "🛫", hotel: "🏨", ticket: "🎟", transport: "🚆", note: "📝", recommendation: "★", emergency: "!" })[kind] || "•";
}

function dateRange(bundle) {
  if (bundle.startDate && bundle.endDate) return `${bundle.startDate}–${bundle.endDate}`;
  return bundle.startDate || bundle.endDate || "";
}

function shorten(value, length) {
  const text = String(value || "");
  return text.length > length ? `${text.slice(0, length - 1)}…` : text;
}

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>\"]/g, (character) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '\"': "&quot;" })[character]);
}
function escapeAttr(value) { return escapeHtml(value).replace(/'/g, "&#39;"); }
