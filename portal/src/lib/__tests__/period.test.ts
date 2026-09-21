import { describe, it, expect } from "vitest";
import { getWeeksForMonth, isInPeriod, parseDate, weekIndexForDate, matchesBairro, filterByBairro, computeStats, matchesWeekday, filterByPeriod } from "../period";
import type { HouseDoc } from "../types";

describe("getWeeksForMonth", () => {
  it("starts on Sunday before month start", () => {
    // Aug 2026: 1st is Saturday => week 0 starts Sun 2026-07-26
    const weeks = getWeeksForMonth(2026, 7);
    expect(weeks[0].start.getDay()).toBe(0);
    expect(weeks[0].start.getDate()).toBe(26);
    expect(weeks[0].start.getMonth()).toBe(6); // July
  });

  it("generates 5-6 weeks and labels", () => {
    const weeks = getWeeksForMonth(2026, 0); // Jan 2026
    expect(weeks.length).toBeGreaterThanOrEqual(4);
    expect(weeks.length).toBeLessThanOrEqual(6);
    expect(weeks[0].label).toMatch(/^Semana 1/);
  });

  it("does not include future weeks beyond today", () => {
    const future = new Date();
    future.setFullYear(future.getFullYear() + 2);
    const weeks = getWeeksForMonth(future.getFullYear(), 0);
    // all weeks should start before now (implementation breaks on future)
    for (const w of weeks) {
      expect(w.start.getTime()).toBeLessThan(Date.now() + 7 * 86400000);
    }
  });
});

describe("parseDate", () => {
  it("parses DD-MM-YYYY at noon", () => {
    const d = parseDate("15-08-2026");
    expect(d.getFullYear()).toBe(2026);
    expect(d.getMonth()).toBe(7);
    expect(d.getDate()).toBe(15);
    expect(d.getHours()).toBe(12);
  });
  it("returns epoch for invalid", () => {
    expect(parseDate("invalid").getTime()).toBe(new Date(0).getTime());
  });
});

describe("isInPeriod", () => {
  it("matches year when month=-1", () => {
    expect(isInPeriod("15-08-2026", 2026, -1, -1)).toBe(true);
    expect(isInPeriod("15-08-2025", 2026, -1, -1)).toBe(false);
  });
  it("matches month via endsWith", () => {
    expect(isInPeriod("15-08-2026", 2026, 7, -1)).toBe(true);
    expect(isInPeriod("15-09-2026", 2026, 7, -1)).toBe(false);
  });
  it("accepts slash format", () => {
    expect(isInPeriod("15/08/2026", 2026, 7, -1)).toBe(true);
  });
  it("matches week index", () => {
    // Aug 2026 week 0 is 26/07-01/08, so 27/07 is inside week 0, 10/08 is not
    expect(isInPeriod("27-07-2026", 2026, 7, 0)).toBe(true);
    expect(isInPeriod("10-08-2026", 2026, 7, 0)).toBe(false);
  });
  it("rejects future dates", () => {
    const future = new Date();
    future.setFullYear(future.getFullYear() + 1);
    const d = `${String(future.getDate()).padStart(2, "0")}-${String(future.getMonth() + 1).padStart(2, "0")}-${future.getFullYear()}`;
    expect(isInPeriod(d, future.getFullYear(), future.getMonth(), -1)).toBe(false);
  });
});

describe("weekIndexForDate", () => {
  it("returns 0 for Jan 1", () => {
    expect(weekIndexForDate(new Date(2026, 0, 1))).toBe(0);
  });
  it("returns 1 for Jan 8", () => {
    expect(weekIndexForDate(new Date(2026, 0, 8))).toBe(1);
  });
});

describe("matchesBairro / filterByBairro", () => {
  it("empty filter matches everything", () => {
    expect(matchesBairro("Centro", "")).toBe(true);
    expect(matchesBairro(undefined, "")).toBe(true);
  });
  it("matches case/accent-insensitive", () => {
    expect(matchesBairro("são josé", "SAO JOSE")).toBe(true);
    expect(matchesBairro("CENTRO", "centro")).toBe(true);
  });
  it("rejects other bairros and blank house bairro", () => {
    expect(matchesBairro("Centro", "Alto")).toBe(false);
    expect(matchesBairro("", "Centro")).toBe(false);
    expect(matchesBairro(undefined, "Centro")).toBe(false);
  });
  it("filterByBairro keeps only selected bairro", () => {
    const houses = [
      { id: "1", bairro: "Centro" },
      { id: "2", bairro: "centro" },
      { id: "3", bairro: "Alto" },
    ] as HouseDoc[];
    expect(filterByBairro(houses, "").length).toBe(3);
    expect(filterByBairro(houses, "CENTRO").map((h) => h.id)).toEqual(["1", "2"]);
  });
});

describe("computeStats with bairro", () => {
  it("filters visits by bairro and counts distinct days", () => {
    const houses = [
      { id: "1", data: "10-08-2026", bairro: "Centro", situation: "NONE", propertyType: "R" },
      { id: "2", data: "10-08-2026", bairro: "Centro", situation: "NONE", propertyType: "R" },
      { id: "3", data: "11-08-2026", bairro: "Alto", situation: "NONE", propertyType: "R" },
    ] as HouseDoc[];
    const activities = [{ date: "10-08-2026" }, { date: "11-08-2026" }, { date: "12-08-2026" }];
    const all = computeStats(houses, activities, 2026, 7, -1);
    expect(all.visits).toBe(3);
    expect(all.activeDays).toBe(3);
    const filtered = computeStats(houses, activities, 2026, 7, -1, "Centro");
    expect(filtered.visits).toBe(2);
    expect(filtered.activeDays).toBe(1);
  });
});

describe("matchesWeekday / weekday filter", () => {
  // 10-08-2026 = segunda-feira, 11-08 = terça, 15-08 = sábado, 16-08 = domingo.
  it("matches getDay convention (0=Dom..6=Sáb)", () => {
    expect(matchesWeekday("10-08-2026", 1)).toBe(true);
    expect(matchesWeekday("10-08-2026", 2)).toBe(false);
    expect(matchesWeekday("15-08-2026", 6)).toBe(true);
    expect(matchesWeekday("16-08-2026", 0)).toBe(true);
    expect(matchesWeekday("10/08/2026", 1)).toBe(true);
  });
  it("empty/invalid filter matches everything", () => {
    expect(matchesWeekday("10-08-2026", -1)).toBe(true);
    expect(matchesWeekday("10-08-2026", 7)).toBe(true);
  });
  it("filterByPeriod narrows by weekday", () => {
    const houses = [
      { id: "1", data: "10-08-2026" },
      { id: "2", data: "11-08-2026" },
      { id: "3", data: "15-08-2026" },
    ] as HouseDoc[];
    expect(filterByPeriod(houses, "data", 2026, 7, -1, 1).map((h) => h.id)).toEqual(["1"]);
    expect(filterByPeriod(houses, "data", 2026, 7, -1, -1)).toHaveLength(3);
  });
  it("computeStats filters visits and activeDays by weekday", () => {
    const houses = [
      { id: "1", data: "10-08-2026", situation: "NONE", propertyType: "R" },
      { id: "2", data: "10-08-2026", situation: "NONE", propertyType: "R" },
      { id: "3", data: "11-08-2026", situation: "NONE", propertyType: "R" },
    ] as HouseDoc[];
    const activities = [{ date: "10-08-2026" }, { date: "11-08-2026" }];
    const monday = computeStats(houses, activities, 2026, 7, -1, "", 1);
    expect(monday.visits).toBe(2);
    expect(monday.activeDays).toBe(1);
    const tuesday = computeStats(houses, activities, 2026, 7, -1, "", 2);
    expect(tuesday.visits).toBe(1);
    expect(tuesday.activeDays).toBe(1);
  });
  it("combines bairro + weekday", () => {
    const houses = [
      { id: "1", data: "10-08-2026", bairro: "Centro", situation: "NONE", propertyType: "R" },
      { id: "2", data: "11-08-2026", bairro: "Centro", situation: "NONE", propertyType: "R" },
      { id: "3", data: "10-08-2026", bairro: "Alto", situation: "NONE", propertyType: "R" },
    ] as HouseDoc[];
    const filtered = computeStats(houses, [], 2026, 7, -1, "Centro", 1);
    expect(filtered.visits).toBe(1);
    expect(filtered.activeDays).toBe(1);
  });
});
