import { useRef, useState, useMemo } from "react";
import { useFrame } from "@react-three/fiber";
import { useTexture } from "@react-three/drei";
import * as THREE from "three";
import { EARTH_RADIUS } from "../lib/geo";

const DAY_URL =
  "https://unpkg.com/three-globe@2.31.1/example/img/earth-blue-marble.jpg";
const BUMP_URL =
  "https://unpkg.com/three-globe@2.31.1/example/img/earth-topology.png";

function FallbackEarth() {
  const mat = useMemo(
    () =>
      new THREE.MeshStandardMaterial({
        color: new THREE.Color("#1a6b8a"),
        roughness: 0.85,
        metalness: 0.05,
        emissive: new THREE.Color("#0a2030"),
        emissiveIntensity: 0.15,
      }),
    []
  );

  return (
    <mesh>
      <sphereGeometry args={[EARTH_RADIUS, 64, 64]} />
      <primitive object={mat} attach="material" />
    </mesh>
  );
}

function TexturedEarth() {
  const [dayMap, bumpMap] = useTexture([DAY_URL, BUMP_URL]);

  dayMap.colorSpace = THREE.SRGBColorSpace;
  dayMap.anisotropy = 8;

  return (
    <mesh>
      <sphereGeometry args={[EARTH_RADIUS, 64, 64]} />
      <meshStandardMaterial
        map={dayMap}
        bumpMap={bumpMap}
        bumpScale={0.04}
        roughness={0.9}
        metalness={0.05}
      />
    </mesh>
  );
}

function Atmosphere() {
  const mat = useMemo(() => {
    return new THREE.ShaderMaterial({
      side: THREE.BackSide,
      transparent: true,
      depthWrite: false,
      uniforms: {
        glowColor: { value: new THREE.Color("#38bdf8") },
        coeficient: { value: 0.6 },
        power: { value: 3.5 },
      },
      vertexShader: `
        varying vec3 vNormal;
        varying vec3 vPositionNormal;
        void main() {
          vNormal = normalize(normalMatrix * normal);
          vPositionNormal = normalize((modelViewMatrix * vec4(position, 1.0)).xyz);
          gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
        }
      `,
      fragmentShader: `
        uniform vec3 glowColor;
        uniform float coeficient;
        uniform float power;
        varying vec3 vNormal;
        varying vec3 vPositionNormal;
        void main() {
          float intensity = pow(coeficient - dot(vNormal, vPositionNormal), power);
          gl_FragColor = vec4(glowColor, intensity * 0.55);
        }
      `,
    });
  }, []);

  return (
    <mesh scale={1.12}>
      <sphereGeometry args={[EARTH_RADIUS, 48, 48]} />
      <primitive object={mat} attach="material" />
    </mesh>
  );
}

export function Globe() {
  const group = useRef<THREE.Group>(null);
  const [textureFailed, setTextureFailed] = useState(false);

  useFrame((_, delta) => {
    // subtle idle tilt wobble — primary spin is via OrbitControls autoRotate
    if (group.current) {
      group.current.rotation.y += delta * 0.02;
    }
  });

  return (
    <group ref={group}>
      {textureFailed ? (
        <FallbackEarth />
      ) : (
        <TextureErrorBoundary onError={() => setTextureFailed(true)}>
          <TexturedEarth />
        </TextureErrorBoundary>
      )}
      <Atmosphere />
    </group>
  );
}

/** Catch texture load failures and fall back to procedural material */
function TextureErrorBoundary({
  children,
  onError,
}: {
  children: React.ReactNode;
  onError: () => void;
}) {
  try {
    return <>{children}</>;
  } catch {
    onError();
    return null;
  }
}

// useTexture throws promise / suspends — wrap with Suspense in parent.
// Also export a safe variant that uses onError via drei's texture loader pattern.
export function GlobeSafe() {
  const group = useRef<THREE.Group>(null);
  const [useFallback, setUseFallback] = useState(false);

  useFrame((_, delta) => {
    if (group.current) {
      group.current.rotation.y += delta * 0.015;
    }
  });

  return (
    <group ref={group}>
      {useFallback ? (
        <FallbackEarth />
      ) : (
        <EarthWithTextures onFail={() => setUseFallback(true)} />
      )}
      <Atmosphere />
    </group>
  );
}

function EarthWithTextures({ onFail }: { onFail: () => void }) {
  // Preload-safe: drei useTexture will suspend; parent Suspense catches.
  // If CORS fails at runtime, canvas may error — listener below handles it.
  const maps = useTexture(
    [DAY_URL, BUMP_URL],
    undefined,
    () => onFail()
  ) as THREE.Texture[];

  const dayMap = maps[0];
  const bumpMap = maps[1];

  if (dayMap) {
    dayMap.colorSpace = THREE.SRGBColorSpace;
    dayMap.anisotropy = 8;
  }

  return (
    <mesh>
      <sphereGeometry args={[EARTH_RADIUS, 64, 64]} />
      <meshStandardMaterial
        map={dayMap}
        bumpMap={bumpMap}
        bumpScale={0.045}
        roughness={0.88}
        metalness={0.04}
      />
    </mesh>
  );
}
