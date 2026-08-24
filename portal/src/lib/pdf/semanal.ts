import { jsPDF } from "jspdf";
import type { HouseDoc } from "../types";
import { getLogoBase64 } from "./logoLoader";
import {
  LIGHT_BG,
  calculateCicloSemanal,
  drawCell,
  drawRectBox,
  drawRow,
  drawTextInRect,
  drawVerticalHeader,
  dsh,
  formatDouble,
  setLine,
  setText,
  textWidth,
  pdfText,
  setTextLeftPad,
  setTextMode,
} from "./shared";

// ============================================================
// Port 1:1 de SemanalPdfGenerator.kt (app Android).
// Resumo Semanal dos Agentes (1 página, A4 paisagem).
// ============================================================

const PAGE_WIDTH = 842;
const PAGE_HEIGHT = 595;
const MARGIN_LEFT = 40;
const MARGIN_RIGHT = 90;
const MARGIN_Y = 80;

const LOGO_W_H_RATIO = 735 / 197;

const dash = "—";

function drawMetaField(
  doc: jsPDF,
  label: string,
  value: string,
  x: number,
  y: number,
  w: number,
  h: number,
  labelSize: number,
  valueSize: number,
  centerValue = false
): void {
  const labelW = doc.getTextWidth(label) + 5;
  setText(doc, labelSize, false);
  pdfText(doc,label, x, y + h - 5);
  setLine(doc);
  doc.line(x + labelW, y + h - 5, x + w, y + h - 5);
  setText(doc, valueSize, true);
  const ty = y + h - 7;
  if (centerValue) {
    const valW = doc.getTextWidth(value);
    pdfText(doc,value, x + labelW + (w - labelW - valW) / 2, ty);
  } else {
    pdfText(doc,value, x + labelW + 5, ty);
  }
}

function drawMetaFieldSegmented(
  doc: jsPDF,
  label: string,
  values: string[],
  widths: number[],
  x: number,
  y: number,
  h: number,
  labelSize: number,
  valueSize: number,
  isSemana = false
): void {
  setText(doc, labelSize, false);
  pdfText(doc,label, x, y + h - 5);
  const startX = x + doc.getTextWidth(label) + 5;
  let curX = startX;
  setLine(doc);
  for (let i = 0; i < values.length; i++) {
    const w = widths[i];
    const value = values[i];
    doc.line(curX, y + h - 5, curX + w, y + h - 5);
    if (value !== "") {
      const valW = doc.getTextWidth(value);
      setText(doc, valueSize, true);
      pdfText(doc,value, curX + (w - valW) / 2, y + h - 7);
    }
    curX += w;

    if (isSemana) {
      if (i === 0 || i === 2) {
        curX += 4;
        setText(doc, labelSize, false);
        pdfText(doc,"/", curX, y + h - 5);
        curX += 12;
      } else if (i === 1) {
        curX += 12;
        setText(doc, labelSize, false);
        pdfText(doc,"a", curX, y + h - 5);
        curX += 18;
      }
    } else {
      if (i < values.length - 1) {
        curX += 8;
        setText(doc, labelSize, false);
        pdfText(doc,"/", curX, y + h - 5);
        curX += 12;
      }
    }
  }
}

export async function drawSemanalPage(
  doc: jsPDF,
  weekDates: string[],
  allHouses: HouseDoc[],
  activities: Record<string, string>,
  agentName: string
): Promise<void> {
  const textSize = 8;
  const boldSize = 8;
  const smallBoldSize = 7.5;

  let cursorY = MARGIN_Y;

  const tableWidth = PAGE_WIDTH - MARGIN_LEFT - MARGIN_RIGHT;
  const rightEdge = MARGIN_LEFT + tableWidth;

  const housesByDate = new Map<string, HouseDoc[]>();
  for (const h of allHouses) {
    const d = h.data ?? "";
    if (!housesByDate.has(d)) housesByDate.set(d, []);
    housesByDate.get(d)!.push(h);
  }
  const activeWeekDates = weekDates.filter((d) => {
    const hasHouses = (housesByDate.get(d)?.length ?? 0) > 0;
    const st = (activities[d] ?? "").trim();
    const upper = st.toUpperCase();
    const isNormal = upper === "NORMAL" || upper.startsWith("NORMAL ");
    const hasStatus = st !== "" && !isNormal;
    return hasHouses || hasStatus;
  });

  // Header
  const logoH = 38;
  const logoY = cursorY + 24;
  const logoW = LOGO_W_H_RATIO * logoH;
  const logoBase64 = await getLogoBase64();
  doc.addImage(`data:image/png;base64,${logoBase64}`, "PNG", MARGIN_LEFT, logoY, logoW, logoH);

  const prefText = "PREFEITURA MUNICIPAL DE BOM JARDIM";
  const secText = "SECRETARIA MUNICIPAL DE SAÚDE";
  // Header esquerdo: alinhado à esquerda (SemanalPdfGenerator.kt:155-156)
  setText(doc, smallBoldSize, true);
  pdfText(doc,prefText, MARGIN_LEFT, cursorY + 10);
  pdfText(doc,secText, MARGIN_LEFT, cursorY + 19);

  const titleSize = 20;
  const subTitleSize = 26;
  const headerText1 = "Programa Municipal de Controle da Dengue";
  const headerText2 = "PMCD";
  setText(doc, titleSize, true);
  const title1W = textWidth(headerText1, titleSize, true);
  pdfText(doc,headerText1, rightEdge - title1W, cursorY + 25);
  setText(doc, subTitleSize, true);
  const title2W = textWidth(headerText2, subTitleSize, true);
  pdfText(doc,headerText2, rightEdge - title1W + (title1W - title2W) / 2, cursorY + 55);

  cursorY += logoH + 30;

  // Metadata
  const uniqueWeekBairros = allHouses
    .filter((h) => activeWeekDates.includes(h.data ?? ""))
    .map((h) => (h.bairro || "").trim().toUpperCase())
    .filter((b) => b !== "")
    .filter((b, i, arr) => arr.findIndex((x) => x.toLowerCase() === b.toLowerCase()) === i);
  const bairro = uniqueWeekBairros.join(" / ");
  const firstHouse = allHouses.find((h) => activeWeekDates.includes(h.data ?? ""));
  const categoria = firstHouse?.categoria || "BRR";
  const firstDateOfWeek = activeWeekDates[0] || "";
  const ciclo = firstDateOfWeek !== "" ? calculateCicloSemanal(firstDateOfWeek) : "";
  const ano = activeWeekDates.length > 0 ? activeWeekDates[0].slice(-4) : "";

  const grayBarH = 15;
  drawRectBox(doc, MARGIN_LEFT, cursorY, tableWidth, grayBarH, "RESUMO SEMANAL DOS AGENTES", boldSize, true, LIGHT_BG);
  cursorY += grayBarH;

  const metaRowH = 20;
  let cx = MARGIN_LEFT;
  const col2X = MARGIN_LEFT + 480;

  const labelCB = "Código/Bairro";
  const wLabelCB = doc.getTextWidth(labelCB);
  const wCat = 60;
  const wBName = 290;
  drawMetaFieldSegmented(doc, labelCB, [categoria, bairro], [wCat, wBName], cx, cursorY, metaRowH, textSize, 10);

  const endLeft = MARGIN_LEFT + wLabelCB + 5 + wCat + 20 + wBName;

  cx = col2X;
  const labelAC = "Ano/Ciclo";
  const wLabelAC = doc.getTextWidth(labelAC);
  const availAC = rightEdge - (col2X + wLabelAC + 25);
  const wAno = availAC * 0.55;
  const wCicloVal = availAC * 0.45;
  drawMetaFieldSegmented(doc, labelAC, [ano, ciclo], [wAno, wCicloVal], cx, cursorY, metaRowH, textSize, 10);

  cursorY += metaRowH + 8;
  cx = MARGIN_LEFT;

  const labelTurma = "Turma:";
  const fullTurmaW = endLeft - MARGIN_LEFT;
  const wTurma = fullTurmaW / 2;
  drawMetaField(doc, labelTurma, "PMBJ", cx, cursorY, wTurma, metaRowH, textSize, 10, true);

  cx = col2X;
  const labelSD = "Semana de";
  const wLabelSD = doc.getTextWidth(labelSD);
  const availSD = rightEdge - (col2X + wLabelSD + 5 + 54);
  const wSeg = availSD / 4;

  let weekStartDay = "";
  let weekStartMonth = "";
  let weekEndDay = "";
  let weekEndMonth = "";

  const firstDateStr = activeWeekDates[0]?.replace(/\//g, "-") ?? "";
  if (firstDateStr) {
    const parts = firstDateStr.split("-");
    if (parts.length === 3) {
      const d = new Date(Date.UTC(Number(parts[2]), Number(parts[1]) - 1, Number(parts[0]), 12, 0, 0));
      const daysFromSunday = d.getUTCDay();
      d.setUTCDate(d.getUTCDate() - daysFromSunday);
      weekStartDay = String(d.getUTCDate()).padStart(2, "0");
      weekStartMonth = String(d.getUTCMonth() + 1).padStart(2, "0");
      d.setUTCDate(d.getUTCDate() + 6);
      weekEndDay = String(d.getUTCDate()).padStart(2, "0");
      weekEndMonth = String(d.getUTCMonth() + 1).padStart(2, "0");
    }
  }
  if (weekStartDay === "") {
    const normalizedFirst = firstDateStr.replace(/\//g, "-");
    const normalizedLast = (activeWeekDates[activeWeekDates.length - 1] ?? "").replace(/\//g, "-");
    weekStartDay = normalizedFirst.slice(0, 2) || "";
    weekStartMonth = normalizedFirst.slice(3, 5) || "";
    weekEndDay = normalizedLast.slice(0, 2) || "";
    weekEndMonth = normalizedLast.slice(3, 5) || "";
  }

  drawMetaFieldSegmented(
    doc,
    labelSD,
    [weekStartDay, weekStartMonth, weekEndDay, weekEndMonth],
    [wSeg, wSeg, wSeg, wSeg],
    cx,
    cursorY,
    metaRowH,
    textSize,
    10,
    true
  );

  cursorY += metaRowH + 15;

  // Table headers
  const th1 = 20;
  const th2 = 35;
  const totalHeaderH = th1 + th2;

  const colData = 60;
  const colRes = 24, colCom = 24, colTB = 24, colOut = 24, colPE = 24, colTotalVisits = 30;
  const groupVisits = colRes + colCom + colTB + colOut + colPE + colTotalVisits;
  const colFEC = 24, colREC = 24, colRecup = 24;
  const groupPend = colFEC + colREC + colRecup;
  const colAmostras = 35;
  const colDep = 23;
  const groupDeps = colDep * 7;
  const colTotalDeps = 52;
  const colElim = 65;
  const colLarv = 57;
  const colQuart = 60;

  let tx = MARGIN_LEFT;

  drawRectBox(doc, tx, cursorY, colData, totalHeaderH, "Data", boldSize, true, null);
  tx += colData;

  drawRectBox(doc, tx, cursorY, groupVisits, th1, "Imóveis Trabalhados", boldSize, true, null);
  let stx = tx;
  for (const [lab, w] of [["Res", colRes], ["Com", colCom], ["TB", colTB], ["Out", colOut], ["PE", colPE]] as Array<[string, number]>) {
    drawVerticalHeader(doc, lab, stx, cursorY + th1, w, th2, textSize, false, null);
    stx += w;
  }
  drawVerticalHeader(doc, "Total", stx, cursorY + th1, colTotalVisits, th2, boldSize, true, null);
  tx += groupVisits;

  drawRectBox(doc, tx, cursorY, groupPend, th1, "Pendências", boldSize, true, null);
  stx = tx;
  for (const [lab, w] of [["FEC", colFEC], ["REC", colREC], ["Recup", colRecup]] as Array<[string, number]>) {
    drawVerticalHeader(doc, lab, stx, cursorY + th1, w, th2, textSize, false, null);
    stx += w;
  }
  tx += groupPend;

  drawVerticalHeader(doc, "Amostras\nColetadas", tx, cursorY, colAmostras, totalHeaderH, textSize, false, null);
  tx += colAmostras;

  drawRectBox(doc, tx, cursorY, groupDeps, th1, "Quantidades de Depósitos Tratados", boldSize, true, null);
  stx = tx;
  for (const lab of ["A1", "A2", "B", "C", "D1", "D2", "E"]) {
    drawRectBox(doc, stx, cursorY + th1, colDep, th2, lab, textSize, false, null);
    stx += colDep;
  }
  tx += groupDeps;

  drawVerticalHeader(doc, "Total de\nDepósitos\nTratados", tx, cursorY, colTotalDeps, totalHeaderH, textSize, false, null);
  tx += colTotalDeps;

  drawVerticalHeader(doc, "Depósitos\nEliminados\nCaixas\nd'água Difícil\nacesso", tx, cursorY, colElim, totalHeaderH, textSize, false, null);
  tx += colElim;

  drawVerticalHeader(doc, "Larvicida\nBPU(Gr)", tx, cursorY, colLarv, totalHeaderH, textSize, false, null);
  tx += colLarv;

  drawVerticalHeader(doc, "Quarteirões\nConcluídos", tx, cursorY, colQuart, totalHeaderH, textSize, false, null);

  cursorY += totalHeaderH;

  // Data rows
  const rowH = 15;

  const allHousesSorted = allHouses.filter((h) => activeWeekDates.includes(h.data ?? "")).sort((a, b) => (a.listOrder ?? 0) - (b.listOrder ?? 0));
  const blockToLastIndex = new Map<string, number>();
  allHousesSorted.forEach((h, index) => {
    const key = `${h.blockNumber}|${h.blockSequence}|${(h.bairro || "").trim().toUpperCase()}`;
    blockToLastIndex.set(key, index);
  });

  const allBlockCompletions: Array<[string, string, string]> = [];

  for (const date of activeWeekDates) {
    const dayHouses = housesByDate.get(date);
    if (!dayHouses) continue;
    const dayHouseIds = new Set(dayHouses.map((h) => h.id));
    const dayBlocks: Array<[string, string, string]> = [];
    for (const h of dayHouses) {
      const bairroK = (h.bairro || "").trim().toUpperCase();
      if (!dayBlocks.some((b) => b[0] === h.blockNumber && b[1] === h.blockSequence && b[2] === bairroK)) {
        dayBlocks.push([h.blockNumber ?? "", h.blockSequence ?? "", bairroK]);
      }
    }

    for (const [bNum, bSeq, bairroK] of dayBlocks) {
      const blockHousesInDay = dayHouses.filter(
        (h) => h.blockNumber === bNum && h.blockSequence === bSeq && (h.bairro || "").trim().toUpperCase() === bairroK,
      );
      const hasManual = blockHousesInDay.some((h) => h.quarteiraoConcluido);
      const hasBairroManual = blockHousesInDay.some((h) => h.localidadeConcluida);

      const key = `${bNum}|${bSeq}|${bairroK}`;
      const lastIndexInFull = blockToLastIndex.get(key) ?? -1;
      const lastHouseInFull = lastIndexInFull !== -1 ? allHousesSorted[lastIndexInFull] : null;
      const isLastHouseInDay = lastHouseInFull !== null && dayHouseIds.has(lastHouseInFull.id);
      const hasSuccessor = lastIndexInFull !== -1 && lastIndexInFull < allHousesSorted.length - 1;
      const autoConcluido = isLastHouseInDay && hasSuccessor;

      if (hasManual || hasBairroManual || autoConcluido) {
        const displayName = bSeq !== "" ? `${bNum}/${bSeq}` : bNum;
        allBlockCompletions.push([displayName, bairroK, date]);
      }
    }
  }

  let totRes = 0, totCom = 0, totTB = 0, totOut = 0, totPE = 0, totVisits = 0;
  let totFEC = 0, totREC = 0, totRecup = 0;
  let totAmostras = 0;
  const totDeps = [0, 0, 0, 0, 0, 0, 0];
  let totTotalDeps = 0;
  let totElim = 0;
  let totLarv = 0;
  const totCompletedBlocks = new Set<string>();

  for (const date of activeWeekDates) {
    const dayHouses = housesByDate.get(date) ?? [];
    const status = activities[date] ?? "";

    tx = MARGIN_LEFT;

    const displayDate = date.replace(/-/g, "/").substring(0, 5);
    drawCell(doc, displayDate, tx, cursorY, colData, rowH, textSize, false);
    tx += colData;

    if (status !== "" && status.toLowerCase() !== "normal") {
      const annotationWidth = tableWidth - colData - colQuart;
      setText(doc, 9, true);
      drawCell(doc, status, tx, cursorY, annotationWidth, rowH, 9, true);
      tx += annotationWidth;
      drawCell(doc, "", tx, cursorY, colQuart, rowH, textSize, false);
    } else {
      if (dayHouses.length === 0) {
        const annotationWidth = tableWidth - colData - colQuart;
        drawCell(doc, dash, tx, cursorY, annotationWidth, rowH, textSize, false);
        tx += annotationWidth;
        drawCell(doc, dash, tx, cursorY, colQuart, rowH, textSize, false);
      } else {
        const res = dayHouses.filter((h) => h.propertyType === "R" && (h.situation === "NONE" || h.situation === "EMPTY")).length;
        const com = dayHouses.filter((h) => h.propertyType === "C" && (h.situation === "NONE" || h.situation === "EMPTY")).length;
        const tb = dayHouses.filter((h) => h.propertyType === "TB" && (h.situation === "NONE" || h.situation === "EMPTY")).length;
        const out = dayHouses.filter((h) => h.propertyType === "O" && (h.situation === "NONE" || h.situation === "EMPTY")).length;
        const pe = dayHouses.filter((h) => h.propertyType === "PE" && (h.situation === "NONE" || h.situation === "EMPTY")).length;
        const dayTotalVisits = res + com + tb + out + pe;

        const fec = dayHouses.filter((h) => h.situation === "F" || h.situation === "A" || h.situation === "V").length;
        const rec = dayHouses.filter((h) => h.situation === "REC").length;
        const recup = 0;
        const samples = 0;

        const workedDayHouses = dayHouses.filter((h) => h.situation === "NONE" || h.situation === "EMPTY");
        const sum = (k: "a1" | "a2" | "b" | "c" | "d1" | "d2" | "e") => workedDayHouses.reduce((acc, h) => acc + (h[k] ?? 0), 0);
        const dists = [sum("a1"), sum("a2"), sum("b"), sum("c"), sum("d1"), sum("d2"), sum("e")];
        const dayTotalDeps = dists.reduce((a, b) => a + b, 0);
        const elim = workedDayHouses.reduce((acc, h) => acc + (h.eliminados ?? 0), 0);
        const larv = workedDayHouses.reduce((acc, h) => acc + (h.larvicida ?? 0), 0);

        const rowWidths = [
          colRes, colCom, colTB, colOut, colPE, colTotalVisits,
          colFEC, colREC, colRecup, colAmostras,
          colDep, colDep, colDep, colDep, colDep, colDep, colDep,
          colTotalDeps, colElim, colLarv,
        ];
        const rowVals = [
          dsh(res), dsh(com), dsh(tb), dsh(out), dsh(pe), dsh(dayTotalVisits),
          dsh(fec), dsh(rec), dsh(recup), dsh(samples),
          dsh(dists[0]), dsh(dists[1]), dsh(dists[2]), dsh(dists[3]), dsh(dists[4]), dsh(dists[5]), dsh(dists[6]),
          dsh(dayTotalDeps), dsh(elim), formatDouble(larv),
        ];
        const rowBolds = [
          false, false, false, false, false, true,
          false, false, false, false,
          false, false, false, false, false, false, false,
          true, false, false,
        ];

        drawRow(doc, tx, cursorY, rowH, rowWidths, rowVals, {
          alignLefts: Array(rowWidths.length).fill(false),
          bolds: rowBolds,
          size: textSize,
        });
        tx += rowWidths.reduce((a, b) => a + b, 0);

        const dayCompletions = allBlockCompletions.filter((c) => c[2] === date);
        const dayCompletedBlocks = dayCompletions.map((c) => c[0]).filter((v, i, arr) => arr.indexOf(v) === i).sort();
        const blocksStr = dayCompletedBlocks.length === 0 ? dash : dayCompletedBlocks.join("   ");
        for (const c of dayCompletions) {
          totCompletedBlocks.add(`${c[1]}|${c[0]}`);
        }

        if (blocksStr !== dash) {
          const availableW = colQuart - 4;
          let currentSize = 8;
          while (doc.getTextWidth(blocksStr) > availableW && currentSize > 4) {
            currentSize -= 0.5;
            setText(doc, currentSize, false);
          }
          setText(doc, currentSize, false);
          drawCell(doc, blocksStr, tx, cursorY, colQuart, rowH, currentSize, false);
          setText(doc, textSize, false);
        } else {
          drawCell(doc, blocksStr, tx, cursorY, colQuart, rowH, textSize, false);
        }
        tx += colQuart;

        const concludedBairrosToday = dayCompletions.map((c) => c[1]).filter((v, i, arr) => arr.indexOf(v) === i).sort();
        if (concludedBairrosToday.length > 0) {
          const labelX = MARGIN_LEFT + tableWidth + 5;
          const labelSize = 7;
          const baseY = cursorY + (rowH / 2) + labelSize / 2 - 1;
          const maxLabelW = 80;
          const fitLabel = (txt: string) => {
            let t = txt;
            while (doc.getTextWidth(t) > maxLabelW && t.length > 4) t = t.slice(0, -4) + "...";
            return t;
          };
          if (concludedBairrosToday.length === 1) {
            setText(doc, labelSize, false);
            pdfText(doc,fitLabel(concludedBairrosToday[0]), labelX, baseY);
          } else if (concludedBairrosToday.length > 2) {
            setText(doc, labelSize, false);
            pdfText(doc,fitLabel(concludedBairrosToday[0]), labelX, cursorY + 6.5);
            pdfText(doc,fitLabel(concludedBairrosToday[1]) + "...", labelX, cursorY + 13.5);
          } else {
            setText(doc, labelSize, false);
            concludedBairrosToday.forEach((name, index) => {
              pdfText(doc,fitLabel(name), labelX, index === 0 ? cursorY + 6.5 : cursorY + 13.5);
            });
          }
          setText(doc, textSize, false);
        }

        totRes += res; totCom += com; totTB += tb; totOut += out; totPE += pe; totVisits += dayTotalVisits;
        totFEC += fec; totREC += rec; totRecup += recup;
        totAmostras += samples;
        for (let i = 0; i < 7; i++) totDeps[i] += dists[i];
        totTotalDeps += dayTotalDeps;
        totElim += elim;
        totLarv += larv;
      }
    }
    cursorY += rowH;
  }

  // Totals row
  tx = MARGIN_LEFT;
  drawRectBox(doc, tx, cursorY, colData, rowH, "TOTAIS", boldSize, true, LIGHT_BG);

  const weeklyTotalWidths = [
    colRes, colCom, colTB, colOut, colPE, colTotalVisits,
    colFEC, colREC, colRecup, colAmostras,
    colDep, colDep, colDep, colDep, colDep, colDep, colDep,
    colTotalDeps, colElim, colLarv, colQuart,
  ];
  const weeklyTotalVals = [
    dsh(totRes), dsh(totCom), dsh(totTB), dsh(totOut), dsh(totPE), dsh(totVisits),
    dsh(totFEC), dsh(totREC), dsh(totRecup), dsh(totAmostras),
    dsh(totDeps[0]), dsh(totDeps[1]), dsh(totDeps[2]), dsh(totDeps[3]), dsh(totDeps[4]), dsh(totDeps[5]), dsh(totDeps[6]),
    dsh(totTotalDeps), dsh(totElim), formatDouble(totLarv),
    totCompletedBlocks.size === 0 ? dash : [...totCompletedBlocks].map((k) => k.split("|")[1]).sort().join("   "),
  ];
  drawRow(doc, MARGIN_LEFT + colData, cursorY, rowH, weeklyTotalWidths, weeklyTotalVals, {
    alignLefts: Array(weeklyTotalWidths.length).fill(false),
    bolds: Array(weeklyTotalWidths.length).fill(true),
    size: boldSize,
    bg: LIGHT_BG,
  });

  cursorY += rowH + 5;
  drawTextInRect(doc, "Resumo Semanal dos Agentes / SMS / Município de Bom Jardim / PMCD", MARGIN_LEFT, cursorY, PAGE_WIDTH, 10, textSize, false, true);

  cursorY += 50;

  const footerDate = activeWeekDates.length > 0 ? activeWeekDates[activeWeekDates.length - 1].replace(/-/g, "/") : "";
  const cityLabel = "Bom Jardim, ";
  const cityLabelW = doc.getTextWidth(cityLabel);
  setText(doc, textSize, false);
  pdfText(doc,cityLabel, MARGIN_LEFT + 10, cursorY);

  const dateStartX = MARGIN_LEFT + 10 + cityLabelW;
  const dateEndX = dateStartX + 120;
  const valSize = 11;
  setText(doc, valSize, true);
  pdfText(doc,footerDate, dateStartX + (120 - doc.getTextWidth(footerDate)) / 2, cursorY - 2);
  setLine(doc);
  doc.line(dateStartX, cursorY + 2, dateEndX, cursorY + 2);

  const agentLabel = "Agente: ";
  const agentLabelW = doc.getTextWidth(agentLabel);
  const agentLineW = 160;
  const agentStartX = MARGIN_LEFT + tableWidth - agentLineW;
  setText(doc, textSize, false);
  pdfText(doc,agentLabel, agentStartX - agentLabelW - 5, cursorY);
  doc.line(agentStartX, cursorY + 2, agentStartX + agentLineW, cursorY + 2);

  if (agentName.trim() !== "") {
    setText(doc, 10, true);
    pdfText(doc,agentName, agentStartX + 5, cursorY - 2);
  }
}

export async function generateSemanalPdf(weekDates: string[], allHouses: HouseDoc[], activities: Record<string, string>, agentName: string): Promise<jsPDF> {
  setTextLeftPad(0); // SemanalPdfGenerator (Metrics) não usa pad esquerdo
  setTextMode("metrics");
  const doc = new jsPDF({ orientation: "landscape", unit: "pt", format: [PAGE_WIDTH, PAGE_HEIGHT], compress: true });
  setLine(doc);
  await drawSemanalPage(doc, weekDates, allHouses, activities, agentName);
  return doc;
}

export async function downloadSemanal(weekDates: string[], allHouses: HouseDoc[], activities: Record<string, string>, agentName: string): Promise<void> {
  const doc = await generateSemanalPdf(weekDates, allHouses, activities, agentName);
  const safe = agentName.trim().replace(/\s+/g, "_").replace(/[^a-zA-Z0-9_-]/g, "_");
  const blob = doc.output("blob");
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `Semanal_${weekDates[0]?.replace(/-/g, "_")}_${safe || "agente"}.pdf`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}