import personasJson from "./personas.json" with { type: "json" }
import type { Persona } from "./promptBuilder.ts"

export interface PersonaConfig {
  active: string
  personas: Record<string, Persona>
}

export function loadPersonas(): PersonaConfig {
  return personasJson as PersonaConfig
}