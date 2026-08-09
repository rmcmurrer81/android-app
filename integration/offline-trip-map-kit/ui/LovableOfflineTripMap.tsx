import React, { useEffect, useRef } from "react";
import "./sarah-offline-trip-map.js";

type OfflineTripBundle = Record<string, unknown>;

type Props = {
  bundle: OfflineTripBundle;
  onMapDownloadRequested?: (detail: unknown) => void;
  onPlaceSelected?: (detail: unknown) => void;
  onNoteAdded?: (detail: unknown) => void;
};

/** Thin React/Lovable wrapper around the dependency-free custom element. */
export function LovableOfflineTripMap({
  bundle,
  onMapDownloadRequested,
  onPlaceSelected,
  onNoteAdded
}: Props) {
  const hostRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;
    const element = document.createElement("sarah-offline-trip-map") as HTMLElement & {
      bundle: OfflineTripBundle;
    };
    element.bundle = bundle;

    const mapHandler = (event: Event) => onMapDownloadRequested?.((event as CustomEvent).detail);
    const placeHandler = (event: Event) => onPlaceSelected?.((event as CustomEvent).detail);
    const noteHandler = (event: Event) => onNoteAdded?.((event as CustomEvent).detail);
    element.addEventListener("sarah-map-download-requested", mapHandler);
    element.addEventListener("sarah-place-selected", placeHandler);
    element.addEventListener("sarah-note-added", noteHandler);
    host.replaceChildren(element);

    return () => {
      element.removeEventListener("sarah-map-download-requested", mapHandler);
      element.removeEventListener("sarah-place-selected", placeHandler);
      element.removeEventListener("sarah-note-added", noteHandler);
      host.replaceChildren();
    };
  }, [bundle, onMapDownloadRequested, onPlaceSelected, onNoteAdded]);

  return <div ref={hostRef} />;
}
