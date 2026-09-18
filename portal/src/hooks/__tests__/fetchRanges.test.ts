import { describe, it, expect } from "vitest";
import { fetchRangesForPeriod } from "../usePortalData";
import { getWeeksForMonth } from "../../lib/period";

const fmt = (d: Date) =>
  `${String(d.getDate()).padStart(2, "0")}-${String(d.getMonth() + 1).padStart(2, "0")}-${d.getFullYear()}`;

function weekDays(year: number, month: number, week: number): string[] {
  const weeks = getWeeksForMonth(year, month);
  const w = weeks[week];
  const out: string[] = [];
  const d = new Date(w.start);
  while (d <= w.end) {
    out.push(fmt(d));
    d.setDate(d.getDate() + 1);
  }
  return out;
}

// Simula a comparação lexicográfica do Firestore para strings DD-MM-YYYY.
function coveredByRanges(day: string, ranges: { start: string; end: string }[]): boolean {
  return ranges.some((r) => day >= r.start && day <= r.end);
}

describe("fetchRangesForPeriod", () => {
  it("month view returns the full month", () => {
    expect(fetchRangesForPeriod(2026, 7, -1)).toEqual([
      { start: "01-08-2026", end: "31-08-2026" },
    ]);
  });

  it("year view returns the full year", () => {
    expect(fetchRangesForPeriod(2026, -1, -1)).toEqual([
      { start: "01-01-2026", end: "31-12-2026" },
    ]);
  });

  it("week inside a single month returns one range", () => {
    // Ago/2026 semana 1 = 02-08..08-08 (toda dentro de agosto)
    expect(fetchRangesForPeriod(2026, 7, 1)).toEqual([
      { start: "01-08-2026", end: "31-08-2026" },
    ]);
  });

  it("week crossing months returns both full months", () => {
    // Ago/2026 semana 0 = 26-07..01-08
    expect(fetchRangesForPeriod(2026, 7, 0)).toEqual([
      { start: "01-07-2026", end: "31-07-2026" },
      { start: "01-08-2026", end: "31-08-2026" },
    ]);
  });

  it("week crossing years returns Dec + Jan ranges", () => {
    // Dez/2025 última semana = 28-12-2025..03-01-2026
    const weeks = getWeeksForMonth(2025, 11);
    const last = weeks.length - 1;
    expect(fetchRangesForPeriod(2025, 11, last)).toEqual([
      { start: "01-12-2025", end: "31-12-2025" },
      { start: "01-01-2026", end: "31-01-2026" },
    ]);
  });

  it("every day of any week is covered by the fetched ranges (lexicographic)", () => {
    // Inclui semanas que invadem o mês vizinho e a virada de ano.
    const cases: [number, number][] = [
      [2026, 7],
      [2026, 0],
      [2025, 11],
      [2026, 1],
    ];
    for (const [year, month] of cases) {
      const weeks = getWeeksForMonth(year, month);
      weeks.forEach((_, i) => {
        const ranges = fetchRangesForPeriod(year, month, i);
        for (const day of weekDays(year, month, i)) {
          expect(coveredByRanges(day, ranges)).toBe(true);
        }
      });
    }
  });
});
