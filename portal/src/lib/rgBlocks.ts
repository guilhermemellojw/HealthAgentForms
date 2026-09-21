import type { HouseDoc } from "./types";
import { createdAtMs, houseDateTs, houseYear, normalizeField, removeAccents } from "./period";

// Port 1:1 de GetRGBlocksUseCase (app Android).
// Filtra por bairro + ano (ano lido do data de cada casa, como o app) e
// agrupa em segmentos cronologicos de quarteirao com conclusao implicita.

export interface RGBlockSegment {
  id: string;
  blockNumber: string;
  blockSequence: string;
  startDate: string;
  endDate: string;
  isConcluded: boolean;
  conclusionDate: string | null;
  houses: HouseDoc[];
  participatingAgents: string[];
}

function healBairro(value: unknown, masterBairros: string[]): string {
  const normalized = normalizeField(value);
  const match = masterBairros.find(
    (it) => removeAccents(it).toLowerCase() === removeAccents(normalized).toLowerCase(),
  );
  return match ?? normalized;
}

function healAgentName(value: unknown, masterAgentNames: string[]): string {
  const normalized = normalizeField(value);
  const match = masterAgentNames.find(
    (it) => removeAccents(it).toLowerCase() === removeAccents(normalized).toLowerCase(),
  );
  return match ?? normalized;
}

function blockKeyOf(blockNumber: string, blockSequence: string): string {
  return `${blockNumber}|${blockSequence}`;
}

function compareByVisits(a: HouseDoc, b: HouseDoc, earliestOnDay: Map<string, number>): number {
  const da = houseDateTs(a) - houseDateTs(b);
  if (da) return da;
  const ka = `${houseDateTs(a)}|${a.agentUid ?? ""}`;
  const kb = `${houseDateTs(b)}|${b.agentUid ?? ""}`;
  const wa = (earliestOnDay.get(ka) ?? Number.MAX_SAFE_INTEGER) - (earliestOnDay.get(kb) ?? Number.MAX_SAFE_INTEGER);
  if (wa) return wa;
  const an = (a.agentName ?? "").localeCompare(b.agentName ?? "");
  if (an) return an;
  const lo = (a.listOrder ?? 0) - (b.listOrder ?? 0);
  if (lo) return lo;
  return (a.id ?? "").localeCompare(b.id ?? "");
}

function compareByGlobal(a: HouseDoc, b: HouseDoc): number {
  const da = houseDateTs(a) - houseDateTs(b);
  if (da) return da;
  const an = (a.agentName ?? "").localeCompare(b.agentName ?? "");
  if (an) return an;
  const lo = (a.listOrder ?? 0) - (b.listOrder ?? 0);
  if (lo) return lo;
  return (a.id ?? "").localeCompare(b.id ?? "");
}

export function getRGBlocks(
  allHouses: HouseDoc[],
  selectedBairro: string,
  selectedYear: string,
  masterBairros: string[],
  masterAgentNames: string[],
): RGBlockSegment[] {
  if (!selectedBairro) return [];
  const healedSelectedBairro = healBairro(selectedBairro, masterBairros);

  // 1. EARLY FILTERING: bairro selecionado + ano (parse do data casa a casa)
  const initialFiltered = allHouses.filter((house) => {
    const matchesBairro = healBairro(house.bairro ?? "", masterBairros) === healedSelectedBairro;
    const matchesYear = selectedYear === "" || (() => {
      const y = houseYear(house);
      if (y != null) return y === Number(selectedYear);
      return (house.data ?? "").includes(selectedYear);
    })();
    return matchesBairro && matchesYear;
  });

  if (initialFiltered.length === 0) return [];

  // 1b. GLOBAL PERCURSO (unfiltered) — julga conclusao implicita globalmente
  const globalSortedVisits = [...allHouses].sort(compareByGlobal);

  // 2. FILTERED SORTING (para exibicao)
  const sortedVisits = [...initialFiltered].sort(compareByGlobal);

  // 3. GROUP BY BLOCK e particionamento cronologico
  const blockGroups = new Map<string, HouseDoc[]>();
  for (const h of sortedVisits) {
    const key = blockKeyOf(normalizeField(h.blockNumber), normalizeField(h.blockSequence));
    const list = blockGroups.get(key);
    if (list) list.push(h);
    else blockGroups.set(key, [h]);
  }

  const blockOrder = [
    ...new Set(
      sortedVisits.map((h) => blockKeyOf(normalizeField(h.blockNumber), normalizeField(h.blockSequence))),
    ),
  ];

  const allSegments: RGBlockSegment[] = [];

  for (const key of blockOrder) {
    const [bNum, bSeq] = key.split("|");
    const unsortedBlockHouses = blockGroups.get(key) ?? [];

    const agentEarliestTimeOnDay = new Map<string, number>();
    for (const h of unsortedBlockHouses) {
      const k = `${houseDateTs(h)}|${h.agentUid ?? ""}`;
      const c = createdAtMs(h);
      const cur = agentEarliestTimeOnDay.get(k);
      if (cur === undefined || c < cur) agentEarliestTimeOnDay.set(k, c);
    }

    const blockHouses = [...unsortedBlockHouses].sort((a, b) =>
      compareByVisits(a, b, agentEarliestTimeOnDay),
    );

    const partitions: HouseDoc[][] = [];
    let currentPartition: HouseDoc[] = [];
    for (const house of blockHouses) {
      currentPartition.push(house);
      if (house.quarteiraoConcluido || house.localidadeConcluida) {
        partitions.push(currentPartition);
        currentPartition = [];
      }
    }
    if (currentPartition.length > 0) partitions.push(currentPartition);

    for (const partition of partitions) {
      const lastHouse = partition[partition.length - 1];
      const lastHouseId = lastHouse?.id ?? "";

      let indexOfLastInGlobal = -1;
      for (let i = globalSortedVisits.length - 1; i >= 0; i--) {
        if (globalSortedVisits[i].id === lastHouseId) {
          indexOfLastInGlobal = i;
          break;
        }
      }
      const isImplicitlyConcluded =
        indexOfLastInGlobal !== -1 && indexOfLastInGlobal < globalSortedVisits.length - 1;
      const isConcluded =
        partition.some((it) => it.quarteiraoConcluido || it.localidadeConcluida) ||
        isImplicitlyConcluded;

      const participatingAgents = [
        ...new Set(
          partition
            .map((h) => healAgentName(h.agentName ?? "", masterAgentNames))
            .filter((n) => n !== ""),
        ),
      ];
      const startDate = partition[0]?.data ?? "";
      const conclusionDate = lastHouse?.data ?? null;

      allSegments.push(
        {
          blockNumber: bNum ?? "",
          blockSequence: bSeq ?? "",
          startDate,
          endDate: lastHouse?.data ?? "",
          isConcluded,
          conclusionDate,
          houses: partition,
          participatingAgents,
          id: `${bNum}_${bSeq}_${isConcluded ? "C" : "O"}_${startDate}_${partition[0]?.id ?? 0}`,
        },
      );
    }
  }

  return allSegments;
}