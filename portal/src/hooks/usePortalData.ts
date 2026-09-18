import { useQuery } from "@tanstack/react-query";
import { dashToIso, must, supabase, toActivityDoc, toAgentDoc, toHouseDoc, toSummaryDoc } from "../lib/supabase";
import { computeStats, filterByBairro, monthYearFromPeriod, normalizeBairro } from "../lib/period";
import type { AgentDoc, DayActivityDoc, HouseDoc, HouseStats, MonthlySummaryDoc } from "../lib/types";

export function useAgents() {
  return useQuery({
    queryKey: ["agents"],
    queryFn: async () => {
      const res = await supabase.from("agents").select("*").order("agent_name");
      return must(res).map(toAgentDoc);
    },
    staleTime: 60_000,
  });
}

function summaryToStats(s: MonthlySummaryDoc | undefined): HouseStats | null {
  if (!s) return null;
  const sc = s.situationCounts || {};
  const pc = s.propertyTypeCounts || {};
  return {
    worked: (sc.NONE || 0) + (sc.EMPTY || 0),
    vacant: (sc.V || 0) + (sc.VACANT || 0),
    closed: (sc.F || 0) + (sc.CLOSED || 0),
    abandoned: (sc.A || 0) + (sc.ABANDONED || 0),
    refused: (sc.REC || 0) + (sc.REFUSED || 0),
    focuses: s.focusCount || 0,
    treated: s.treatedCount || 0,
    visits: s.totalHouses || 0,
    res: (pc.R || 0) + (pc.EMPTY || 0),
    com: pc.C || 0,
    tb: pc.TB || 0,
    pe: pc.PE || 0,
    out: pc.O || 0,
    activeDays: s.daysWorked || 0,
  };
}

function groupByAgent(rows: MonthlySummaryDoc[], keyOf: (r: MonthlySummaryDoc & { agent_id: string }) => string) {
  const out = new Map<string, MonthlySummaryDoc[]>();
  for (const r of rows as (MonthlySummaryDoc & { agent_id: string })[]) {
    const k = keyOf(r);
    if (!out.has(k)) out.set(k, []);
    out.get(k)!.push(r);
  }
  return out;
}

export function useSummariesByAgent(agents: AgentDoc[] | undefined, year: number, month: number, enabled = true) {
  const monthYear = monthYearFromPeriod(year, month);
  const agentKey = agents?.map((a) => a.id).sort().join(",") ?? "";
  return useQuery({
    queryKey: ["summaries", agentKey, year, month],
    enabled: enabled && !!agents?.length && monthYear !== null,
    queryFn: async () => {
      const ids = (agents as AgentDoc[]).map((a) => a.id);
      const res = await supabase
        .from("monthly_summaries")
        .select("*")
        .in("agent_id", ids)
        .eq("month_year", monthYear as string);
      const rows = must(res).map((r) => ({ ...toSummaryDoc(r), agent_id: r.agent_id }));
      return groupByAgent(rows, (r) => r.agent_id);
    },
    staleTime: 60_000,
  });
}

export function useYearSummariesByAgent(agents: AgentDoc[] | undefined, year: number, enabled = true) {
  const agentKey = agents?.map((a) => a.id).sort().join(",") ?? "";
  return useQuery({
    queryKey: ["summaries-year", agentKey, year],
    enabled: enabled && !!agents?.length,
    queryFn: async () => {
      const ids = (agents as AgentDoc[]).map((a) => a.id);
      // monthYear é "MM-AAAA" (largura fixa): o range cobre exato o ano.
      const res = await supabase
        .from("monthly_summaries")
        .select("*")
        .in("agent_id", ids)
        .gte("month_year", `01-${year}`)
        .lte("month_year", `12-${year}`);
      const rows = must(res).map((r) => ({ ...toSummaryDoc(r), agent_id: r.agent_id }));
      return groupByAgent(rows, (r) => r.agent_id);
    },
    staleTime: 60_000,
  });
}

export interface AgentPeriodStats {
  stats: HouseStats;
  source: "summary" | "raw";
  summaries: MonthlySummaryDoc[];
}

/** Bounds ISO (YYYY-MM-DD) cobrindo os ranges do período — meses cheios contíguos. */
function isoBounds(ranges: FetchDateRange[]): { start: string; end: string } | null {
  const isos = ranges.map((r) => dashToIso(r.start)).concat(ranges.map((r) => dashToIso(r.end)));
  if (isos.some((x) => !x)) return null;
  const sorted = (isos as string[]).sort();
  return { start: sorted[0], end: sorted[sorted.length - 1] };
}

async function fetchHousesInRanges(agentId: string, ranges: FetchDateRange[]): Promise<HouseDoc[]> {
  const b = isoBounds(ranges);
  if (!b) return [];
  const res = await supabase
    .from("houses")
    .select("*")
    .eq("agent_id", agentId)
    .is("deleted_at", null)
    .gte("data_date", b.start)
    .lte("data_date", b.end);
  return must(res).map(toHouseDoc);
}

async function fetchAllActivities(agentId: string): Promise<DayActivityDoc[]> {
  const res = await supabase
    .from("day_activities")
    .select("*")
    .eq("agent_id", agentId)
    .is("deleted_at", null);
  return must(res).map(toActivityDoc);
}

export function useAgentStatsByPeriod(
  agents: AgentDoc[] | undefined,
  year: number,
  month: number,
  week: number,
  bairro = "",
  weekday = -1,
): { statsByAgent: Map<string, AgentPeriodStats>; isLoading: boolean } {
  const bairroFilter = normalizeBairro(bairro);
  const monthRaw = monthYearFromPeriod(year, month);
  // monthly_summaries não têm quebra por bairro nem por dia da semana:
  // com esses filtros ativos, força leitura direta.
  const useRaw = week >= 0 || monthRaw === null || bairroFilter !== "" || weekday !== -1;
  // Liga só o caminho usado (mês XOR ano; nenhum no modo raw) — nunca os dois.
  const summariesMonth = useSummariesByAgent(agents, year, month, !useRaw && monthRaw !== null);
  const summariesYear = useYearSummariesByAgent(agents, year, !useRaw && monthRaw === null);

  const agentKey = agents?.map((a) => a.id).sort().join(",") ?? "";
  const raw = useQuery({
    queryKey: ["raw-period", agentKey, year, month, week, bairroFilter],
    enabled: !!agents?.length && useRaw,
    queryFn: async () => {
      const out = new Map<string, { houses: HouseDoc[]; activities: DayActivityDoc[] }>();
      const ranges = fetchRangesForPeriod(year, month, week);
      await Promise.all(
        (agents as AgentDoc[]).map(async (agent) => {
          const [houses, activities] = await Promise.all([
            fetchHousesInRanges(agent.id, ranges),
            fetchAllActivities(agent.id),
          ]);
          out.set(agent.id, { houses, activities });
        }),
      );
      return out;
    },
    staleTime: 30_000,
  });

  const result = new Map<string, AgentPeriodStats>();
  if (agents) {
    for (const agent of agents) {
      if (useRaw) {
        const data = raw.data?.get(agent.id);
        if (!data) continue;
        const bairroHouses = filterByBairro(data.houses, bairroFilter);
        // Sem dedup por campos: identidade é a natural_key e os ranges já
        // dedupam por id — colapsar por campos esconderia visitas legítimas.
        const stats = computeStats(bairroHouses, data.activities, year, month, week, bairroFilter, weekday);
        // Oculta agentes sem produção no bairro/período (paridade com modo sumarizado).
        if (bairroFilter && stats.visits === 0 && stats.activeDays === 0) continue;
        result.set(agent.id, {
          stats,
          source: "raw",
          summaries: [],
        });
      } else if (monthRaw) {
        const sums = summariesMonth.data?.get(agent.id) || [];
        const stats = summaryToStats(sums[0]);
        if (stats) result.set(agent.id, { stats, source: "summary", summaries: sums });
      } else {
        const sums = summariesYear.data?.get(agent.id) || [];
        if (sums.length === 0) continue;
        const totals: HouseStats = sums.reduce<HouseStats>(
          (acc, s) => {
            const st = summaryToStats(s);
            if (!st) return acc;
            for (const k of Object.keys(acc) as (keyof HouseStats)[]) acc[k] += st[k];
            return acc;
          },
          { worked: 0, vacant: 0, closed: 0, abandoned: 0, refused: 0, focuses: 0, treated: 0, visits: 0, res: 0, com: 0, tb: 0, pe: 0, out: 0, activeDays: 0 },
        );
        result.set(agent.id, { stats: totals, source: "summary", summaries: sums });
      }
    }
  }
  const isLoading =
    (!agents ? false : useRaw ? raw.isLoading : monthRaw ? summariesMonth.isLoading : summariesYear.isLoading);
  return { statsByAgent: result, isLoading };
}

function monthRange(year: number, month: number): { start: string; end: string } {
  const pad = (n: number) => String(n).padStart(2, "0");
  const lastDay = new Date(year, month + 1, 0).getDate();
  return { start: `01-${pad(month + 1)}-${year}`, end: `${pad(lastDay)}-${pad(month + 1)}-${year}` };
}

export interface FetchDateRange { start: string; end: string }

// O campo `data` é DD-MM-YYYY e NÃO ordena lexicograficamente entre meses
// (ex.: semana 26-07-2026..01-08-2026 teria início > fim e retornaria vazio).
// Para semanas que invadem o mês vizinho, busca cada mês cheio envolvido;
// o recorte exato da semana é aplicado no cliente via filterByPeriod.
// (Mantido p/ os testes; a query converte p/ ISO na coluna data_date.)
export function fetchRangesForPeriod(year: number, month: number, week: number): FetchDateRange[] {
  if (week >= 0 && month !== -1) {
    const s = weekStartFor(year, month, week);
    const e = weekEndFor(year, month, week);
    const first = monthRange(s.getFullYear(), s.getMonth());
    const last = monthRange(e.getFullYear(), e.getMonth());
    if (first.start === last.start) return [first];
    return [first, last];
  }
  if (month !== -1) return [monthRange(year, month)];
  return [{ start: `01-01-${year}`, end: `31-12-${year}` }];
}

function weekStartFor(year: number, month: number, week: number): Date {
  let date = new Date(year, month, 1, 0, 0, 0, 0);
  while (date.getDay() !== 0) date.setDate(date.getDate() - 1);
  date.setDate(date.getDate() + week * 7);
  return date;
}

function weekEndFor(year: number, month: number, week: number): Date {
  const start = weekStartFor(year, month, week);
  const end = new Date(start);
  end.setDate(start.getDate() + 6);
  end.setHours(23, 59, 59, 999);
  return end;
}

export function useAgentSnapshot(agentId: string, year: number, month: number, week: number) {
  return useQuery({
    queryKey: ["agent-snapshot", agentId, year, month, week],
    enabled: !!agentId,
    queryFn: async () => {
      // A aba Produção exibe só a semana selecionada: busca só esse recorte
      // (leitura do servidor a cada troca, sem dado desatualizado).
      const ranges = fetchRangesForPeriod(year, month, week);
      const b = isoBounds(ranges);
      const houseRes = await supabase
        .from("houses")
        .select("*")
        .eq("agent_id", agentId)
        .is("deleted_at", null)
        .gte("data_date", b?.start ?? "0001-01-01")
        .lte("data_date", b?.end ?? "9999-12-31");
      const actRes = await supabase
        .from("day_activities")
        .select("*")
        .eq("agent_id", agentId)
        .is("deleted_at", null)
        .gte("date_value", b?.start ?? "0001-01-01")
        .lte("date_value", b?.end ?? "9999-12-31");
      return { houses: must(houseRes).map(toHouseDoc), activities: must(actRes).map(toActivityDoc) };
    },
    staleTime: 30_000,
  });
}
// RG multi-agente: todas as casas do ano, de todos os agentes (sem dedup — paridade Android).
// Alimenta os dropdowns de bairro/quarteirão E a geração do PDF com uma única query.
// `enabled=false` = não busca (para telas que só precisam dela sob demanda).
// Sem collectionGroup e sem refiltro no cliente: data_date filtra exato no servidor.
export function useRgCoverage(year: number, enabled = true) {
  return useQuery({
    queryKey: ["rg-coverage", year],
    enabled,
    queryFn: async () => {
      const res = await supabase
        .from("houses")
        .select("*")
        .is("deleted_at", null)
        .gte("data_date", `${year}-01-01`)
        .lte("data_date", `${year}-12-31`);
      return must(res).map(toHouseDoc);
    },
    staleTime: 5 * 60_000,
  });
}
