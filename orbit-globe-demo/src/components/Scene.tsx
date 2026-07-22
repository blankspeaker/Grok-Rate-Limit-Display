import { Suspense, useRef, useEffect, useCallback } from "react";
import { Canvas, useThree, useFrame } from "@react-three/fiber";
import { OrbitControls, Stars } from "@react-three/drei";
import * as THREE from "three";
import { GlobeSafe } from "./Globe";
import { Markers } from "./Markers";
import type { Location } from "../data/locations";
import { latLngToVector3, EARTH_RADIUS } from "../lib/geo";

/** Minimal OrbitControls surface we use */
type ControlsHandle = {
  autoRotate: boolean;
  target: THREE.Vector3;
  update: () => void;
};

interface SceneProps {
  selectedId: string | null;
  focusLocation: Location | null;
  onSelect: (loc: Location) => void;
  onReady: () => void;
}

/** Pauses autoRotate while user interacts; resumes after idle */
function ControlsManager({
  focusLocation,
}: {
  focusLocation: Location | null;
}) {
  const controlsRef = useRef<ControlsHandle | null>(null);
  const resumeTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const animating = useRef(false);
  const targetPos = useRef(new THREE.Vector3());
  const targetCam = useRef(new THREE.Vector3());
  const { camera } = useThree();

  const pauseAutoRotate = useCallback(() => {
    const c = controlsRef.current;
    if (!c) return;
    c.autoRotate = false;
    if (resumeTimer.current) clearTimeout(resumeTimer.current);
  }, []);

  const scheduleResume = useCallback(() => {
    if (resumeTimer.current) clearTimeout(resumeTimer.current);
    resumeTimer.current = setTimeout(() => {
      if (controlsRef.current && !animating.current) {
        controlsRef.current.autoRotate = true;
      }
    }, 2000);
  }, []);

  useEffect(() => {
    if (!focusLocation || !controlsRef.current) return;

    const surface = latLngToVector3(
      focusLocation.lat,
      focusLocation.lng,
      EARTH_RADIUS
    );
    targetPos.current.copy(surface);

    const dir = surface.clone().normalize();
    const camDist = 4.2;
    targetCam.current.copy(dir.multiplyScalar(camDist));
    targetCam.current.y += 0.35;

    animating.current = true;
    pauseAutoRotate();

    return () => {
      animating.current = false;
    };
  }, [focusLocation, pauseAutoRotate]);

  useFrame(() => {
    const c = controlsRef.current;
    if (!c || !animating.current) return;

    camera.position.lerp(targetCam.current, 0.045);
    c.target.lerp(targetPos.current, 0.055);
    c.update();

    const camDone = camera.position.distanceTo(targetCam.current) < 0.04;
    const targetDone = c.target.distanceTo(targetPos.current) < 0.02;

    if (camDone && targetDone) {
      animating.current = false;
      scheduleResume();
    }
  });

  return (
    <OrbitControls
      ref={controlsRef as React.RefObject<never>}
      enableDamping
      dampingFactor={0.08}
      autoRotate
      autoRotateSpeed={0.4}
      enablePan={false}
      minDistance={2.6}
      maxDistance={8}
      minPolarAngle={0.35}
      maxPolarAngle={Math.PI - 0.35}
      rotateSpeed={0.55}
      zoomSpeed={0.7}
      onStart={() => {
        animating.current = false;
        pauseAutoRotate();
      }}
      onEnd={scheduleResume}
    />
  );
}

function Lights() {
  return (
    <>
      <ambientLight intensity={0.35} color="#a8c4e0" />
      <directionalLight
        position={[5, 3, 5]}
        intensity={1.35}
        color="#fff5e6"
        castShadow={false}
      />
      <directionalLight
        position={[-4, -1, -3]}
        intensity={0.25}
        color="#6090c0"
      />
      <hemisphereLight args={["#b0c8e8", "#0a1520", 0.35]} />
    </>
  );
}

function ReadySignal({ onReady }: { onReady: () => void }) {
  useEffect(() => {
    const t = setTimeout(onReady, 400);
    return () => clearTimeout(t);
  }, [onReady]);
  return null;
}

export function Scene({
  selectedId,
  focusLocation,
  onSelect,
  onReady,
}: SceneProps) {
  return (
    <div className="canvas-layer">
      <Canvas
        camera={{
          position: [0, 0.6, 5.2],
          fov: 42,
          near: 0.1,
          far: 200,
        }}
        dpr={[1, 1.75]}
        gl={{
          antialias: true,
          alpha: true,
          powerPreference: "high-performance",
          toneMapping: THREE.ACESFilmicToneMapping,
          toneMappingExposure: 1.05,
        }}
        onCreated={({ gl }) => {
          gl.setClearColor("#060a12", 0);
        }}
      >
        <Suspense fallback={null}>
          <Lights />
          <Stars
            radius={80}
            depth={40}
            count={2800}
            factor={2.8}
            saturation={0}
            fade
            speed={0.3}
          />
          <GlobeSafe />
          <Markers selectedId={selectedId} onSelect={onSelect} />
          <ControlsManager focusLocation={focusLocation} />
          <ReadySignal onReady={onReady} />
        </Suspense>
      </Canvas>
    </div>
  );
}
