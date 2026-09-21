import { describe, it, expect } from "vitest";
import {
  monthYearOf, monthDateBounds, dateToInt, computeMonthlySummary,
  buildUpdatePayload, houseToForm, lastUpdatedMs,
  validateRowLabels, findDuplicateIds, computeDayTotals, treatmentSummary,
  type VisitForm,
} from "../adminProduction";
import type { HouseDoc } from "../types";

const BASE_FORM: VisitForm = {
  data: "10-08-2026", streetName: "Rua A", number: "10", complement: 0, sequence: 1,
  blockNumber: "12", blockSequence: "", bairro: "CENTRO", situation: "NONE", propertyType: "R",
  a1: 0, a2: 0, b: 0, c: 0, d1: 0, d2: 0, e: 0,
  eliminados: 0, larvicida: 0, comFoco: false, observation: "",
  visitSegment: 0, listOrder: 0,
};

describe("monthYearOf / monthDateBounds / dateToInt", () => {
  it("extracts MM-YYYY", () => {
    expect(monthYearOf("10-08-2026")).toBe("08-2026");
    expect(monthYearOf("10/08/2026")).toBe("08-2026");
    expect(monthYearOf("bad")).toBeNull();
    expect(monthYearOf("99-99-2026")).toBeNull();
  });
  it("bounds a month", () => {
    expect(monthDateBounds("08-2026")).toEqual({ start: "01-08-2026", end: "31-08-2026" });
    expect(monthDateBounds("02-2024")).toEqual({ start: "01-02-2024", end: "29-02-2024" });
    expect(monthDateBounds("xx")).toBeNull();
  });
  it("converts to int", () => {
    expect(dateToInt("10-08-2026")).toBe(20260810);
    expect(dateToInt("bad")).toBeNull();
  });
});

describe("computeMonthlySummary (paridade Android)", () => {
  const houses = [
    { id: "1", data: "10-08-2026", situation: "NONE", propertyType: "R", a1: 1 },
    { id: "2", data: "11-08-2026", situation: "F", propertyType: "C", comFoco: true },
    { id: "3", data: "12-08-2026", situation: "EMPTY", propertyType: "R" },
    { id: "4", data: "10-09-2026", situation: "NONE", propertyType: "R" },
    { id: "5", data: "10-08-2099", situation: "NONE", propertyType: "R" },
  ] as HouseDoc[];
  const activities = [{ date: "10-08-2026" }, { date: "11-08-2026" }, { date: "10-09-2026" }];
  it("counts month only, EMPTY->NONE, excludes future", () => {
    const s = computeMonthlySummary(houses, activities, "08-2026", 20260831);
    expect(s.totalHouses).toBe(3);
    expect(s.situationCounts).toEqual({ NONE: 2, F: 1 });
    expect(s.propertyTypeCounts).toEqual({ R: 2, C: 1 });
    expect(s.treatedCount).toBe(2);
    expect(s.focusCount).toBe(1);
    expect(s.daysWorked).toBe(2);
    expect(s.monthYear).toBe("08-2026");
  });
});

describe("buildUpdatePayload", () => {
  const orig = {
    id: "abc", data: "10-08-2026", streetName: "Rua A", number: "10",
    situation: "NONE", propertyType: "R", a1: 0, comFoco: false,
  } as HouseDoc;
  it("includes only changed fields + admin stamp", () => {
    const form = { ...BASE_FORM, situation: "F" };
    const p = buildUpdatePayload(orig, form);
    expect(p["situation"]).toBe("F");
    expect(p["editedByAdmin"]).toBe(true);
    expect(p["lastUpdated"]).toBeDefined();
    expect("streetName" in p).toBe(false);
    expect("number" in p).toBe(false);
  });
});

describe("validateRowLabels (paridade app)", () => {
  it("clean row has no labels", () => {
    expect(validateRowLabels(BASE_FORM, false)).toEqual([]);
  });
  it("flags missing address pieces", () => {
    expect(validateRowLabels({ ...BASE_FORM, number: "", sequence: 0 }, false)).toContain("SEM Nº");
    expect(validateRowLabels({ ...BASE_FORM, bairro: "" }, false)).toContain("SEM BAIRRO");
    expect(validateRowLabels({ ...BASE_FORM, streetName: "" }, false)).toContain("SEM RUA");
    expect(validateRowLabels({ ...BASE_FORM, blockNumber: "" }, false)).toContain("SEM QUART.");
    expect(validateRowLabels({ ...BASE_FORM, propertyType: "EMPTY" }, false)).toContain("SEM TIPO");
  });
  it("flags treatment inconsistencies", () => {
    expect(validateRowLabels({ ...BASE_FORM, situation: "F", a1: 1 }, false)).toContain("TRAT. INDEVIDO");
    expect(validateRowLabels({ ...BASE_FORM, larvicida: 1 }, false)).toContain("LARV. SEM DEP.");
    expect(validateRowLabels({ ...BASE_FORM, a1: 2, larvicida: 0 }, false)).toContain("DEP. SEM LARV.");
    expect(validateRowLabels({ ...BASE_FORM, a1: 2, larvicida: 1 }, false)).toEqual([]);
  });
  it("flags duplicates", () => {
    expect(validateRowLabels(BASE_FORM, true)).toContain("DUPLICADO");
  });
});

describe("findDuplicateIds", () => {
  it("marks same-signature rows", () => {
    const ids = findDuplicateIds([
      { id: "1", form: BASE_FORM },
      { id: "2", form: { ...BASE_FORM } },
      { id: "3", form: { ...BASE_FORM, number: "20" } },
    ]);
    expect([...ids].sort()).toEqual(["1", "2"]);
  });
});

describe("computeDayTotals / treatmentSummary", () => {
  it("sums deposits, focus and worked", () => {
    const t = computeDayTotals([
      BASE_FORM,
      { ...BASE_FORM, a1: 2, larvicida: 1, comFoco: true, situation: "F" },
    ]);
    expect(t.visits).toBe(2);
    expect(t.a1).toBe(2);
    expect(t.larvicida).toBe(1);
    expect(t.focuses).toBe(1);
    expect(t.worked).toBe(1);
  });
  it("formats treatment summary", () => {
    expect(treatmentSummary(BASE_FORM)).toBe("");
    expect(treatmentSummary({ ...BASE_FORM, a1: 1, larvicida: 0.5 })).toBe("A1: 1 | Larv: 0.5g");
  });
});

describe("houseToForm", () => {
  it("fills defaults for missing fields", () => {
    const f = houseToForm({ id: "x", data: "10-08-2026" } as HouseDoc);
    expect(f.situation).toBe("NONE");
    expect(f.sequence).toBe(1);
    expect(f.comFoco).toBe(false);
  });
});

describe("lastUpdatedMs", () => {
  it("parses number, Timestamp-like, null", () => {
    expect(lastUpdatedMs(123)).toBe(123);
    expect(lastUpdatedMs({ seconds: 10 })).toBe(10000);
    expect(lastUpdatedMs(null)).toBeNull();
    expect(lastUpdatedMs(undefined)).toBeNull();
  });
});
