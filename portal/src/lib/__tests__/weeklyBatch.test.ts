import { describe, it, expect } from "vitest";

// copy of fixed dateSortKey
function dateSortKey(d: string): string {
  return d.replace(/\//g, "-").split("-").reverse().join("");
}

describe("weeklyBatch dateSortKey", () => {
  it("sorts DD-MM-YYYY correctly", () => {
    const dates = ["15-08-2026", "01-02-2026", "02-02-2026", "31-12-2025"];
    const sorted = [...dates].sort((a, b) => dateSortKey(a).localeCompare(dateSortKey(b)));
    expect(sorted).toEqual(["31-12-2025", "01-02-2026", "02-02-2026", "15-08-2026"]);
  });

  it("sorts DD/MM/YYYY correctly after fix", () => {
    const dates = ["15/08/2026", "01/02/2026", "02/02/2026"];
    const sorted = [...dates].sort((a, b) => dateSortKey(a).localeCompare(dateSortKey(b)));
    expect(sorted).toEqual(["01/02/2026", "02/02/2026", "15/08/2026"]);
  });

  it("handles mixed separators", () => {
    expect(dateSortKey("01-02-2026")).toBe(dateSortKey("01/02/2026"));
  });

  it("old bug would fail: single replace", () => {
    const buggy = (d: string) => d.replace("/", "-").split("-").reverse().join("");
    // buggy for "01/02/2026" => "01-02/2026".split("-") => ["01","02/2026"] => "02/202601"
    expect(buggy("01/02/2026")).not.toBe(dateSortKey("01/02/2026"));
  });
});
