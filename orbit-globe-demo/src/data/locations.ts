export interface Location {
  id: string;
  name: string;
  country: string;
  lat: number;
  lng: number;
  description: string;
}

export const LOCATIONS: Location[] = [
  {
    id: "tokyo",
    name: "Tokyo",
    country: "Japan",
    lat: 35.6762,
    lng: 139.6503,
    description: "Neon megacity, tech capital",
  },
  {
    id: "new-york",
    name: "New York",
    country: "USA",
    lat: 40.7128,
    lng: -74.006,
    description: "The city that never sleeps",
  },
  {
    id: "london",
    name: "London",
    country: "UK",
    lat: 51.5074,
    lng: -0.1278,
    description: "Historic heart of finance",
  },
  {
    id: "sydney",
    name: "Sydney",
    country: "Australia",
    lat: -33.8688,
    lng: 151.2093,
    description: "Harbor city under southern skies",
  },
  {
    id: "cape-town",
    name: "Cape Town",
    country: "South Africa",
    lat: -33.9249,
    lng: 18.4241,
    description: "Where mountains meet ocean",
  },
  {
    id: "rio",
    name: "Rio de Janeiro",
    country: "Brazil",
    lat: -22.9068,
    lng: -43.1729,
    description: "Carnival and Christ the Redeemer",
  },
  {
    id: "dubai",
    name: "Dubai",
    country: "UAE",
    lat: 25.2048,
    lng: 55.2708,
    description: "Desert metropolis of glass",
  },
  {
    id: "reykjavik",
    name: "Reykjavik",
    country: "Iceland",
    lat: 64.1466,
    lng: -21.9426,
    description: "Gateway to the north",
  },
  {
    id: "singapore",
    name: "Singapore",
    country: "Singapore",
    lat: 1.3521,
    lng: 103.8198,
    description: "Garden city of the future",
  },
  {
    id: "san-francisco",
    name: "San Francisco",
    country: "USA",
    lat: 37.7749,
    lng: -122.4194,
    description: "Golden Gate and innovation",
  },
];
