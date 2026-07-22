# ORBIT — Rotating 3D Globe

Interactive full-viewport Earth with glowing city markers, drag-to-spin, click-to-focus, and a glass featured-locations panel.

## Play instantly (no install)

**Standalone single-file demo (Three.js CDN):**

https://gist.githack.com/blankspeaker/a998a389128e5cda6fd1b749001bddc0/raw/orbit-globe.html

Source gist: https://gist.github.com/blankspeaker/a998a389128e5cda6fd1b749001bddc0

## Features

- Full-viewport WebGL Earth (NASA Blue Marble + topology bump + optional clouds)
- Fresnel atmosphere glow shell (sky cyan)
- Soft ambient + directional sun + hemisphere lighting, starfield
- 10 featured city markers with pulse glow
- Drag to spin (OrbitControls + damping)
- Auto-rotate that **pauses while interacting**, resumes after ~2s idle
- Click marker **or** list item → smooth camera focus on that city
- Glass UI side panel (dark atmospheric aesthetic, single sky-cyan accent)
- Mobile bottom-sheet panel

## Featured cities

Tokyo · New York · London · Sydney · Cape Town · Rio de Janeiro · Dubai · Reykjavik · Singapore · San Francisco

## Dev server (Vite + React + R3F)

```bash
cd orbit-globe-demo
npm install
npm run dev
```

Listens on **http://0.0.0.0:8080**

```bash
chmod +x startup.sh && ./startup.sh
```

## Scripts

| Command | Description |
|---------|-------------|
| `npm run dev` | Dev server on 0.0.0.0:8080 |
| `npm run build` | Production build |
| `npm run typecheck` | TypeScript check |
| `npm run preview` | Preview production build |

## Stack

- React 19 + TypeScript + Vite 6
- three + @react-three/fiber + @react-three/drei
- Outfit + JetBrains Mono

## Layout

```
src/
  App.tsx
  components/
    Globe.tsx           # Earth, clouds, atmosphere
    Markers.tsx         # Glowing pins
    Scene.tsx           # Canvas, lights, controls, focus
    LocationsPanel.tsx  # Glass featured list
  data/locations.ts
  lib/geo.ts            # lat/lng → XYZ
```

## Design tokens

| Token | Value |
|-------|-------|
| Void bg | `#060a12` |
| Glass | `rgba(12, 18, 32, 0.58)` + blur |
| Text | `#e8eef8` / muted `#9aa8c0` |
| Accent | `#38bdf8` (sky cyan only) |
