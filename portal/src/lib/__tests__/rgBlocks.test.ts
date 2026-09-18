import { describe, expect, it } from "vitest";
import { getRGBlocks } from "../rgBlocks";
import { houseYear, createdAtMs, houseDateTs } from "../period";
import type { HouseDoc } from "../types";

const MASTER_BAIRROS = ["CENTRO", "SÃO JOSÉ", "ALVES", "SANTO ANTÔNIO"];
const MASTER_AGENTS = ["BEATRIZ MONTEIRO", "GUILHERME MELLO", "IANÊ ALVES"];

function house(overrides: Partial<HouseDoc> & Pick<HouseDoc, "id">): HouseDoc {
  return {
    data: "01-01-2026",
    bairro: "CENTRO",
    blockNumber: "10",
    blockSequence: "",
    streetName: "RUA TESTE",
    number: "1",
    situation: "NONE",
    agentName: "GUILHERME MELLO",
    agentUid: "uid-g",
    listOrder: 0,
    createdAt: 1700000000000,
    ...overrides,
  } as HouseDoc;
}

describe("house helpers", () => {
  it("houseYear extrai o ano direto do data", () => {
    expect(houseYear(house({ data: "15-08-2026" }))).toBe(2026);
    expect(houseYear(house({ data: "31/12/2025" }))).toBe(2025);
    expect(houseYear(house({ data: "abc" }))).toBeNull();
    expect(houseYear(house({ data: "01-01-999" }))).toBeNull();
  });

  it("createdAtMs aceita number e { seconds }", () => {
    expect(createdAtMs(house({ createdAt: 1234 }))).toBe(1234);
    expect(createdAtMs(house({ createdAt: { seconds: 5 } }))).toBe(5000);
    expect(createdAtMs(house({ createdAt: undefined }))).toBe(Number.MAX_SAFE_INTEGER);
  });

  it("houseDateTs usa dia/mes/ano reais (ordem cronológica)", () => {
    expect(houseDateTs(house({ data: "01-02-2026" }))).toBeLessThan(houseDateTs(house({ data: "01-03-2026" })));
    expect(houseDateTs(house({ data: "31-12-2025" }))).toBeLessThan(houseDateTs(house({ data: "01-01-2026" })));
    expect(houseDateTs(house({ data: "" }))).toBe(0);
  });
});

describe("getRGBlocks — filtros", () => {
  it("não vaza outros anos (data em DD-MM-YYYY)", () => {
    const houses = [
      house({ id: "a", data: "31-12-2025", blockNumber: "10" }),
      house({ id: "b", data: "05-05-2026", blockNumber: "10" }),
      house({ id: "c", data: "01-01-2027", blockNumber: "10" }),
      house({ id: "d", data: "15-01-2026", blockNumber: "10" }),
    ];
    const blocks = getRGBlocks(houses, "CENTRO", "2026", MASTER_BAIRROS, MASTER_AGENTS);
    // Somente os segmentos de 2026, com as casas certas
    expect(blocks).toHaveLength(1);
    expect(blocks[0].houses.map((h) => h.id).sort()).toEqual(["b", "d"]);
  });

  it("filtra por bairro com heal (acentos/caixa)", () => {
    const houses = [
      house({ id: "b1", bairro: "SÃO JOSÉ", blockNumber: "1" }),
      house({ id: "b2", bairro: "SAO JOSE", blockNumber: "1" }),
      house({ id: "outro", bairro: "CENTRO", blockNumber: "99" }),
    ];
    // Selecionando com nome sem acento, deve casar com as duas "SÃO JOSÉ"
    const blocks = getRGBlocks(houses, "SAO JOSE", "2026", MASTER_BAIRROS, MASTER_AGENTS);
    expect(blocks).toHaveLength(1);
    expect(blocks[0].houses.map((h) => h.id).sort()).toEqual(["b1", "b2"]);
  });

  it("retorna vazio sem bairro selecionado", () => {
    expect(getRGBlocks([house({ id: "a" })], "", "2026", MASTER_BAIRROS, MASTER_AGENTS)).toEqual([]);
  });
});

describe("getRGBlocks — segmentação", () => {
  it("particiona em segmentos por quarteiraoConcluido/localidadeConcluida", () => {
    const houses = [
      house({ id: "h1", blockNumber: "10", listOrder: 0 }),
      house({ id: "h2", blockNumber: "10", listOrder: 1, quarteiraoConcluido: true }),
      house({ id: "h3", blockNumber: "10", listOrder: 2 }),
    ];
    const blocks = getRGBlocks(houses, "CENTRO", "2026", MASTER_BAIRROS, MASTER_AGENTS);
    expect(blocks).toHaveLength(2);
    expect(blocks[0].isConcluded).toBe(true);
    expect(blocks[0].houses.map((h) => h.id)).toEqual(["h1", "h2"]);
    expect(blocks[1].isConcluded).toBe(false);
    expect(blocks[1].houses.map((h) => h.id)).toEqual(["h3"]);
  });

  it("ordena por data, depois agente que começou primeiro, listOrder e id", () => {
    const houses = [
      // dia 2 começado por Beatriz; dia 1 por Guilherme
      house({ id: "g1", data: "10-01-2026", blockNumber: "10", agentUid: "uid-g", agentName: "GUILHERME MELLO", listOrder: 5, createdAt: 100 }),
      house({ id: "b1", data: "11-01-2026", blockNumber: "10", agentUid: "uid-b", agentName: "BEATRIZ MONTEIRO", listOrder: 1, createdAt: 50 }),
      house({ id: "g2", data: "10-01-2026", blockNumber: "10", agentUid: "uid-g", agentName: "GUILHERME MELLO", listOrder: 6, createdAt: 101 }),
    ];
    const blocks = getRGBlocks(houses, "CENTRO", "2026", MASTER_BAIRROS, MASTER_AGENTS);
    expect(blocks).toHaveLength(1);
    // Por dia: g1,g2 (dia 10) antes de b1 (dia 11); dentro do dia, por listOrder
    expect(blocks[0].houses.map((h) => h.id)).toEqual(["g1", "g2", "b1"]);
  });

  it("conclusão implícita: último segmento sem flag mas seguido de outro quarteirão no percurso", () => {
    const houses = [
      // Percurso global: bloco 10 dia 1, bloco 10 dia 5 (reaberto), bloco 11 (último global)
      house({ id: "a1", data: "01-01-2026", blockNumber: "10", listOrder: 0 }),
      house({ id: "a2", data: "05-01-2026", blockNumber: "10", listOrder: 1 }),
      house({ id: "z1", data: "06-01-2026", blockNumber: "11", listOrder: 0 }),
    ];
    const blocks = getRGBlocks(houses, "CENTRO", "2026", MASTER_BAIRROS, MASTER_AGENTS);
    // Bloco 10: 1 segmento; a2 não é o último do percurso global (z1 é) → implicitamente concluído
    const block10 = blocks.filter((b) => b.blockNumber === "10");
    const block11 = blocks.filter((b) => b.blockNumber === "11");
    expect(block10).toHaveLength(1);
    expect(block10[0].isConcluded).toBe(true);
    expect(block11).toHaveLength(1);
    expect(block11[0].isConcluded).toBe(false);
  });

  it("participatingAgents usa a lista mestra (healAgentName)", () => {
    const houses = [
      house({ id: "p1", blockNumber: "10", agentName: "GUILHERME MELLO" }),
      house({ id: "p2", blockNumber: "10", agentName: "guilherme mello" }),
      house({ id: "p3", blockNumber: "10", agentName: "IANÊ ALVES" }),
    ];
    const blocks = getRGBlocks(houses, "CENTRO", "2026", MASTER_BAIRROS, MASTER_AGENTS);
    expect(blocks).toHaveLength(1);
    expect(blocks[0].participatingAgents).toEqual(["GUILHERME MELLO", "IANÊ ALVES"]);
  });
});

describe("getRGBlocks — signature", () => {
  it("id e datas espelham o BlockSegment do Android", () => {
    const houses = [
      house({ id: "s1", data: "01-01-2026", blockNumber: "10", blockSequence: "2", listOrder: 0 }),
      house({ id: "s2", data: "02-01-2026", blockNumber: "10", blockSequence: "2", listOrder: 1 }),
    ];
    const blocks = getRGBlocks(houses, "CENTRO", "2026", MASTER_BAIRROS, MASTER_AGENTS);
    expect(blocks).toHaveLength(1);
    const s = blocks[0];
    expect(s.blockNumber).toBe("10");
    expect(s.blockSequence).toBe("2");
    expect(s.startDate).toBe("01-01-2026");
    expect(s.endDate).toBe("02-01-2026");
    expect(s.houses.map((h) => h.id)).toEqual(["s1", "s2"]);
    expect(s.id).toBe(`10_2_O_01-01-2026_s1`);
  });
});