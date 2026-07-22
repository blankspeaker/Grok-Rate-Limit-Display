import { LOCATIONS, type Location } from "../data/locations";
import { formatCoords } from "../lib/geo";

interface LocationsPanelProps {
  selectedId: string | null;
  onSelect: (loc: Location) => void;
}

export function LocationsPanel({ selectedId, onSelect }: LocationsPanelProps) {
  return (
    <aside className="panel" aria-label="Featured locations">
      <div className="panel-header">
        <h2>Featured</h2>
        <p>Select a city to focus the globe</p>
      </div>

      <div className="panel-list" role="listbox" aria-label="Cities">
        {LOCATIONS.map((loc) => {
          const selected = selectedId === loc.id;
          return (
            <button
              key={loc.id}
              type="button"
              role="option"
              aria-selected={selected}
              className={`location-row${selected ? " selected" : ""}`}
              onClick={() => onSelect(loc)}
            >
              <span className="location-name">
                {loc.name}
                <span style={{ color: "var(--muted)", fontWeight: 300 }}>
                  {" · "}{loc.country}
                </span>
              </span>
              <span className="location-coords">
                {formatCoords(loc.lat, loc.lng)}
              </span>
              <span className="location-desc">{loc.description}</span>
            </button>
          );
        })}
      </div>

      <div className="panel-footer">
        {LOCATIONS.length} destinations · ORBIT
      </div>
    </aside>
  );
}
