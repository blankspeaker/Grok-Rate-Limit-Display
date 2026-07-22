import * as THREE from "three";

/** Earth radius used throughout the scene */
export const EARTH_RADIUS = 1.8;

/**
 * Convert geographic coordinates to Three.js XYZ on a sphere.
 * Convention matches three-globe / common globe shaders.
 */
export function latLngToVector3(
  lat: number,
  lng: number,
  radius: number = EARTH_RADIUS
): THREE.Vector3 {
  const phi = (90 - lat) * (Math.PI / 180);
  const theta = (lng + 180) * (Math.PI / 180);

  const x = -radius * Math.sin(phi) * Math.cos(theta);
  const y = radius * Math.cos(phi);
  const z = radius * Math.sin(phi) * Math.sin(theta);

  return new THREE.Vector3(x, y, z);
}

/** Format lat/lng for mono display */
export function formatCoords(lat: number, lng: number): string {
  const latDir = lat >= 0 ? "N" : "S";
  const lngDir = lng >= 0 ? "E" : "W";
  return `${Math.abs(lat).toFixed(2)}°${latDir}  ${Math.abs(lng).toFixed(2)}°${lngDir}`;
}
