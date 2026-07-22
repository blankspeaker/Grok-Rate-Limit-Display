# ORBIT — Rotating 3D Globe

Demo-quality interactive Earth globe built with **React 19**, **TypeScript**, **Vite**, **Three.js**, and **@react-three/fiber**.

## Features

- Full-viewport WebGL Earth with NASA Blue Marble day texture + topology bump
- Fresnel-style atmosphere glow shell
- Soft ambient + directional sun lighting, starfield background
- 10 featured city markers with pulse glow
- Drag to spin (OrbitControls + damping)
- Auto-rotate that pauses while interacting, resumes after ~2s idle
- Click marker or list item → smooth camera focus on that city
- Glass UI side panel (dark atmospheric aesthetic, sky-cyan accent only)
- Mobile bottom-sheet panel

## Quick start

```bash
cd orbit-globe-demo
npm install
npm run dev
```

App listens on **http://0.0.0.0:8080**

Idempotent startup:

```bash
chmod +x startup.sh
./startup.sh
```

## Scripts

| Command | Description |
|---------|-------------|
| `npm run dev` | Dev server on 0.0.0.0:8080 |
| `npm run build` | Production build |
| `npm run typecheck` | TypeScript check |
| `npm run preview` | Preview production build |

## Stack

- React 19 + TypeScript
- Vite 6
- three + @react-three/fiber + @react-three/drei
- Google fonts: Outfit + JetBrains Mono

## Textures

- Day: `https://unpkg.com/three-globe@2.31.1/example/img/earth-blue-marble.jpg`
- Bump: `https://unpkg.com/three-globe@2.31.1/example/img/earth-topology.png`

Falls back to procedural blue-green material if textures fail (CORS/network).

## Layout

```
src/
  App.tsx
  main.tsx
  index.css
  components/
    Globe.tsx      # Earth + atmosphere
    Markers.tsx    # City pins + pulse
    Scene.tsx      # Canvas, lights, controls, focus
    LocationsPanel.tsx
  data/locations.ts
  lib/geo.ts       # lat/lng → XYZ
```

## Design tokens

- Void bg: `#060a12`
- Glass: `rgba(12, 18, 32, 0.58)` + blur
- Text: `#e8eef8` / muted `#9aa8c0`
- Accent: `#38bdf8` (sky cyan only)
