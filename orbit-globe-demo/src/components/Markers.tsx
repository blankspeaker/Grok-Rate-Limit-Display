import { useRef } from "react";
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
  const group = useRef<THREE.Group>(null);
  const glow = useRef<THREE.Mesh>(null);
  const pos = latLngToVector3(location.lat, location.lng, EARTH_RADIUS + 0.02);

  useFrame(({ clock }) => {
    if (glow.current) {
      const t = clock.getElapsedTime();
      const pulse = 1 + Math.sin(t * 2.4 + location.lat) * 0.35;
      glow.current.scale.setScalar(selected ? pulse * 1.4 : pulse);
      const mat = glow.current.material as THREE.MeshBasicMaterial;
      mat.opacity = selected
        ? 0.35 + Math.sin(t * 2.4) * 0.15
        : 0.18 + Math.sin(t * 2.4 + location.lat) * 0.08;
    }
  });

  // Orient marker to face outward from globe center
  const up = pos.clone().normalize();

  return (
    <group
      ref={group}
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
        <sphereGeometry args={[0.06, 16, 16]} />
        <meshBasicMaterial
          color="#38bdf8"
          transparent
          opacity={0.25}
          depthWrite={false}
        />
      </mesh>

      {/* Core pin */}
      <mesh>
        <sphereGeometry args={[selected ? 0.028 : 0.02, 16, 16]} />
        <meshStandardMaterial
          color={selected ? "#7dd3fc" : "#38bdf8"}
          emissive="#38bdf8"
          emissiveIntensity={selected ? 2.2 : 1.2}
          toneMapped={false}
        />
      </mesh>

      {/* Small stem pointing outward */}
      <mesh position={up.clone().multiplyScalar(0.035)}>
        <cylinderGeometry args={[0.004, 0.006, 0.05, 8]} />
        <meshStandardMaterial
          color="#38bdf8"
          emissive="#0ea5e9"
          emissiveIntensity={0.8}
          toneMapped={false}
        />
      </mesh>

      {/* Point light for selected */}
      {selected && (
        <pointLight color="#38bdf8" intensity={0.6} distance={1.5} decay={2} />
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
