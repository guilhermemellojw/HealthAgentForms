import { SITUATION, PROPERTY_TYPE, WORKED_EXCLUDED } from "./constants";
import type { HouseDoc, HouseStats } from "./types";

export function normalizeName(text: unknown): string {
  if (text === null || text === undefined) return "";
  return text
    .toString()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .trim()
    .replace(/\//g, "-")
    .replace(/\./g, "-")
    .replace(/\s+/g, " ")
    .replace(/-+/g, "-")
    .toUpperCase();
}

export function isTreated(house: HouseDoc): boolean {
  return (
    (house.a1 || 0) + (house.a2 || 0) + (house.b || 0) + (house.c || 0) +
      (house.d1 || 0) + (house.d2 || 0) + (house.e || 0) + (house.eliminados || 0) >
      0 ||
    (house.larvicida || 0) > 0 ||
    !!house.comFoco
  );
}

export function parseDate(dateStr: string): Date {
  const parts = dateStr.split("-");
  if (parts.length !== 3) return new Date(0);
  return new Date(Number(parts[2]), Number(parts[1]) - 1, Number(parts[0]), 12, 0, 0);
}

export interface WeekDef {
  label: string;
  start: Date;
  end: Date;
}

export function getWeeksForMonth(year: number, month: number): WeekDef[] {
  const weeks: WeekDef[] = [];
  let date = new Date(year, month, 1, 0, 0, 0, 0);
  while (date.getDay() !== 0) date.setDate(date.getDate() - 1);
  const maxDay = new Date(year, month + 1, 0).getDate();
  const endOfMonth = new Date(year, month, maxDay, 23, 59, 59, 999);
  const now = new Date();
  let weekNum = 1;
  while (date <= endOfMonth) {
    const start = new Date(date);
    if (start > now) break;
    const end = new Date(start);
    end.setDate(start.getDate() + 6);
    end.setHours(23, 59, 59, 999);
    weeks.push({
      label: `Semana ${weekNum} (${formatSmallDate(start)} - ${formatSmallDate(end)})`,
      start,
      end,
    });
    date = new Date(end);
    date.setDate(date.getDate() + 1);
    date.setHours(0, 0, 0, 0);
    weekNum++;
  }
  return weeks;
}

function formatSmallDate(d: Date): string {
  return `${String(d.getDate()).padStart(2, "0")}/${String(d.getMonth() + 1).padStart(2, "0")}`;
}

export function isInPeriod(dateStr: string, year: number, month: number, weekIndex: number): boolean {
  const normalized = (dateStr || "").replace(/\//g, "-");
  const date = parseDate(normalized);
  const today = new Date();
  today.setHours(23, 59, 59, 999);
  if (date > today) return false;

  if (month === -1) {
    return date.getFullYear() === year;
  }
  if (weekIndex !== -1) {
    const weeks = getWeeksForMonth(year, month);
    const week = weeks[weekIndex];
    if (week) return date >= week.start && date <= week.end;
  }
  return normalized.endsWith(`-${String(month + 1).padStart(2, "0")}-${year}`);
}

export function filterByPeriod<T extends { data?: string; date?: string }>(
  items: T[],
  dateField: "data" | "date",
  year: number,
  month: number,
  weekIndex = -1,
): T[] {
  return items.filter((item) => isInPeriod((item[dateField] || "") as string, year, month, weekIndex));
}

export interface DedupResult {
  valid: HouseDoc[];
  duplicates: number;
}

export function dedupHouses(houses: HouseDoc[]): DedupResult {
  const groups = new Map<string, HouseDoc & { ts: number | string }>();
  let duplicates = 0;
  for (const h of houses) {
    const date = normalizeName(h.data);
    const street = normalizeName(h.streetName);
    const num = normalizeName(h.number);
    const bNum = normalizeName(h.blockNumber);
    const bSeq = normalizeName(h.blockSequence || "0");
    const seq = h.sequence || 0;
    const comp = h.complement || 0;
    const seg = h.visitSegment || 0;
    const key = `DEDUP|${date}|${bNum}|${bSeq}|${street}|${num}|${seq}|${comp}|${seg}`;
    const raw = h.lastUpdated as { seconds?: number } | undefined;
    const ts = (raw?.seconds ?? h.lastUpdated ?? h.lastSyncTime ?? 0) as number | string;
    const existing = groups.get(key);
    if (!existing) {
      groups.set(key, { ...h, ts });
    } else {
      duplicates++;
      if (ts > existing.ts) groups.set(key, { ...h, ts });
    }
  }
  return { valid: [...groups.values()], duplicates };
}

export function computeStats(houses: HouseDoc[], activities: { date?: string }[], year: number, month: number, weekIndex = -1): HouseStats {
  const filteredHouses = filterByPeriod(houses, "data", year, month, weekIndex);
  const filteredActivities = filterByPeriod(activities, "date", year, month, weekIndex);
  const countSituation = (s: string) => filteredHouses.filter((h) => h.situation === s).length;
  return {
    worked: filteredHouses.filter((h) => !WORKED_EXCLUDED.includes(h.situation as never)).length,
    vacant: countSituation(SITUATION.VACANT) + countSituation(SITUATION.L_V),
    closed: countSituation(SITUATION.CLOSED) + countSituation(SITUATION.L_F),
    abandoned: countSituation(SITUATION.ABANDONED) + countSituation(SITUATION.L_A),
    refused: countSituation(SITUATION.REFUSED) + countSituation(SITUATION.L_REC),
    focuses: filteredHouses.filter((h) => h.comFoco).length,
    treated: filteredHouses.filter((h) => isTreated(h)).length,
    visits: filteredHouses.length,
    res: filteredHouses.filter((h) => h.propertyType === PROPERTY_TYPE.RES || h.propertyType === PROPERTY_TYPE.EMPTY).length,
    com: filteredHouses.filter((h) => h.propertyType === PROPERTY_TYPE.COM).length,
    tb: filteredHouses.filter((h) => h.propertyType === PROPERTY_TYPE.TB).length,
    pe: filteredHouses.filter((h) => h.propertyType === PROPERTY_TYPE.PE).length,
    out: filteredHouses.filter((h) => h.propertyType === PROPERTY_TYPE.OUT).length,
    activeDays: filteredActivities.length,
  };
}

export function monthYearFromPeriod(year: number, month: number): string | null {
  if (month === -1) return null;
  return `${String(month + 1).padStart(2, "0")}-${year}`;
}

export function weekIndexForDate(date: Date): number {
  const start = new Date(date.getFullYear(), 0, 1);
  start.setHours(0, 0, 0, 0);
  return Math.floor((date.getTime() - start.getTime()) / (7 * 86400000));
}
// Replicates GetRGBlocksUseCase within-block ordering (Android):
// day -> earliest createdAt of (day, agentUid) pair -> agentName -> listOrder -> id
function rgDayTs(data: string): number {
  const [d, m, y] = data.split("-").map(Number);
  return new Date(y || 1970, (m || 1) - 1, d || 1).getTime();
}

export function sortRgHouses(houses: HouseDoc[]): HouseDoc[] {
  const pairMin = new Map<string, number>();
  for (const h of houses) {
    const key = `${h.data || ""}|${h.agentUid || h.agentName || ""}`;
    const c = Number(h.createdAt ?? 0) || Number.MAX_SAFE_INTEGER;
    if (!pairMin.has(key) || c < (pairMin.get(key) as number)) pairMin.set(key, c);
  }
  return [...houses].sort((a, b) => {
    const da = dayTsOf(a), dbb = dayTsOf(b);
    if (da !== dbb) return da - dbb;
    const ka = `${a.data || ""}|${a.agentUid || a.agentName || ""}`;
    const kb = `${b.data || ""}|${b.agentUid || b.agentName || ""}`;
    const pm = (pairMin.get(ka) ?? 0) - (pairMin.get(kb) ?? 0);
    if (pm) return pm;
    const an = (a.agentName || "").localeCompare(b.agentName || "");
    if (an) return an;
    const lo = (a.listOrder ?? 0) - (b.listOrder ?? 0);
    if (lo) return lo;
    return (a.id || "").localeCompare(b.id || "");
  });
}

function dayTsOf(h: HouseDoc): number {
  return rgDayTs((h.data || "").trim());
}
