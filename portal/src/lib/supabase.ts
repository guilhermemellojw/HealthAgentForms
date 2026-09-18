import { createClient, type SupabaseClient, type User } from "@supabase/supabase-js";
import type { AccessRequest } from "./adminTypes";
import type { AgentDoc, DayActivityDoc, HouseDoc, MonthlySummaryDoc, UserDoc } from "./types";

const url = import.meta.env.VITE_SUPABASE_URL ?? "https://jgvwiqrvkqkugqtycgbv.supabase.co";
const key = import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY ?? "sb_publishable_LP0VVL4NsruINN05X9rFdg_y9ryqM5X";

export const supabase: SupabaseClient = createClient(url, key);

export const BOOTSTRAP_ADMINS: string[] =
  (import.meta.env.VITE_BOOTSTRAP_ADMINS as string | undefined)
    ?.split(",")
    .map((s: string) => s.trim())
    .filter(Boolean) ?? ["gmellobkp@gmail.com"];

export type { User };

/** Lança erro legível em falhas PostgREST; estreita data não-nula. */
export function must<T>(res: { data: T | null; error: unknown }): T {
  if (res.error || res.data === null || res.data === undefined) {
    const e = res.error as { message?: string; code?: string } | null;
    throw new Error(e?.message || `Supabase ${e?.code || "error"}: resposta vazia`);
  }
  return res.data as T;
}

/** ISO timestamptz -> ms (compat com campos legados number). */
export function isoMs(v: string | null | undefined): number | null {
  if (!v) return null;
  const ms = Date.parse(v);
  return Number.isFinite(ms) ? ms : null;
}

/** "DD-MM-YYYY" -> "YYYY-MM-DD" (filtros na coluna data_date). */
export function dashToIso(dash: string): string | null {
  const m = /^(\d{2})-(\d{2})-(\d{4})$/.exec(dash.trim());
  return m ? `${m[3]}-${m[2]}-${m[1]}` : null;
}

// ---------------------------------------------------------------------------
// Linhas do banco (snake_case) -> docs do portal (camelCase, inalterados p/ UI)
// ---------------------------------------------------------------------------

export interface ProfileRow {
  id: string; email: string; display_name: string | null; photo_url: string | null;
  role: string; is_authorized: boolean; agent_name: string | null;
  is_pre_registered: boolean; require_data_reset: boolean;
}

export function toUserDoc(r: ProfileRow): UserDoc {
  return {
    uid: r.id,
    email: r.email,
    role: (r.role as UserDoc["role"]) ?? "AGENT",
    isAuthorized: !!r.is_authorized,
    isPreRegistered: !!r.is_pre_registered,
    displayName: r.display_name ?? undefined,
    agentName: r.agent_name ?? undefined,
  };
}

export interface AgentRow {
  id: string; email: string | null; agent_name: string | null; photo_url: string | null;
  is_pre_registered: boolean; last_sync_time: string | null;
}

export function toAgentDoc(r: AgentRow): AgentDoc {
  return {
    id: r.id,
    agentName: r.agent_name ?? undefined,
    email: r.email ?? undefined,
    lastSyncTime: isoMs(r.last_sync_time) ?? undefined,
    photoUrl: r.photo_url ?? undefined,
    isPreRegistered: !!r.is_pre_registered,
  };
}

export interface HouseRow {
  natural_key: string; data_text: string; street_name: string | null; number: string | null;
  block_number: string | null; block_sequence: string | null; sequence: number | null;
  complement: number | null; visit_segment: number | null; list_order: number | null;
  situation: string | null; property_type: string | null; com_foco: boolean | null;
  a1: number; a2: number; b: number; c: number; d1: number; d2: number; e: number;
  eliminados: number; larvicida: number | string; latitude: number | null; longitude: number | null;
  observation: string | null; municipio: string | null; bairro: string | null;
  categoria: string | null; zona: string | null; tipo: string | null; atividade: string | null;
  ciclo: string | null; localidade_concluida: boolean | null; quarteirao_concluido: boolean | null;
  agent_name: string | null; agent_uid: string | null; edited_by_admin: boolean;
  updated_at: string; last_sync_time: string | null;
}

export function toHouseDoc(r: HouseRow): HouseDoc {
  return {
    id: r.natural_key,
    data: r.data_text,
    streetName: r.street_name ?? undefined,
    number: r.number ?? undefined,
    blockNumber: r.block_number ?? undefined,
    blockSequence: r.block_sequence ?? undefined,
    sequence: r.sequence ?? undefined,
    complement: r.complement ?? undefined,
    visitSegment: r.visit_segment ?? undefined,
    listOrder: r.list_order ?? undefined,
    situation: r.situation ?? undefined,
    propertyType: r.property_type ?? undefined,
    comFoco: r.com_foco ?? undefined,
    a1: r.a1, a2: r.a2, b: r.b, c: r.c, d1: r.d1, d2: r.d2, e: r.e,
    eliminados: r.eliminados,
    larvicida: typeof r.larvicida === "string" ? Number(r.larvicida) : r.larvicida,
    latitude: r.latitude, longitude: r.longitude,
    lastUpdated: r.updated_at,
    lastSyncTime: isoMs(r.last_sync_time) ?? undefined,
    agentName: r.agent_name ?? undefined,
    agentUid: r.agent_uid ?? undefined,
    editedByAdmin: !!r.edited_by_admin,
    observation: r.observation ?? undefined,
    municipio: r.municipio ?? undefined,
    bairro: r.bairro ?? undefined,
    categoria: r.categoria ?? undefined,
    zona: r.zona ?? undefined,
    tipo: r.tipo ?? undefined,
    atividade: r.atividade ?? undefined,
    localidadeConcluida: r.localidade_concluida ?? undefined,
    quarteiraoConcluido: r.quarteirao_concluido ?? undefined,
  };
}

export interface ActivityRow {
  date_text: string; status: string | null; is_closed: boolean | null;
  is_manual_unlock: boolean | null; agent_name: string | null; agent_uid: string | null;
  edited_by_admin: boolean; updated_at: string;
}

export function toActivityDoc(r: ActivityRow): DayActivityDoc {
  return {
    id: r.date_text,
    date: r.date_text,
    status: r.status ?? undefined,
    isClosed: r.is_closed ?? undefined,
    isManualUnlock: r.is_manual_unlock ?? undefined,
    agentUid: r.agent_uid ?? undefined,
    agentName: r.agent_name ?? undefined,
    lastUpdated: r.updated_at,
  };
}

export interface SummaryRow {
  month_year: string; treated_count: number; focus_count: number; total_houses: number;
  days_worked: number; situation_counts: Record<string, number>; property_type_counts: Record<string, number>;
}

export function toSummaryDoc(r: SummaryRow): MonthlySummaryDoc {
  return {
    id: r.month_year,
    monthYear: r.month_year,
    totalHouses: r.total_houses,
    treatedCount: r.treated_count,
    focusCount: r.focus_count,
    daysWorked: r.days_worked,
    situationCounts: r.situation_counts || {},
    propertyTypeCounts: r.property_type_counts || {},
  };
}

export interface RequestRow {
  id: string; email: string | null; display_name: string | null;
  requested_name: string | null; status: string; created_at: string;
}

export function toAccessRequest(r: RequestRow): AccessRequest {
  return {
    id: r.id,
    email: r.email ?? "",
    displayName: r.display_name ?? undefined,
    requestedName: r.requested_name ?? undefined,
    status: (r.status as AccessRequest["status"]) ?? "PENDING",
    createdAt: isoMs(r.created_at) ?? undefined,
  };
}

export interface BackupRow {
  ts: number; storage_path: string; house_count: number; activity_count: number; agent_name: string | null;
}

// ---------------------------------------------------------------------------
// Escrita: payload camelCase (buildUpdatePayload) -> colunas snake_case.
// lastUpdated é carimbado pelo trigger updated_at — nunca enviado.
// ---------------------------------------------------------------------------

const CAMEL_TO_SNAKE: Record<string, string> = {
  data: "data_text",
  streetName: "street_name",
  blockNumber: "block_number",
  blockSequence: "block_sequence",
  visitSegment: "visit_segment",
  listOrder: "list_order",
  propertyType: "property_type",
  comFoco: "com_foco",
  agentName: "agent_name",
  agentUid: "agent_uid",
  editedByAdmin: "edited_by_admin",
  lastSyncTime: "last_sync_time",
  localidadeConcluida: "localidade_concluida",
  quarteiraoConcluido: "quarteirao_concluido",
  monthYear: "month_year",
  month_year: "month_year",
};

export function toSnakePayload(payload: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(payload)) {
    if (k === "lastUpdated" || k === "createdAt") continue; // carimbos do banco
    out[CAMEL_TO_SNAKE[k] ?? k] = v;
  }
  return out;
}

// ---------------------------------------------------------------------------
// metadata.{agent_info,locations,settings} (value JSONB, merge no cliente)
// ---------------------------------------------------------------------------

export async function getMetaValue(key: string): Promise<Record<string, unknown> | null> {
  const { data, error } = await supabase.from("metadata").select("value").eq("key", key).maybeSingle();
  if (error) throw new Error(error.message);
  return (data?.value as Record<string, unknown>) ?? null;
}

export async function setMetaValue(key: string, value: Record<string, unknown>): Promise<void> {
  const { error } = await supabase.from("metadata").upsert({ key, value }, { onConflict: "key" });
  if (error) throw new Error(error.message);
}

/** Acrescenta/remove item de array dentro de metadata.<key>.<field> (sem arrayUnion). */
export async function mutateMetaArray(key: string, field: string, add?: string, remove?: string): Promise<void> {
  const cur = (await getMetaValue(key)) || {};
  const list = Array.isArray(cur[field]) ? [...(cur[field] as string[])] : [];
  if (add && !list.includes(add)) list.push(add);
  const next = remove ? list.filter((x) => x !== remove) : list;
  await setMetaValue(key, { ...cur, [field]: next });
}
