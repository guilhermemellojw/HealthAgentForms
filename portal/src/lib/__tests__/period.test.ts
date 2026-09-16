import { describe, it, expect } from "vitest";
import { getWeeksForMonth, isInPeriod, dedupHouses, parseDate, weekIndexForDate } from "../period";
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

describe("dedupHouses", () => {
  const base: HouseDoc = {
    id: "1",
    data: "15-08-2026",
    streetName: "Rua A",
    number: "10",
    blockNumber: "12",
    blockSequence: "1",
    sequence: 1,
    complement: 0,
    visitSegment: 0,
    lastUpdated: { seconds: 100 } as unknown as HouseDoc["lastUpdated"],
  };
  it("deduplicates by natural key, keeps latest", () => {
    const dup: HouseDoc = { ...base, id: "2", lastUpdated: { seconds: 200 } as unknown as HouseDoc["lastUpdated"], situation: "F" };
    const result = dedupHouses([base, dup]);
    expect(result.valid).toHaveLength(1);
    expect(result.duplicates).toBe(1);
    expect(result.valid[0].id).toBe("2");
  });
  it("keeps distinct complements", () => {
    const other: HouseDoc = { ...base, id: "3", complement: 1, lastUpdated: { seconds: 150 } as unknown as HouseDoc["lastUpdated"] };
    const result = dedupHouses([base, other]);
    expect(result.valid).toHaveLength(2);
    expect(result.duplicates).toBe(0);
  });
  it("handles missing blockSequence", () => {
    const h = { ...base, blockSequence: undefined, id: "4" };
    const result = dedupHouses([h]);
    expect(result.valid).toHaveLength(1);
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
