import { jsPDF } from "jspdf";
import type { HouseDoc } from "../types";
import { drawBackPage, drawFrontPage, calculateBlockStats, createChunks } from "./boletim";
import { drawSemanalPage } from "./semanal";
import { setLine } from "./shared";

// ============================================================
// Port 1:1 de BoletimPdfGenerator.generateWeeklyBatchPdf (app Android).
// Produção da Semana: frentes + versos de todos os dias + resumo semanal.
// ============================================================

const PAGE_WIDTH = 842;
const PAGE_HEIGHT = 595;

function dateSortKey(d: string): string {
  return d.replace(/\//g, "-").split("-").reverse().join("");
}

export async function generateWeeklyBatchPdf(
  weeklyData: Record<string, HouseDoc[]>,
  agentName: string,
  activities: Record<string, string>,
  weekDates: string[]
): Promise<jsPDF> {
  const sortedDates = Object.keys(weeklyData).sort((a, b) => dateSortKey(a).localeCompare(dateSortKey(b)));

  const dailyChunks = new Map<string, HouseDoc[][]>();
  for (const date of sortedDates) {
    dailyChunks.set(date, createChunks(weeklyData[date] ?? []));
  }

  const doc = new jsPDF({ orientation: "landscape", unit: "pt", format: [PAGE_WIDTH, PAGE_HEIGHT], compress: true });
  setLine(doc);

  let first = true;
  const addPage = () => {
    if (first) {
      first = false;
    } else {
      doc.addPage([PAGE_WIDTH, PAGE_HEIGHT], "landscape");
    }
  };

  // Pass 1: todas as frentes
  for (const date of sortedDates) {
    const chunks = dailyChunks.get(date) ?? [];
    const totalFolhas = chunks.length;
    for (let index = 0; index < chunks.length; index++) {
      const chunk = chunks[index];
      addPage();
      await drawFrontPage(doc, chunk, date, agentName, index + 1, totalFolhas);
    }
  }

  // Pass 2: todos os versos
  for (const date of sortedDates) {
    const houses = weeklyData[date] ?? [];
    const chunks = dailyChunks.get(date) ?? [];
    for (const chunk of chunks) {
      const stats = calculateBlockStats(houses, chunk);
      addPage();
      drawBackPage(doc, chunk, date, stats.quarteiraoConcluido, stats.localidadeConcluida, stats.workedBlocks, stats.completedBlocks);
    }
  }

  // Pass 3: resumo semanal — filtrar apenas datas da semana selecionada
  const weekSet = new Set(weekDates);
  const allWeekHouses = Object.values(weeklyData)
    .flat()
    .filter((h) => weekSet.has((h.data || "").trim()));
  addPage();
  await drawSemanalPage(doc, weekDates, allWeekHouses, activities, agentName);

  return doc;
}

export async function downloadWeeklyBatch(
  weeklyData: Record<string, HouseDoc[]>,
  agentName: string,
  activities: Record<string, string>,
  weekDates: string[]
): Promise<void> {
  const doc = await generateWeeklyBatchPdf(weeklyData, agentName, activities, weekDates);
  const rangeStart = weekDates[0] ?? "";
  const rangeEnd = weekDates[weekDates.length - 1] ?? "";
  const sanitizedAgent = agentName.trim().replace(/\s+/g, "_").replace(/[^a-zA-Z0-9_-]/g, "_");
  const blob = doc.output("blob");
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `Produção_da_Semana_${rangeStart}_a_${rangeEnd}_${sanitizedAgent}.pdf`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}