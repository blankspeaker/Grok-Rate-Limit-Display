import { useState, useCallback } from "react";
import { Scene } from "./components/Scene";
import { LocationsPanel } from "./components/LocationsPanel";
import type { Location } from "./data/locations";

export default function App() {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [focusLocation, setFocusLocation] = useState<Location | null>(null);
  const [ready, setReady] = useState(false);

  const handleSelect = useCallback((loc: Location) => {
    setSelectedId(loc.id);
    setFocusLocation({ ...loc });
  }, []);

  return (
    <div className="app-shell">
      <div className={`loading${ready ? " hidden" : ""}`} aria-live="polite">
        <span className="loading-text">Loading orbit</span>
      </div>

      <Scene
        selectedId={selectedId}
        focusLocation={focusLocation}
        onSelect={handleSelect}
        onReady={() => setReady(true)}
      />

      <div className="brand">
        <div className="brand-mark">Explorer</div>
        <div className="brand-title">ORBIT</div>
        <div className="brand-sub">3D Earth · featured cities</div>
      </div>

      <div className="hint">Drag to spin · Click a marker</div>

      <LocationsPanel selectedId={selectedId} onSelect={handleSelect} />
    </div>
  );
}
