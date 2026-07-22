import { useRef, useMemo } from "react";
import { useFrame } from "@react-three/fiber";
import * as THREE from "three";
import { LOCATIONS, type Location } from "../data/locations";
import { latLngToVector3, EARTH_RADIUS } from "../lib/geo";

interface MarkersProps {
  selectedId: string | null;
  onSelect: (loc: Location) => void;
}

function Marker({
  location,
  selected,
  onSelect,
}: {
  location: Location;
  selected: boolean;
  onSelect: () => void;
}) {
  const glow = useRef<THREE.Mesh>(null);
  const pos = useMemo(
    () => latLngToVector3(location.lat, location.lng, EARTH_RADIUS + 0.02),
    [location.lat, location.lng]
  );
  const phase = useMemo(() => location.lat * 0.1 + location.lng * 0.05, [location]);

  useFrame(({ clock }) => {
    if (!glow.current) return;
    const t = clock.getElapsedTime();
    const pulse = 1 + Math.sin(t * 2.4 + phase) * 0.35;
    glow.current.scale.setScalar(selected ? pulse * 1.45 : pulse);
    const mat = glow.current.material as THREE.MeshBasicMaterial;
    mat.opacity = selected
      ? 0.38 + Math.sin(t * 2.4) * 0.15
      : 0.18 + Math.sin(t * 2.4 + phase) * 0.08;
  });

  return (
    <group
      position={pos}
      onClick={(e) => {
        e.stopPropagation();
        onSelect();
      }}
      onPointerOver={() => {
        document.body.style.cursor = "pointer";
      }}
      onPointerOut={() => {
        document.body.style.cursor = "auto";
      }}
    >
      {/* Soft pulse glow */}
      <mesh ref={glow} scale={1.2}>
        <sphereGeometry args={[0.055, 16, 16]} />
        <meshBasicMaterial
          color="#38bdf8"
          transparent
          opacity={0.25}
          depthWrite={false}
          toneMapped={false}
        />
      </mesh>

      {/* Core pin */}
      <mesh>
        <sphereGeometry args={[selected ? 0.028 : 0.02, 16, 16]} />
        <meshStandardMaterial
          color={selected ? "#7dd3fc" : "#38bdf8"}
          emissive="#38bdf8"
          emissiveIntensity={selected ? 2.4 : 1.3}
          toneMapped={false}
        />
      </mesh>

      {selected && (
        <pointLight color="#38bdf8" intensity={0.55} distance={1.4} decay={2} />
      )}
    </group>
  );
}

export function Markers({ selectedId, onSelect }: MarkersProps) {
  return (
    <group>
      {LOCATIONS.map((loc) => (
        <Marker
          key={loc.id}
          location={loc}
          selected={selectedId === loc.id}
          onSelect={() => onSelect(loc)}
        />
      ))}
    </group>
  );
}
