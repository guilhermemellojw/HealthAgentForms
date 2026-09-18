import { useQuery } from "@tanstack/react-query";
import { collection, collectionGroup, getDocs, query, where, type QueryDocumentSnapshot } from "firebase/firestore";
import { db } from "../lib/firebase";
import { computeStats, filterByBairro, monthYearFromPeriod, normalizeBairro } from "../lib/period";
import type { AgentDoc, DayActivityDoc, HouseDoc, HouseStats, MonthlySummaryDoc } from "../lib/types";

function docToData<T>(snap: QueryDocumentSnapshot): T {
  return { id: snap.id, ...snap.data() } as T;
}

export function useAgents() {
  return useQuery({
    queryKey: ["agents"],
    queryFn: async () => {
      const snap = await getDocs(collection(db, "agents"));
      return snap.docs.map((d) => docToData<AgentDoc>(d));
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

export function useSummariesByAgent(agents: AgentDoc[] | undefined, year: number, month: number, enabled = true) {
  const monthYear = monthYearFromPeriod(year, month);
  const agentKey = agents?.map((a) => a.id).sort().join(",") ?? "";
  return useQuery({
    queryKey: ["summaries", agentKey, year, month],
    enabled: enabled && !!agents?.length && monthYear !== null,
    queryFn: async () => {
      const out = new Map<string, MonthlySummaryDoc[]>();
      const target = monthYear as string;
      await Promise.all(
        (agents as AgentDoc[]).map(async (agent) => {
          const ref = collection(db, "agents", agent.id, "monthly_summaries");
          const q = query(ref, where("monthYear", "==", target));
          const snap = await getDocs(q);
          out.set(agent.id, snap.docs.map((d) => docToData<MonthlySummaryDoc>(d)));
        }),
      );
      return out;
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
      const out = new Map<string, MonthlySummaryDoc[]>();
      await Promise.all(
        (agents as AgentDoc[]).map(async (agent) => {
          // monthYear é "MM-AAAA" (largura fixa): o range cobre exato o ano.
          const ref = collection(db, "agents", agent.id, "monthly_summaries");
          const snap = await getDocs(
            query(ref, where("monthYear", ">=", `01-${year}`), where("monthYear", "<=", `12-${year}`)),
          );
          out.set(
            agent.id,
            snap.docs.map((d) => docToData<MonthlySummaryDoc>(d)),
          );
        }),
      );
      return out;
    },
    staleTime: 60_000,
  });
}

export interface AgentPeriodStats {
  stats: HouseStats;
  source: "summary" | "raw";
  summaries: MonthlySummaryDoc[];
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
          const actRef = collection(db, "agents", agent.id, "day_activities");
          const [houses, aSnap] = await Promise.all([
            fetchHousesInRanges(agent.id, ranges),
            getDocs(actRef),
          ]);
          out.set(agent.id, {
            houses,
            activities: aSnap.docs.map((d) => docToData<DayActivityDoc>(d)),
          });
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
        // Sem dedup por campos: identidade é o doc id (uuid) e os ranges já
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

async function fetchSubcollectionInRanges<T>(agentId: string, sub: string, field: string, ranges: FetchDateRange[]): Promise<T[]> {
  const snaps = await Promise.all(
    ranges.map((r) =>
      getDocs(
        query(
          collection(db, "agents", agentId, sub),
          where(field, ">=", r.start),
          where(field, "<=", r.end),
        ),
      ),
    ),
  );
  const seen = new Set<string>();
  const out: T[] = [];
  for (const snap of snaps) {
    for (const d of snap.docs) {
      if (seen.has(d.id)) continue;
      seen.add(d.id);
      out.push(docToData<T>(d));
    }
  }
  return out;
}

async function fetchHousesInRanges(agentId: string, ranges: FetchDateRange[]): Promise<HouseDoc[]> {
  return fetchSubcollectionInRanges<HouseDoc>(agentId, "houses", "data", ranges);
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
      const [houses, activities] = await Promise.all([
        fetchHousesInRanges(agentId, ranges),
        fetchSubcollectionInRanges<DayActivityDoc>(agentId, "day_activities", "date", ranges),
      ]);
      return { houses, activities };
    },
    staleTime: 30_000,
  });
}
// RG multi-agente: todas as casas do ano, de todos os agentes (sem dedup — paridade Android).
// Alimenta os dropdowns de bairro/quarteirão E a geração do PDF com uma única query.
// `enabled=false` = não busca (para telas que só precisam dela sob demanda).
export function useRgCoverage(year: number, enabled = true) {
  return useQuery({
    queryKey: ["rg-coverage", year],
    enabled,
    queryFn: async () => {
      const q = query(
        collectionGroup(db, "houses"),
        where("data", ">=", `01-01-${year}`),
        where("data", "<=", `31-12-${year}`),
      );
      const snap = await getDocs(q);
      return snap.docs.map((d) => docToData<HouseDoc>(d));
    },
    staleTime: 5 * 60_000,
  });
}
