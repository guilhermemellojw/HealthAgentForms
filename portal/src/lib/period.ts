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
  weekday = -1,
): T[] {
  return items.filter(
    (item) =>
      isInPeriod((item[dateField] || "") as string, year, month, weekIndex) &&
      matchesWeekday((item[dateField] || "") as string, weekday),
  );
}

// weekday: -1 = todos os dias, 0 = Domingo ... 6 = Sábado (Date.getDay).
export function matchesWeekday(dateStr: string, weekday: number): boolean {
  if (weekday < 0 || weekday > 6) return true;
  const normalized = (dateStr || "").replace(/\//g, "-");
  if (normalized.split("-").length !== 3) return false;
  return parseDate(normalized).getDay() === weekday;
}

export function computeStats(houses: HouseDoc[], activities: { date?: string }[], year: number, month: number, weekIndex = -1, bairro = "", weekday = -1): HouseStats {
  const bairroFiltered = filterByBairro(houses, bairro);
  const filteredHouses = filterByPeriod(bairroFiltered, "data", year, month, weekIndex, weekday);
  const filteredActivities = filterByPeriod(activities, "date", year, month, weekIndex, weekday);
  const countSituation = (s: string) => filteredHouses.filter((h) => h.situation === s).length;
  // day_activities não carrega bairro: com filtro ativo, dias = datas distintas
  // dos imóveis do bairro no período (evita contar dias de outros bairros).
  const activeDays = normalizeBairro(bairro)
    ? new Set(filteredHouses.map((h) => (h.data || "").trim()).filter(Boolean)).size
    : filteredActivities.length;
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
    activeDays,
  };
}

export function normalizeBairro(value: unknown): string {
  return normalizeField(value);
}

export function matchesBairro(houseBairro: unknown, selectedBairro: string): boolean {
  const sel = normalizeBairro(selectedBairro);
  if (!sel) return true;
  const house = normalizeBairro(houseBairro);
  if (!house) return false;
  return removeAccents(house).toLowerCase() === removeAccents(sel).toLowerCase();
}

export function filterByBairro<T extends { bairro?: string }>(houses: T[], selectedBairro: string): T[] {
  if (!normalizeBairro(selectedBairro)) return houses;
  return houses.filter((h) => matchesBairro(h.bairro, selectedBairro));
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

// ------------------------------------------------------------
// Helpers p/ RG — paridade getTimestamp/normalize/heal do Android
// ------------------------------------------------------------

// App StringExtensions.removeAccents(): só tira acentos (p/ comparacao).
export function removeAccents(text: string): string {
  return text.toString().normalize("NFD").replace(/[\u0300-\u036f]/g, "");
}

// App StringExtensions.normalize(): padroniza separadores/casing, MANTEM acentos.
export function normalizeField(text: unknown): string {
  if (text === null || text === undefined) return "";
  return String(text)
    .trim()
    .replace(/\//g, "-")
    .replace(/\./g, "-")
    .replace(/\s+/g, " ")
    .replace(/-+/g, "-")
    .toUpperCase();
}

// Ano direto do campo data (dd-MM-yyyy), como o Android faz por casa.
export function houseYear(h: HouseDoc): number | null {  const parts = (h.data || "").trim().replace(/\//g, "-").split("-");
  if (parts.length !== 3) return null;
  const y = Number(parts[2]);
  return Number.isFinite(y) && y > 1000 ? y : null;
}

// Timestamp da data (0 p/ invalida), como getTimestamp() do use case.
export function houseDateTs(h: HouseDoc): number {
  const [d, m, y] = (h.data || "").trim().replace(/\//g, "-").split("-").map(Number);
  if (!y || !m || !d) return 0;
  const ts = new Date(y, m - 1, d, 12, 0, 0).getTime();
  return Number.isFinite(ts) ? ts : 0;
}

// createdAt normalizado p/ ms (number ou { seconds } do Firestore).
export function createdAtMs(h: HouseDoc): number {
  const raw = h.createdAt;
  if (raw == null) return Number.MAX_SAFE_INTEGER;
  if (typeof raw === "number") return Number.isFinite(raw) ? raw : Number.MAX_SAFE_INTEGER;
  if (typeof raw === "object" && typeof raw.seconds === "number") return raw.seconds * 1000;
  const n = Number(raw);
  return Number.isFinite(n) ? n : Number.MAX_SAFE_INTEGER;
}
