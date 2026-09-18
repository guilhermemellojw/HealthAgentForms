import { beforeEach, describe, expect, it, vi } from "vitest";
import { generateWeeklyBatchPdf } from "../pdf/weeklyBatch";
import type { HouseDoc } from "../types";

const calls: string[] = [];

vi.mock("jspdf", () => {
  class JsPDFMock {
    addPage() {}
    output() {
      return new Blob();
    }
  }
  return { jsPDF: vi.fn(function () { return new JsPDFMock(); }) };
});

vi.mock("../pdf/boletim", async (importOriginal) => {
  const original = await importOriginal<typeof import("../pdf/boletim")>();
  return {
    ...original,
    calculateBlockStats: () => ({
      workedBlocks: [],
      completedBlocks: [],
      quarteiraoConcluido: false,
      localidadeConcluida: false,
    }),
    drawFrontPage: vi.fn(async (_doc, chunk: HouseDoc[], date: string) => {
      calls.push(`front:${date}:${chunk[0]?.id}`);
    }),
    drawBackPage: vi.fn((_doc, chunk: HouseDoc[], date: string) => {
      calls.push(`back:${date}:${chunk[0]?.id}`);
    }),
  };
});

vi.mock("../pdf/semanal", () => ({
  drawSemanalPage: vi.fn(async () => {
    calls.push("semanal");
  }),
}));

vi.mock("../pdf/shared", () => ({
  setLine: vi.fn(),
}));

function makeHouses(prefix: string, count: number, bairro: string): HouseDoc[] {
  return Array.from({ length: count }, (_, i) => ({
    id: `${prefix}_${i + 1}`,
    data: prefix,
    bairro,
    listOrder: i,
    situation: "NONE",
    propertyType: "R",
  })) as HouseDoc[];
}

describe("generateWeeklyBatchPdf ordering", () => {
  beforeEach(() => {
    calls.length = 0;
  });

  it("prints all front pages ascending, then all back pages descending (n..1), then weekly summary", async () => {
    const d1 = "01-02-2026";
    const d2 = "02-02-2026";
    const d3 = "03-02-2026";

    const weeklyData = {
      [d1]: makeHouses("D1", 25, "CENTRO"), // 2 chunks
      [d2]: makeHouses("D2", 10, "NOVA"), // 1 chunk
      [d3]: makeHouses("D3", 10, "NOVA"), // 1 chunk
    };

    await generateWeeklyBatchPdf(weeklyData, "Agente", {}, [d1, d2, d3]);

    expect(calls).toEqual([
      // Pass 1: all fronts, days asc, chunks asc
      `front:${d1}:D1_1`,
      `front:${d1}:D1_21`,
      `front:${d2}:D2_1`,
      `front:${d3}:D3_1`,
      // Pass 2: all backs, globally reversed n..1
      `back:${d3}:D3_1`,
      `back:${d2}:D2_1`,
      `back:${d1}:D1_21`,
      `back:${d1}:D1_1`,
      // Pass 3: weekly summary last
      "semanal",
    ]);
  });

  it("orders backs globally reversed across multiple days with varying chunk counts", async () => {
    const d1 = "10-01-2026";
    const d2 = "11-01-2026";
    const weeklyData = {
      [d1]: makeHouses("A", 41, "CENTRO"), // 3 chunks (20+20+1)
      [d2]: makeHouses("B", 21, "CENTRO"), // 2 chunks (20+1)
    };

    await generateWeeklyBatchPdf(weeklyData, "Agente", {}, [d1, d2]);

    const frontCalls = calls.filter((c) => c.startsWith("front"));
    const backCalls = calls.filter((c) => c.startsWith("back"));

    expect(frontCalls).toEqual([
      `front:${d1}:A_1`,
      `front:${d1}:A_21`,
      `front:${d1}:A_41`,
      `front:${d2}:B_1`,
      `front:${d2}:B_21`,
    ]);
    // Backs must be the exact reverse of the fronts, per chunk
    const key = (c: string) => c.replace(/^(front|back):/, "");
    expect(backCalls.map(key)).toEqual(frontCalls.map(key).reverse());
    expect(calls[calls.length - 1]).toBe("semanal");
  });
});