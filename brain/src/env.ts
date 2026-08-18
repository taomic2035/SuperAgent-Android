export function env(key: string, fallback: string): string {
  const value = process.env[key]
  return value === undefined || value === "" ? fallback : value
}

export function envInt(key: string, fallback: number): number {
  const value = process.env[key]
  if (value === undefined || value === "") return fallback
  const parsed = Number.parseInt(value, 10)
  return Number.isNaN(parsed) ? fallback : parsed
}

export function envBool(key: string, fallback: boolean): boolean {
  const value = process.env[key]
  if (value === undefined || value === "") return fallback
  return value === "1" || value.toLowerCase() === "true"
}