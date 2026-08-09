import { OFFLINE_TRIP_MAP_SCHEMA, addPersonalNote } from "./offline-trip-map-kit.mjs";

/**
 * Stores trip JSON, notes, freshness state, and map-package references offline.
 * Map tiles themselves remain inside the selected map SDK/provider's storage.
 */
export class OfflineTripStore {
  constructor({
    dbName = "sarah-offline-trip-maps",
    storeName = "trip-bundles",
    indexedDBImpl = globalThis.indexedDB
  } = {}) {
    this.dbName = dbName;
    this.storeName = storeName;
    this.indexedDB = indexedDBImpl;
    this.memory = new Map();
    this.dbPromise = null;
  }

  async saveBundle(bundle) {
    validateBundle(bundle);
    const copy = clone(bundle);
    if (!this.indexedDB) {
      this.memory.set(key(bundle.personScopeId, bundle.tripId), copy);
      return copy;
    }
    const db = await this.open();
    await transactionPromise(db, this.storeName, "readwrite", (store) => store.put({
      key: key(bundle.personScopeId, bundle.tripId),
      personScopeId: bundle.personScopeId,
      tripId: bundle.tripId,
      updatedAt: Date.now(),
      bundle: copy
    }));
    return copy;
  }

  async loadBundle(personScopeId, tripId) {
    const recordKey = key(personScopeId, tripId);
    if (!this.indexedDB) return clone(this.memory.get(recordKey) || null);
    const db = await this.open();
    const row = await transactionPromise(db, this.storeName, "readonly", (store) => store.get(recordKey));
    return clone(row?.bundle || null);
  }

  async listBundles(personScopeId) {
    if (!this.indexedDB) {
      return [...this.memory.values()]
        .filter((bundle) => bundle.personScopeId === personScopeId)
        .map(clone)
        .sort((a, b) => String(a.startDate).localeCompare(String(b.startDate)));
    }
    const db = await this.open();
    const rows = await transactionPromise(db, this.storeName, "readonly", (store) => store.getAll());
    return rows
      .filter((row) => row.personScopeId === personScopeId)
      .map((row) => clone(row.bundle))
      .sort((a, b) => String(a.startDate).localeCompare(String(b.startDate)));
  }

  async addNote(personScopeId, tripId, note) {
    const bundle = await this.loadBundle(personScopeId, tripId);
    if (!bundle) throw new Error("Trip bundle not found");
    const next = addPersonalNote(bundle, note);
    await this.saveBundle(next);
    return next;
  }

  async deleteBundle(personScopeId, tripId) {
    const recordKey = key(personScopeId, tripId);
    if (!this.indexedDB) return this.memory.delete(recordKey);
    const db = await this.open();
    await transactionPromise(db, this.storeName, "readwrite", (store) => store.delete(recordKey));
    return true;
  }

  async open() {
    if (!this.indexedDB) return null;
    if (this.dbPromise) return this.dbPromise;
    this.dbPromise = new Promise((resolve, reject) => {
      const request = this.indexedDB.open(this.dbName, 1);
      request.onupgradeneeded = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains(this.storeName)) {
          const store = db.createObjectStore(this.storeName, { keyPath: "key" });
          store.createIndex("personScopeId", "personScopeId", { unique: false });
          store.createIndex("updatedAt", "updatedAt", { unique: false });
        }
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error || new Error("Unable to open offline trip database"));
    });
    return this.dbPromise;
  }
}

function validateBundle(bundle) {
  if (!bundle || bundle.schema !== OFFLINE_TRIP_MAP_SCHEMA) throw new Error("Unsupported offline trip bundle");
  if (!bundle.personScopeId || !bundle.tripId) throw new Error("Bundle must include personScopeId and tripId");
}

function key(personScopeId, tripId) {
  const person = String(personScopeId || "").trim();
  const trip = String(tripId || "").trim();
  if (!person || !trip) throw new Error("personScopeId and tripId are required");
  return `${person}::${trip}`;
}

function transactionPromise(db, storeName, mode, operation) {
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(storeName, mode);
    const store = transaction.objectStore(storeName);
    let request;
    try {
      request = operation(store);
    } catch (error) {
      reject(error);
      return;
    }
    transaction.oncomplete = () => resolve(request?.result);
    transaction.onerror = () => reject(transaction.error || request?.error || new Error("Offline trip database transaction failed"));
    transaction.onabort = () => reject(transaction.error || new Error("Offline trip database transaction was aborted"));
  });
}

function clone(value) {
  if (value == null) return value;
  return typeof structuredClone === "function" ? structuredClone(value) : JSON.parse(JSON.stringify(value));
}
