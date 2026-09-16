import { useQuery } from "@tanstack/react-query";
import { collection, collectionGroup, getDocs, query, where, type QueryDocumentSnapshot } from "firebase/firestore";
import { db } from "../lib/firebase";
import { computeStats, dedupHouses, filterByPeriod, monthYearFromPeriod } from "../lib/period";
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

export function useSummariesByAgent(agents: AgentDoc[] | undefined, year: number, month: number) {
  const monthYear = monthYearFromPeriod(year, month);
  const agentKey = agents?.map((a) => a.id).sort().join(",") ?? "";
  return useQuery({
    queryKey: ["summaries", agentKey, year, month],
    enabled: !!agents?.length && monthYear !== null,
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

function chunkArray<T>(arr: T[], size: number): T[][] {
  const result: T[][] = [];
  for (let i = 0; i < arr.length; i += size) {
    result.push(arr.slice(i, i + size));
  }
  return result;
}

export function useYearSummariesByAgent(agents: AgentDoc[] | undefined, year: number) {
  const agentKey = agents?.map((a) => a.id).sort().join(",") ?? "";
  return useQuery({
    queryKey: ["summaries-year", agentKey, year],
    enabled: !!agents?.length,
    queryFn: async () => {
      const out = new Map<string, MonthlySummaryDoc[]>();
      const monthYears = Array.from({ length: 12 }, (_, i) => `${String(i + 1).padStart(2, "0")}-${year}`);
      const monthChunks = chunkArray(monthYears, 10);
      await Promise.all(
        (agents as AgentDoc[]).map(async (agent) => {
          const ref = collection(db, "agents", agent.id, "monthly_summaries");
          const snapPromises = monthChunks.map((chunk) =>
            getDocs(query(ref, where("monthYear", "in", chunk))),
          );
          const snapResults = await Promise.all(snapPromises);
          snapResults.forEach((snap) => {
            snap.docs.forEach((d) => {
              out.set(agent.id, [...(out.get(agent.id) || []), docToData<MonthlySummaryDoc>(d)]);
            });
          });
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
): { statsByAgent: Map<string, AgentPeriodStats>; isLoading: boolean } {
  const summariesMonth = useSummariesByAgent(agents, year, month);
  const summariesYear = useYearSummariesByAgent(agents, year);

  const monthRaw = monthYearFromPeriod(year, month);
  const useRaw = week >= 0 || monthRaw === null;

  const agentKey = agents?.map((a) => a.id).sort().join(",") ?? "";
  const raw = useQuery({
    queryKey: ["raw-period", agentKey, year, month, week],
    enabled: !!agents?.length && useRaw,
    queryFn: async () => {
      const out = new Map<string, { houses: HouseDoc[]; activities: DayActivityDoc[] }>();
      const { start, end } = week >= 0
        ? weekRange(year, month, week)
        : { start: `01-01-${year}`, end: `31-12-${year}` };
      await Promise.all(
        (agents as AgentDoc[]).map(async (agent) => {
          const housesRef = collection(db, "agents", agent.id, "houses");
          const actRef = collection(db, "agents", agent.id, "day_activities");
          const [hSnap, aSnap] = await Promise.all([
            getDocs(query(housesRef, where("data", ">=", start), where("data", "<=", end))),
            getDocs(actRef),
          ]);
          out.set(agent.id, {
            houses: hSnap.docs.map((d) => docToData<HouseDoc>(d)),
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
        const { valid } = dedupHouses(data.houses);
        result.set(agent.id, {
          stats: computeStats(valid, data.activities, year, month, week),
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

function weekRange(year: number, month: number, week: number): { start: string; end: string } {
  const fmt = (d: Date) => `${String(d.getDate()).padStart(2, "0")}-${String(d.getMonth() + 1).padStart(2, "0")}-${d.getFullYear()}`;
  return { start: fmt(weekStartFor(year, month, week)), end: fmt(weekEndFor(year, month, week)) };
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

export function useAgentActivitiesByPeriod(agents: AgentDoc[] | undefined, year: number, month: number, week: number) {
  const agentKey = agents?.map((a) => a.id).sort().join(",") ?? "";
  return useQuery({
    queryKey: ["activities-period", agentKey, year, month, week],
    enabled: !!agents?.length,
    queryFn: async () => {
      const out = new Map<string, DayActivityDoc[]>();
      await Promise.all(
        (agents as AgentDoc[]).map(async (agent) => {
          const snap = await getDocs(collection(db, "agents", agent.id, "day_activities"));
          const all = snap.docs.map((d) => docToData<DayActivityDoc>(d));
          out.set(agent.id, filterByPeriod(all, "date", year, month, week));
        }),
      );
      return out;
    },
    staleTime: 30_000,
  });
}

export function useAgentSnapshot(agentId: string) {
  return useQuery({
    queryKey: ["agent-snapshot", agentId],
    enabled: !!agentId,
    queryFn: async () => {
      const [hSnap, aSnap] = await Promise.all([
        getDocs(collection(db, "agents", agentId, "houses")),
        getDocs(collection(db, "agents", agentId, "day_activities")),
      ]);
      return {
        houses: hSnap.docs.map((d) => docToData<HouseDoc>(d)),
        activities: aSnap.docs.map((d) => docToData<DayActivityDoc>(d)),
      };
    },
    staleTime: 30_000,
  });
}
// RG multi-agente: todas as casas do ano, de todos os agentes (sem dedup — paridade Android).
// Alimenta os dropdowns de bairro/quarteirão E a geração do PDF com uma única query.
export function useRgCoverage(year: number) {
  return useQuery({
    queryKey: ["rg-coverage", year],
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
