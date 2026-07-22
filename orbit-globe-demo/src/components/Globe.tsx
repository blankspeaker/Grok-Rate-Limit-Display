import { useState, useMemo, useEffect } from "react";
import * as THREE from "three";
import { EARTH_RADIUS } from "../lib/geo";

const DAY_URL =
  "https://unpkg.com/three-globe@2.31.1/example/img/earth-blue-marble.jpg";
const BUMP_URL =
  "https://unpkg.com/three-globe@2.31.1/example/img/earth-topology.png";
const CLOUDS_URL =
  "https://unpkg.com/three-globe@2.31.1/example/img/earth-clouds.png";

function FallbackEarth() {
  return (
    <mesh>
      <sphereGeometry args={[EARTH_RADIUS, 64, 64]} />
      <meshStandardMaterial
        color="#1a6b8a"
        roughness={0.85}
        metalness={0.05}
        emissive="#0a2030"
        emissiveIntensity={0.18}
      />
    </mesh>
  );
}

function TexturedEarth({
  dayMap,
  bumpMap,
}: {
  dayMap: THREE.Texture;
  bumpMap: THREE.Texture | null;
}) {
  return (
    <mesh>
      <sphereGeometry args={[EARTH_RADIUS, 64, 64]} />
      <meshStandardMaterial
        map={dayMap}
        bumpMap={bumpMap ?? undefined}
        bumpScale={0.045}
        roughness={0.88}
        metalness={0.04}
      />
    </mesh>
  );
}

function Clouds({ map }: { map: THREE.Texture }) {
  return (
    <mesh scale={1.01}>
      <sphereGeometry args={[EARTH_RADIUS, 48, 48]} />
      <meshStandardMaterial
        map={map}
        transparent
        opacity={0.28}
        depthWrite={false}
        roughness={1}
        metalness={0}
      />
    </mesh>
  );
}

function Atmosphere() {
  const mat = useMemo(
    () =>
      new THREE.ShaderMaterial({
        side: THREE.BackSide,
        transparent: true,
        depthWrite: false,
        blending: THREE.AdditiveBlending,
        uniforms: {
          glowColor: { value: new THREE.Color("#38bdf8") },
          coeficient: { value: 0.55 },
          power: { value: 3.2 },
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
            gl_FragColor = vec4(glowColor, clamp(intensity * 0.65, 0.0, 0.85));
          }
        `,
      }),
    []
  );

  return (
    <mesh scale={1.14}>
      <sphereGeometry args={[EARTH_RADIUS, 48, 48]} />
      <primitive object={mat} attach="material" />
    </mesh>
  );
}

function useEarthTextures() {
  const [dayMap, setDayMap] = useState<THREE.Texture | null>(null);
  const [bumpMap, setBumpMap] = useState<THREE.Texture | null>(null);
  const [cloudMap, setCloudMap] = useState<THREE.Texture | null>(null);
  const [failed, setFailed] = useState(false);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    const loader = new THREE.TextureLoader();
    loader.setCrossOrigin("anonymous");

    const load = (url: string) =>
      new Promise<THREE.Texture>((resolve, reject) => {
        loader.load(url, resolve, undefined, reject);
      });

    Promise.all([load(DAY_URL), load(BUMP_URL)])
      .then(async ([day, bump]) => {
        if (cancelled) return;
        day.colorSpace = THREE.SRGBColorSpace;
        day.anisotropy = 8;
        bump.anisotropy = 4;
        setDayMap(day);
        setBumpMap(bump);
        setLoading(false);
        try {
          const clouds = await load(CLOUDS_URL);
          if (!cancelled) {
            clouds.colorSpace = THREE.SRGBColorSpace;
            setCloudMap(clouds);
          }
        } catch {
          /* clouds optional */
        }
      })
      .catch(() => {
        if (cancelled) return;
        loader.load(
          DAY_URL,
          (day) => {
            if (cancelled) return;
            day.colorSpace = THREE.SRGBColorSpace;
            day.anisotropy = 8;
            setDayMap(day);
            setLoading(false);
          },
          undefined,
          () => {
            if (!cancelled) {
              setFailed(true);
              setLoading(false);
            }
          }
        );
      });

    return () => {
      cancelled = true;
    };
  }, []);

  return { dayMap, bumpMap, cloudMap, failed, loading };
}

/** Earth is static in world space; OrbitControls rotates the camera. */
export function GlobeSafe() {
  const { dayMap, bumpMap, cloudMap, failed, loading } = useEarthTextures();

  return (
    <group>
      {failed || (!loading && !dayMap) ? (
        <FallbackEarth />
      ) : dayMap ? (
        <TexturedEarth dayMap={dayMap} bumpMap={bumpMap} />
      ) : (
        <FallbackEarth />
      )}
      {cloudMap ? <Clouds map={cloudMap} /> : null}
      <Atmosphere />
    </group>
  );
}

export { GlobeSafe as Globe };
