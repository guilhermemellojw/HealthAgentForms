import { serverTimestamp, collection, deleteDoc, doc, getDoc, getDocs, query, setDoc, where } from "firebase/firestore";
import { db } from "./firebase";
import { normalizeField } from "./period";
import type { DayActivityDoc, HouseDoc } from "./types";

// Paridade com o app Android (SyncPushHandler agregação + HouseMapper.toFirestoreMap).
// Enums atuais do app (domain/model/Enums.kt): Situation {EMPTY, NONE, F, REC, A, V},
// PropertyType {EMPTY, R, C, TB, O, PE} — gravados pelo .name.

export const SITUATION_OPTIONS: { value: string; label: string }[] = [
  { value: "NONE", label: "Aberto" },
  { value: "EMPTY", label: "Aberto (legado)" },
  { value: "F", label: "Fechado (F)" },
  { value: "REC", label: "Recusado (REC)" },
  { value: "A", label: "Abandonado (A)" },
  { value: "V", label: "Vazio (V)" },
];

export const PROPERTY_OPTIONS: { value: string; label: string }[] = [
  { value: "R", label: "Residência (R)" },
  { value: "C", label: "Comércio (C)" },
  { value: "TB", label: "Terreno baldio (TB)" },
  { value: "PE", label: "Ponto estratégico (PE)" },
  { value: "O", label: "Outros (O)" },
  { value: "EMPTY", label: "Não informado" },
];

export interface VisitForm {
  data: string; // DD-MM-YYYY (fixa do dia em edição)
  streetName: string;
  number: string;
  complement: number;
  sequence: number;
  blockNumber: string;
  blockSequence: string;
  bairro: string;
  situation: string;
  propertyType: string;
  a1: number; a2: number; b: number; c: number;
  d1: number; d2: number; e: number;
  eliminados: number;
  larvicida: number;
  comFoco: boolean;
  observation: string;
  visitSegment: number;
  listOrder: number;
}

export function houseToForm(h: HouseDoc): VisitForm {
  const num = (v: unknown, fb = 0) => (typeof v === "number" && Number.isFinite(v) ? v : fb);
  return {
    data: (h.data || "").trim(),
    streetName: h.streetName || "",
    number: h.number || "",
    complement: num(h.complement),
    sequence: num(h.sequence, 1),
    blockNumber: h.blockNumber || "",
    blockSequence: h.blockSequence || "",
    bairro: h.bairro || "",
    situation: h.situation || "NONE",
    propertyType: h.propertyType || "R",
    a1: num(h.a1), a2: num(h.a2), b: num(h.b), c: num(h.c),
    d1: num(h.d1), d2: num(h.d2), e: num(h.e),
    eliminados: num(h.eliminados),
    larvicida: num(h.larvicida),
    comFoco: !!h.comFoco,
    observation: h.observation || "",
    visitSegment: num(h.visitSegment),
    listOrder: num(h.listOrder),
  };
}

export function validateVisitForm(f: VisitForm): string[] {
  const errors: string[] = [];
  if (!/^\d{2}-\d{2}-\d{4}$/.test(f.data.trim())) errors.push("Data inválida (use DD-MM-AAAA).");
  else {
    const [d, m, y] = f.data.trim().split("-").map(Number);
    const dt = new Date(y, m - 1, d);
    if (dt.getFullYear() !== y || dt.getMonth() !== m - 1 || dt.getDate() !== d) {
      errors.push("Data inexistente.");
    }
  }
  if (!f.streetName.trim()) errors.push("Rua é obrigatória.");
  if (!SITUATION_OPTIONS.some((o) => o.value === f.situation)) errors.push("Situação inválida.");
  if (!PROPERTY_OPTIONS.some((o) => o.value === f.propertyType)) errors.push("Tipo de imóvel inválido.");
  for (const k of ["a1", "a2", "b", "c", "d1", "d2", "e", "eliminados", "larvicida", "complement", "sequence", "visitSegment", "listOrder"] as const) {
    const v = f[k];
    if (!Number.isFinite(v) || v < 0) errors.push(`Campo ${k} deve ser número ≥ 0.`);
  }
  return errors;
}

/** Situação "trabalhada" (paridade HouseValidationUseCase). */
export function isWorkedSituation(situation: string): boolean {
  return situation === "NONE" || situation === "EMPTY";
}

function depositsTotal(f: Pick<VisitForm, "a1" | "a2" | "b" | "c" | "d1" | "d2" | "e">): number {
  return f.a1 + f.a2 + f.b + f.c + f.d1 + f.d2 + f.e;
}

/**
 * Etiquetas de validação da linha (paridade HouseUiStateMapper + HouseValidationUseCase):
 * DUPLICADO, SEM Nº, SEM TIPO, SEM BAIRRO, SEM RUA, SEM QUART.,
 * TRAT. INDEVIDO, LARV. SEM DEP., DEP. SEM LARV.
 */
export function validateRowLabels(f: VisitForm, duplicate: boolean): string[] {
  const labels: string[] = [];
  if (duplicate) labels.push("DUPLICADO");
  const complete =
    f.bairro.trim() !== "" &&
    f.streetName.trim() !== "" &&
    f.blockNumber.trim() !== "" &&
    (f.number.trim() !== "" || f.sequence > 0);
  if (!complete) {
    if (f.number.trim() === "" && !(f.sequence > 0)) labels.push("SEM Nº");
    if (f.bairro.trim() === "") labels.push("SEM BAIRRO");
    if (f.streetName.trim() === "") labels.push("SEM RUA");
    if (f.blockNumber.trim() === "") labels.push("SEM QUART.");
  }
  if (f.propertyType === "EMPTY") labels.push("SEM TIPO");
  const worked = isWorkedSituation(f.situation);
  const hasTreatment =
    depositsTotal(f) + f.eliminados > 0 || f.larvicida > 0 || f.comFoco;
  if (!worked && hasTreatment) labels.push("TRAT. INDEVIDO");
  if (f.larvicida > 0 && depositsTotal(f) === 0) labels.push("LARV. SEM DEP.");
  if (depositsTotal(f) > 0 && !(f.larvicida > 0)) labels.push("DEP. SEM LARV.");
  return labels;
}

/** Assinatura de endereço p/ detectar duplicados no dia (paridade generateAddressSignature). */
export function addressSignature(f: Pick<VisitForm, "blockNumber" | "blockSequence" | "streetName" | "number" | "sequence" | "complement" | "bairro">): string {
  const norm = (v: string) => normalizeField(v);
  return `${norm(f.blockNumber)}_${norm(f.blockSequence)}_${norm(f.streetName)}_${norm(f.number)}_${f.sequence}_${f.complement}_${norm(f.bairro)}`.toUpperCase();
}

/** Ids com assinatura repetida no conjunto (marcados DUPLICADO). */
export function findDuplicateIds(rows: { id: string; form: VisitForm }[]): Set<string> {
  const seen = new Map<string, string[]>();
  for (const r of rows) {
    const key = addressSignature(r.form);
    seen.set(key, [...(seen.get(key) ?? []), r.id]);
  }
  const out = new Set<string>();
  for (const ids of seen.values()) {
    if (ids.length > 1) ids.forEach((id) => out.add(id));
  }
  return out;
}

/** Resumo de tratamento da linha (paridade TreatmentData.formattedSummary). */
export function treatmentSummary(f: Pick<VisitForm, "a1" | "a2" | "b" | "c" | "d1" | "d2" | "e" | "eliminados" | "larvicida">): string {
  const parts: string[] = [];
  if (f.a1 > 0) parts.push(`A1: ${f.a1}`);
  if (f.a2 > 0) parts.push(`A2: ${f.a2}`);
  if (f.b > 0) parts.push(`B: ${f.b}`);
  if (f.c > 0) parts.push(`C: ${f.c}`);
  if (f.d1 > 0) parts.push(`D1: ${f.d1}`);
  if (f.d2 > 0) parts.push(`D2: ${f.d2}`);
  if (f.e > 0) parts.push(`E: ${f.e}`);
  if (f.eliminados > 0) parts.push(`Elim: ${f.eliminados}`);
  if (f.larvicida > 0) parts.push(`Larv: ${f.larvicida}g`);
  return parts.join(" | ");
}

export interface DayTotals {
  visits: number;
  a1: number; a2: number; b: number; c: number;
  d1: number; d2: number; e: number;
  eliminados: number;
  larvicida: number;
  focuses: number;
  worked: number;
}

/** Totais ao vivo do dia (paridade ProductionStatsBar). */
export function computeDayTotals(forms: VisitForm[]): DayTotals {
  const t: DayTotals = {
    visits: forms.length,
    a1: 0, a2: 0, b: 0, c: 0, d1: 0, d2: 0, e: 0,
    eliminados: 0, larvicida: 0, focuses: 0, worked: 0,
  };
  for (const f of forms) {
    t.a1 += f.a1; t.a2 += f.a2; t.b += f.b; t.c += f.c;
    t.d1 += f.d1; t.d2 += f.d2; t.e += f.e;
    t.eliminados += f.eliminados; t.larvicida += f.larvicida;
    if (f.comFoco) t.focuses += 1;
    if (isWorkedSituation(f.situation)) t.worked += 1;
  }
  return t;
}

/** "DD-MM-YYYY" -> "MM-YYYY" (mês do monthly_summaries). */
export function monthYearOf(dateStr: string): string | null {
  const parts = (dateStr || "").trim().replace(/\//g, "-").split("-");
  if (parts.length !== 3) return null;
  const [d, m, y] = parts.map(Number);
  if (!Number.isInteger(d) || !Number.isInteger(m) || !Number.isInteger(y)) return null;
  if (d < 1 || d > 31 || m < 1 || m > 12 || y < 1000) return null;
  return `${String(m).padStart(2, "0")}-${y}`;
}

/** Bounds DD-MM-YYYY do mês "MM-YYYY" (mesmo mês = ordenação lexicográfica segura). */
export function monthDateBounds(monthYear: string): { start: string; end: string } | null {
  const m = /^(\d{2})-(\d{4})$/.exec(monthYear);
  if (!m) return null;
  const month = Number(m[1]);
  const year = Number(m[2]);
  if (month < 1 || month > 12) return null;
  const lastDay = new Date(year, month, 0).getDate();
  const pad = (n: number) => String(n).padStart(2, "0");
  return { start: `01-${m[1]}-${m[2]}`, end: `${pad(lastDay)}-${m[1]}-${m[2]}` };
}

/** "DD-MM-YYYY" -> AAAAMMDD (nº) ou null. */
export function dateToInt(dateStr: string): number | null {
  const parts = (dateStr || "").trim().replace(/\//g, "-").split("-");
  if (parts.length !== 3) return null;
  const [d, m, y] = parts.map(Number);
  if (![d, m, y].every(Number.isInteger)) return null;
  return y * 10000 + m * 100 + d;
}

/** Hoje em America/Sao_Paulo como AAAAMMDD (filtro de futuro, paridade com o app). */
export function todayIntSP(now = new Date()): number {
  const parts = new Intl.DateTimeFormat("pt-BR", {
    timeZone: "America/Sao_Paulo", day: "2-digit", month: "2-digit", year: "numeric",
  }).formatToParts(now);
  const get = (t: string) => Number(parts.find((p) => p.type === t)?.value);
  return get("year") * 10000 + get("month") * 100 + get("day");
}

/** today DD-MM-YYYY (America/Sao_Paulo). */
export function todayDashSP(now = new Date()): string {
  const parts = new Intl.DateTimeFormat("pt-BR", {
    timeZone: "America/Sao_Paulo", day: "2-digit", month: "2-digit", year: "numeric",
  }).formatToParts(now);
  const get = (t: string) => parts.find((p) => p.type === t)?.value ?? "";
  return `${get("day")}-${get("month")}-${get("year")}`;
}

function isTreatedCounts(h: {
  a1?: number; a2?: number; b?: number; c?: number; d1?: number; d2?: number;
  e?: number; eliminados?: number; larvicida?: number; comFoco?: boolean;
}): boolean {
  return (
    (h.a1 || 0) + (h.a2 || 0) + (h.b || 0) + (h.c || 0) +
      (h.d1 || 0) + (h.d2 || 0) + (h.e || 0) + (h.eliminados || 0) > 0 ||
    (h.larvicida || 0) > 0 ||
    !!h.comFoco
  );
}

export interface RecomputedSummary {
  monthYear: string;
  treatedCount: number;
  focusCount: number;
  situationCounts: Record<string, number>;
  propertyTypeCounts: Record<string, number>;
  totalHouses: number;
  daysWorked: number;
  lastUpdated: number;
}

/**
 * Recalcula o monthly_summary com as regras exatas do app (SyncPushHandler §4):
 * - situation EMPTY -> "NONE", demais pelo nome gravado;
 * - treated = soma depósitos > 0 ou larvicida > 0 ou comFoco;
 * - daysWorked = nº de day_activities no mês;
 * - exclui datas futuras (limite = todayInt).
 */
export function computeMonthlySummary(
  houses: HouseDoc[],
  activities: { date?: string }[],
  monthYear: string,
  todayInt: number,
): RecomputedSummary {
  const inMonth = (ds: string) => {
    const norm = (ds || "").trim().replace(/\//g, "-");
    if (!norm.endsWith(`-${monthYear}`)) return false;
    const di = dateToInt(norm);
    return di !== null && di <= todayInt;
  };
  const mh = houses.filter((h) => inMonth(h.data || ""));
  const ma = activities.filter((a) => inMonth(a.date || ""));
  const situationCounts: Record<string, number> = {};
  for (const h of mh) {
    const key = h.situation === "EMPTY" ? "NONE" : h.situation || "NONE";
    situationCounts[key] = (situationCounts[key] ?? 0) + 1;
  }
  const propertyTypeCounts: Record<string, number> = {};
  for (const h of mh) {
    const key = h.propertyType || "EMPTY";
    propertyTypeCounts[key] = (propertyTypeCounts[key] ?? 0) + 1;
  }
  return {
    monthYear,
    treatedCount: mh.filter(isTreatedCounts).length,
    focusCount: mh.filter((h) => h.comFoco).length,
    situationCounts,
    propertyTypeCounts,
    totalHouses: mh.length,
    daysWorked: ma.length,
    lastUpdated: Date.now(),
  };
}

/** Payload de update: só campos alterados + carimbo admin (paridade toFirestoreMap). */
export function buildUpdatePayload(orig: HouseDoc, form: VisitForm): Record<string, unknown> {
  const base = houseToForm(orig);
  const out: Record<string, unknown> = {};
  const put = (key: string, next: unknown, prev: unknown) => {
    if (JSON.stringify(next) !== JSON.stringify(prev)) out[key] = next;
  };
  put("data", form.data.trim().replace(/\//g, "-"), base.data);
  put("streetName", form.streetName.trim(), base.streetName);
  put("number", form.number.trim(), base.number);
  put("complement", form.complement, base.complement);
  put("sequence", form.sequence, base.sequence);
  put("blockNumber", form.blockNumber.trim(), base.blockNumber);
  put("blockSequence", form.blockSequence.trim(), base.blockSequence);
  put("bairro", normalizeField(form.bairro), base.bairro);
  put("situation", form.situation, base.situation);
  put("propertyType", form.propertyType, base.propertyType);
  put("a1", form.a1, base.a1); put("a2", form.a2, base.a2);
  put("b", form.b, base.b); put("c", form.c, base.c);
  put("d1", form.d1, base.d1); put("d2", form.d2, base.d2);
  put("e", form.e, base.e);
  put("eliminados", form.eliminados, base.eliminados);
  put("larvicida", form.larvicida, base.larvicida);
  put("comFoco", form.comFoco, base.comFoco);
  put("observation", form.observation.trim(), base.observation);
  put("visitSegment", form.visitSegment, base.visitSegment);
  put("listOrder", form.listOrder, base.listOrder);
  out["editedByAdmin"] = true;
  out["lastUpdated"] = serverTimestamp();
  return out;
}

export type AuditAction = "update" | "delete";

/** lastUpdated (Timestamp|nº|ausente) -> ms (nº) ou null. */
export function lastUpdatedMs(v: unknown): number | null {
  if (v == null) return null;
  if (typeof v === "number") return Number.isFinite(v) ? v : null;
  if (typeof v === "object") {
    const o = v as { seconds?: number; nanoseconds?: number; toMillis?: () => number };
    if (typeof o.toMillis === "function") {
      try { return o.toMillis(); } catch { return null; }
    }
    if (typeof o.seconds === "number") return o.seconds * 1000;
  }
  return null;
}

export function buildAuditEntry(args: {
  actorUid: string;
  actorEmail: string;
  action: AuditAction;
  agentUid: string;
  agentName: string;
  houseId?: string;
  date?: string;
  monthYear?: string;
  before?: unknown;
  after?: unknown;
}): Record<string, unknown> {
  return { ...args, createdAt: serverTimestamp() };
}

function docToHouse(id: string, data: Record<string, unknown>): HouseDoc {
  return { id, ...data } as HouseDoc;
}

/**
 * Recalcula e grava o monthly_summary do mês (formato exato do app).
 * Apaga também a variante legada "MM/AAAA" se existir a leitura.
 * Retorna o summary gravado.
 */
export async function refreshMonthSummary(agentId: string, monthYear: string): Promise<RecomputedSummary> {
  const bounds = monthDateBounds(monthYear);
  if (!bounds) throw new Error(`Mês inválido: ${monthYear}`);
  const todayInt = todayIntSP();
  const housesRef = collection(db, "agents", agentId, "houses");
  const actsRef = collection(db, "agents", agentId, "day_activities");
  const [hSnap, aSnap] = await Promise.all([
    getDocs(query(housesRef, where("data", ">=", bounds.start), where("data", "<=", bounds.end))),
    getDocs(query(actsRef, where("date", ">=", bounds.start), where("date", "<=", bounds.end))),
  ]);
  const houses = hSnap.docs.map((d) => docToHouse(d.id, d.data()));
  const activities = aSnap.docs.map((d) => ({ id: d.id, ...d.data() }) as DayActivityDoc);
  const summary = computeMonthlySummary(houses, activities, monthYear, todayInt);
  await setDoc(doc(db, "agents", agentId, "monthly_summaries", monthYear), summary);
  // Variante legada "MM/AAAA" (o app ainda a lê como fallback): remove se existir.
  const legacyId = monthYear.replace("-", "/");
  const legacyRef = doc(db, "agents", agentId, "monthly_summaries", legacyId);
  if ((await getDoc(legacyRef)).exists()) await deleteDoc(legacyRef);
  return summary;
}
