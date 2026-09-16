import { jsPDF } from "jspdf";
import type { HouseDoc } from "../types";
import { getLogoBase64 } from "./logoLoader";
import {
  HEADER_BG,
  calculateCicloFromDate,
  dsh,
  drawBlockCell,
  drawCell,
  drawCenteredText,
  drawHeaderBox,
  drawRectBox,
  drawRow,
  drawTextInBox,
  drawTextInRect,
  drawVerticalHeader,
  fillRect,
  fitToWidth,
  formatDouble,
  formatStreetName,
  isOpen,
  propertyTypeCode,
  setLine,
  setText,
  situationCode,
  strokeRect,
  textWidth,
  tightBounds,
  pdfText,
} from "./shared";

// ============================================================
// Port 1:1 de BoletimPdfGenerator.kt / PdfComponents.kt /
// PdfTableBuilder.kt / PdfHeaderFooter.kt / BoletimDataMapper.kt
// (app Android). Layout, cores, fontes e métricas idênticos.
// ============================================================

const PAGE_W = 842;
const PAGE_H = 595;
const MARGIN = 20;

export interface BlockStats {
  workedBlocks: Array<[string, string]>;
  completedBlocks: Array<[string, string]>;
  quarteiraoConcluido: boolean;
  localidadeConcluida: boolean;
}

// ------------------------------------------------------------
// Dados (BoletimDataMapper.kt + models)
// ------------------------------------------------------------

export function createChunks(houses: HouseDoc[]): HouseDoc[][] {
  if (houses.length === 0) return [];
  const sorted = [...houses].sort((a, b) => (a.listOrder ?? 0) - (b.listOrder ?? 0));
  const chunks: HouseDoc[][] = [];
  let currentBairro = (sorted[0].bairro || "").trim().toUpperCase();
  let currentGroup: HouseDoc[] = [];
  for (const house of sorted) {
    const houseBairro = (house.bairro || "").trim().toUpperCase();
    if (houseBairro !== currentBairro || currentGroup.length >= 20) {
      if (currentGroup.length) chunks.push(currentGroup);
      currentGroup = [];
      currentBairro = houseBairro;
    }
    currentGroup.push(house);
  }
  if (currentGroup.length) chunks.push(currentGroup);
  return chunks;
}

export function calculateBlockStats(allHouses: HouseDoc[], chunk: HouseDoc[]): BlockStats {
  if (chunk.length === 0) return { workedBlocks: [], completedBlocks: [], quarteiraoConcluido: false, localidadeConcluida: false };
  const blockToLastIndex = new Map<string, number>();
  allHouses.forEach((h, i) => {
    blockToLastIndex.set(`${h.blockNumber}|${h.blockSequence}|${(h.bairro || "").trim().toUpperCase()}`, i);
  });
  const chunkIds = new Set(chunk.map((h) => h.id));
  const workedSet = new Map<string, [string, string]>();
  for (const h of chunk) {
    const bKey = `${(h.blockNumber ?? "").trim()}|${(h.blockSequence ?? "").trim()}|${(h.bairro || "").trim().toUpperCase()}`;
    workedSet.set(bKey, [h.blockNumber ?? "", h.blockSequence ?? ""]);
  }
  const workedBlocks = [...workedSet.values()];
  const completedBlocks: Array<[string, string]> = [];
  for (const [bNum, bSeq] of workedBlocks) {
    const hasManual = chunk.some((h) => h.blockNumber === bNum && h.blockSequence === bSeq && h.quarteiraoConcluido);
    const bairroForBlock = chunk.find((h) => h.blockNumber === bNum && h.blockSequence === bSeq)?.bairro?.trim().toUpperCase() ?? (chunk[0].bairro || "").trim().toUpperCase();
    const lastIndexInFull = blockToLastIndex.get(`${bNum}|${bSeq}|${bairroForBlock}`) ?? -1;
    const lastHouseInFull = lastIndexInFull !== -1 ? allHouses[lastIndexInFull] : null;
    const isLastHouseInChunk = lastHouseInFull !== null && chunkIds.has(lastHouseInFull.id);
    const nextHouse = lastIndexInFull !== -1 && lastIndexInFull + 1 < allHouses.length ? allHouses[lastIndexInFull + 1] : null;
    const nextIsDifferentBlock =
      nextHouse !== null &&
      ((nextHouse.blockNumber ?? "").trim() !== bNum.trim() ||
        (nextHouse.blockSequence ?? "").trim() !== (bSeq ?? "").trim() ||
        (nextHouse.bairro || "").trim().toUpperCase() !== bairroForBlock);
    const hasSuccessor = nextHouse !== null && nextIsDifferentBlock;
    const autoConcluido = isLastHouseInChunk && hasSuccessor;
    if (hasManual || autoConcluido) completedBlocks.push([bNum, bSeq]);
  }
  return {
    workedBlocks,
    completedBlocks,
    quarteiraoConcluido: completedBlocks.length > 0,
    localidadeConcluida: chunk.some((h) => h.localidadeConcluida),
  };
}

// ------------------------------------------------------------
// Frente (drawFrontPage)
// ------------------------------------------------------------

const LOGO_W_H_RATIO = 735 / 197;

export async function drawFrontPage(doc: jsPDF, houses: HouseDoc[], date: string, agentName: string, folhaNumber: number, totalFolhas: number): Promise<void> {
  let cursorY = MARGIN;
  const last = houses[houses.length - 1];

  // 1. Logo
  const logoH = 40;
  const logoW = LOGO_W_H_RATIO * logoH;
  const logoBase64 = await getLogoBase64();
  doc.addImage(`data:image/png;base64,${logoBase64}`, "PNG", MARGIN, cursorY, logoW, logoH);

  // 2. Títulos (baselines exatas do Android)
  const centerX = PAGE_W / 2;
  const titleSize = 12;
  const t1 = "SECRETARIA DE ESTADO DE SAÚDE E DEFESA CIVIL";
  const t2 = "REGISTRO DIÁRIO DO SERVIÇO ANTIVETORIAL";
  setText(doc, titleSize, true);
  pdfText(doc,t1, centerX - tightBounds(t1, titleSize, true).w / 2, cursorY + 15);
  pdfText(doc,t2, centerX - tightBounds(t2, titleSize, true).w / 2, cursorY + 32);

  // 3. Contador de folha (sem borda)
  const folhaW = 100;
  drawCenteredText(doc, `Folha:   ${folhaNumber}   /   ${totalFolhas}`, PAGE_W - MARGIN - folhaW, cursorY, folhaW, 20, 12, false);

  cursorY += 40 + 5;

  // Metadata
  const rowH = 35;
  const gap = 40;
  const wMunic = 160;
  const wBairro = 260;
  const wCat = 90;
  const wZona = 60;
  const wTipo = PAGE_W - 2 * MARGIN - wMunic - wBairro - wCat - wZona - gap * 4;
  const labelSize = 8;
  const dataSize = 10;

  const municipio = last?.municipio || "Bom Jardim";
  const bairro = (last?.bairro || "").trim().toUpperCase();
  const categoria = last?.categoria || "BRR";
  const zona = last?.zona || "URB";
  const tipo = last?.tipo != null ? String(last.tipo) : "2";

  let cx = MARGIN;
  const dBox = (w: number, label: string, val: string) => {
    drawHeaderBox(doc, cx, cursorY, w, rowH, label, val, labelSize, dataSize);
    cx += w + gap;
  };
  dBox(wMunic, "Município", municipio);
  dBox(wBairro, "Código e Nome do Bairro", bairro);
  dBox(wCat, "Categoria / Bairro", categoria);
  dBox(wZona, "Zona", zona);

  // Box Tipo custom
  const tipoX = cx;
  const labelH = 12;
  const bottomH = rowH - labelH;
  fillRect(doc, tipoX, cursorY, wTipo, labelH, HEADER_BG);
  strokeRect(doc, tipoX, cursorY, wTipo, rowH);
  setLine(doc);
  doc.line(tipoX, cursorY + labelH, tipoX + wTipo, cursorY + labelH);
  drawCenteredText(doc, "Tipo", tipoX, cursorY, wTipo, labelH, labelSize, false);
  drawCenteredText(doc, tipo, tipoX, cursorY + labelH, wTipo * 0.25, bottomH, dataSize, true);
  doc.line(tipoX + wTipo * 0.25, cursorY + labelH, tipoX + wTipo * 0.25, cursorY + rowH);

  const legPaintSize = 7;
  const legX = tipoX + wTipo * 0.25 + 2;
  const midY = cursorY + labelH + bottomH / 2;
  doc.line(tipoX + wTipo * 0.25, midY, tipoX + wTipo, midY);
  setText(doc, legPaintSize, false);
  pdfText(doc,"1-Sede", legX, midY - 4);
  pdfText(doc,"2-Outros", legX, midY + 9);

  cursorY += rowH + 5;

  // Row 2: Data | Ciclo | Atividade
  const wData = 200;
  const wCiclo = 200;
  const wAtiv = PAGE_W - 2 * MARGIN - wData - wCiclo - gap * 2;

  const year = date.split("-").pop() ?? "";
  const cicloRaw = calculateCicloFromDate(date);
  const ciclo = cicloRaw !== "" && year !== "" ? `${cicloRaw} / ${year}` : cicloRaw;
  const atividadeCode = last?.atividade != null ? String(last.atividade) : "4";

  cx = MARGIN;
  dBox(wData, "Data da atividade", date);
  dBox(wCiclo, "Ciclo/Ano", ciclo);

  // Box Atividade custom
  const ativX = cx;
  fillRect(doc, ativX, cursorY, wAtiv, labelH, HEADER_BG);
  strokeRect(doc, ativX, cursorY, wAtiv, rowH);
  setLine(doc);
  doc.line(ativX, cursorY + labelH, ativX + wAtiv, cursorY + labelH);
  drawCenteredText(doc, "Atividade", ativX, cursorY, wAtiv, labelH, labelSize, false);

  const ativValW = wAtiv * 0.1;
  const ativLegW = wAtiv - ativValW;
  drawCenteredText(doc, atividadeCode, ativX, cursorY + labelH, ativValW, bottomH, dataSize, true);
  doc.line(ativX + ativValW, cursorY + labelH, ativX + ativValW, cursorY + rowH);

  const gridX = ativX + ativValW;
  const col1W = ativLegW * 0.28;
  const col2W = ativLegW * 0.44;
  const col3W = ativLegW * 0.28;
  const gRowH = bottomH / 2;
  doc.line(gridX, cursorY + labelH + gRowH, gridX + ativLegW, cursorY + labelH + gRowH);
  doc.line(gridX + col1W, cursorY + labelH, gridX + col1W, cursorY + rowH);
  doc.line(gridX + col1W + col2W, cursorY + labelH, gridX + col1W + col2W, cursorY + rowH);

  const pGridSize = 5;
  const items = [
    "1-LI-Levantamento de Índice",
    "2-LI+T-Levantamento/Índice + Tratamento",
    "3-PE-Ponto Estratégico",
    "4-T-Tratamento",
    "5-DF-Delimitação de Foco",
    "6-PVE-Pesquisa Vetorial Espacial",
  ];
  const dText = (txt: string, x: number, y: number, w: number) => {
    drawTextInBox(doc, txt, x, y, w, gRowH, pGridSize, false);
  };
  dText(items[0], gridX, cursorY + labelH, col1W);
  dText(items[1], gridX + col1W, cursorY + labelH, col2W);
  dText(items[2], gridX + col1W + col2W, cursorY + labelH, col3W);
  dText(items[3], gridX, cursorY + labelH + gRowH, col1W);
  dText(items[4], gridX + col1W, cursorY + labelH + gRowH, col2W);
  dText(items[5], gridX + col1W + col2W, cursorY + labelH + gRowH, col3W);

  cursorY += rowH + 5;

  // Barra cinza
  const grayBarH = 20;
  drawRectBox(doc, MARGIN, cursorY, PAGE_W - 2 * MARGIN, grayBarH, "PESQUISA ENTOMOLÓGICA / TRATAMENTO", 8, true, HEADER_BG);
  cursorY += grayBarH;

  // Cabeçalho da tabela
  const ch = 80;
  const headerY = cursorY;
  const cwQuart = 25;
  const cwNum = 25;
  const cwSeq = 20;
  const cwComp = 20;
  const cwTipo = 20;
  const cwHora = 35;
  const cwSit = 25;
  const cwDa = 20;
  const cwDepSingle = 20;
  const cwDepTotal = cwDepSingle * 7;
  const cwElim = 25;
  const cwAmostraSingle = 29;
  const cwAmostraTotal = cwAmostraSingle * 3;
  const cwInsp = 20;
  const cwImovTrat = 20;
  const cwLarvItem = 28;
  const cwLarv1Total = cwLarvItem * 2;
  const cwLarv2Total = cwLarvItem * 2;
  const cwAdultTotal = cwLarvItem * 2;
  const cwTratTotal = cwImovTrat + cwLarv1Total + cwLarv2Total + cwAdultTotal;

  const fixedWidths =
    cwQuart + cwNum + cwSeq + cwComp + cwTipo + cwHora + cwSit + cwDa + cwDepTotal + cwElim + cwAmostraTotal + cwInsp + cwTratTotal;
  const finalCwLog = PAGE_W - 2 * MARGIN - fixedWidths;

  fillRect(doc, MARGIN, headerY, PAGE_W - MARGIN - MARGIN, ch, HEADER_BG);

  let tx = MARGIN;
  const dVert = (label: string, w: number) => {
    drawVerticalHeader(doc, label, tx, headerY, w, ch, 8, false, null);
    tx += w;
  };

  dVert("Nº do quarteirão", cwQuart);
  drawRectBox(doc, tx, headerY, finalCwLog, ch, "Logradouro", 8, true, null);
  tx += finalCwLog;
  drawRectBox(doc, tx, headerY, cwNum, ch, "Nº", 8, true, null);
  tx += cwNum;
  dVert("Sequência", cwSeq);
  dVert("Complemento", cwComp);
  dVert("Tipo do Imóvel", cwTipo);
  dVert("Hora de Entrada", cwHora);
  dVert("Situação", cwSit);

  const wDepGroup = cwDa + cwDepTotal + cwElim;
  drawRectBox(doc, tx, headerY, wDepGroup, ch / 3, "Nº DE DEPÓSITOS", 8, true, null);
  const r2Y = headerY + ch / 3;
  const subH = ch / 3;
  const tallH = (ch / 3) * 2;
  drawVerticalHeader(doc, "D.A.", tx, r2Y, cwDa, tallH, 8, false, null);
  drawRectBox(doc, tx + cwDa, r2Y, cwDepTotal, subH, "Tipos de Depósitos", 8, false, null);
  const elimXBase = tx + cwDa + cwDepTotal;
  drawRectBox(doc, elimXBase, r2Y, cwElim, tallH, "", 8, false, null);

  const eCenterX = elimXBase + cwElim / 2;
  const eCenterY = r2Y + tallH / 2;
  const eb1 = tightBounds("Depósitos", 8, false);
  setText(doc, 8, false);
  pdfText(doc,"Depósitos", eCenterX - 1, eCenterY + textWidth("Depósitos", 8, false) / 2, { angle: 90 });
  pdfText(doc,"Eliminados", eCenterX + eb1.h + 1, eCenterY + textWidth("Eliminados", 8, false) / 2, { angle: 90 });

  let depX = tx + cwDa;
  for (const d of ["A1", "A2", "B", "C", "D1", "D2", "E"]) {
    drawRectBox(doc, depX, headerY + (ch / 3) * 2, cwDepSingle, subH, d, 8, false, null);
    depX += cwDepSingle;
  }
  tx += wDepGroup;

  drawRectBox(doc, tx, headerY, cwAmostraTotal, subH, "Coleta Amostra", 8, true, null);
  const wNumAmostra = cwAmostraSingle * 2;
  const wTubitos = cwAmostraSingle;
  drawRectBox(doc, tx, r2Y, wNumAmostra, subH, "Nº da Amostra", 8, false, null);
  drawVerticalHeader(doc, "Qtde. Tubitos", tx + wNumAmostra, r2Y, wTubitos, tallH, 8, false, null);
  let amX = tx;
  for (const l of ["Inicial", "Final"]) {
    drawVerticalHeader(doc, l, amX, headerY + (ch / 3) * 2, cwAmostraSingle, subH, 8, false, null);
    amX += cwAmostraSingle;
  }
  tx += cwAmostraTotal;

  dVert("Imóv. Inspec.", cwInsp);

  const tratY1 = headerY;
  const trH1 = 15;
  const trH2 = 15;
  const trH3 = 15;
  const trH4 = ch - trH1 - trH2 - trH3;
  drawRectBox(doc, tx, tratY1, cwTratTotal, trH1, "Tratamento", 8, true, null);
  const yR2 = tratY1 + trH1;
  drawRectBox(doc, tx, yR2, cwImovTrat + cwLarv1Total + cwLarv2Total, trH2, "Focal", 8, false, null);
  drawRectBox(doc, tx + cwImovTrat + cwLarv1Total + cwLarv2Total, yR2, cwAdultTotal, trH2, "Perifocal", 8, false, null);
  const yR3 = yR2 + trH2;
  drawVerticalHeader(doc, "Imóv. Trat.", tx, yR3, cwImovTrat, trH3 + trH4, 8, false, null);
  drawRectBox(doc, tx + cwImovTrat, yR3, cwLarv1Total, trH3, "Larvicida 1", 8, false, null);
  drawRectBox(doc, tx + cwImovTrat + cwLarv1Total, yR3, cwLarv2Total, trH3, "Larvicida 2", 8, false, null);
  drawRectBox(doc, tx + cwImovTrat + cwLarv1Total + cwLarv2Total, yR3, cwAdultTotal, trH3, "Adulticida", 8, false, null);
  const yR4 = yR3 + trH3;
  let lX = tx + cwImovTrat;
  for (const l of ["Qtde.\n(gramas)", "Qtde.\ndep.\nTrat."]) {
    drawVerticalHeader(doc, l, lX, yR4, cwLarvItem, trH4, 8, false, null);
    lX += cwLarvItem;
  }
  for (const l of ["Qtde.\n(gramas)", "Qtde.\ndep.\nTrat."]) {
    drawVerticalHeader(doc, l, lX, yR4, cwLarvItem, trH4, 8, false, null);
    lX += cwLarvItem;
  }
  for (const l of ["Tipo", "Qtde.\nCargas"]) {
    drawVerticalHeader(doc, l, lX, yR4, cwLarvItem, trH4, 8, false, null);
    lX += cwLarvItem;
  }

  cursorY += ch;

  // Linhas
  const gridRowH = 12;
  const maxRows = 20;
  const widths = [
    cwQuart, finalCwLog, cwNum, cwSeq, cwComp, cwTipo, cwHora, cwSit, cwDa,
    cwDepSingle, cwDepSingle, cwDepSingle, cwDepSingle, cwDepSingle, cwDepSingle, cwDepSingle,
    cwElim, cwAmostraSingle, cwAmostraSingle, cwAmostraSingle, cwInsp,
    cwImovTrat, cwLarvItem, cwLarvItem, cwLarvItem, cwLarvItem, cwLarvItem, cwLarvItem,
  ];
  const alignLefts = widths.map((_, i) => i === 1);

  for (let i = 0; i < maxRows; i++) {
    const house = houses[i] ?? null;
    const blockText =
      house && (house.blockSequence ?? "") !== ""
        ? `${house.blockNumber} / ${house.blockSequence}`
        : house?.blockNumber ?? "";
    const logPaintSize = 10;
    const streetNameRaw = formatStreetName(house?.streetName ?? "");
    const availableDescWidth = finalCwLog - 7;
    const streetName = fitToWidth(streetNameRaw, logPaintSize, false, availableDescWidth);

    const chkV = (v: string | undefined): string => (streetName !== "" && (v == null || v === "")) ? "—" : (v ?? "");

    const numStr = chkV(house?.number);
    const seqStr = house?.sequence != null && house.sequence !== 0 ? String(house.sequence) : "";
    const complStr = house?.complement != null && house.complement !== 0 ? String(house.complement) : "";
    const propType = propertyTypeCode(house?.propertyType);
    const horaStr = "";
    const sit = situationCode(house?.situation);
    const open = isOpen(sit);
    const daStr = "";

    const depValues =
      house !== null && open
        ? [house.a1 ?? 0, house.a2 ?? 0, house.b ?? 0, house.c ?? 0, house.d1 ?? 0, house.d2 ?? 0, house.e ?? 0]
        : [0, 0, 0, 0, 0, 0, 0];
    const depStrs = depValues.map((v) => (v > 0 ? String(v) : "")).map((v) => (open ? chkV(v) : ""));

    const elim = house != null && (house.eliminados ?? 0) > 0 && open ? String(house.eliminados) : "";
    const elimStr = open ? chkV(elim) : "";

    const samples = ["", "", ""];
    const inspected = "";

    const larvG = house != null && (house.larvicida ?? 0) > 0 && open ? String(house.larvicida) : "";
    const treated = larvG !== "" || (house != null && (house.eliminados ?? 0) > 0) ? "X" : "";
    const treatedStr = open ? chkV(treated) : "";
    const larvGStr = open ? chkV(larvG) : "";

    const sumDeps = house != null && open
      ? (house.a1 ?? 0) + (house.a2 ?? 0) + (house.b ?? 0) + (house.c ?? 0) + (house.d1 ?? 0) + (house.d2 ?? 0) + (house.e ?? 0)
      : 0;
    const sumDepsStr = sumDeps > 0 ? String(sumDeps) : "";
    const sumDepsStrFinal = open ? chkV(sumDepsStr) : "";

    const rowValues: string[] = [
      blockText, streetName, numStr, chkV(seqStr), chkV(complStr), propType, horaStr, chkV(sit), daStr,
    ];
    rowValues.push(...depStrs);
    rowValues.push(elimStr);
    rowValues.push(...samples);
    rowValues.push(inspected);
    rowValues.push(treatedStr);
    rowValues.push(larvGStr);
    rowValues.push(sumDepsStrFinal);

    if (house != null && house.comFoco && open) {
      const v26 = [...rowValues, "", ""];
      drawRow(doc, MARGIN, cursorY, gridRowH, widths.slice(0, 26), v26, {
        alignLefts: alignLefts.slice(0, 26),
        bolds: Array(26).fill(false),
        size: 8,
      });
      const mergedX = MARGIN + widths.slice(0, 26).reduce((a, b) => a + b, 0);
      const mergedW = cwLarvItem * 2;
      drawCell(doc, "Com Foco", mergedX, cursorY, mergedW, gridRowH, 8, true);
    } else {
      const v28 = [...rowValues, "", "", "", ""];
      drawRow(doc, MARGIN, cursorY, gridRowH, widths, v28, {
        alignLefts,
        bolds: Array(28).fill(false),
        size: 8,
      });
    }
    cursorY += gridRowH;
  }

  // Totais
  const totalRowH = 15;
  const wLabel = cwQuart + finalCwLog + cwNum + cwSeq + cwComp + cwTipo + cwHora + cwSit + cwDa;
  const workedHouses = houses.filter((h) => isOpen(situationCode(h.situation)));
  const sums: number[] = [];
  if (workedHouses.length > 0) {
    const sum = (k: "a1" | "a2" | "b" | "c" | "d1" | "d2" | "e") => workedHouses.reduce((acc, h) => acc + (h[k] ?? 0), 0);
    sums.push(sum("a1"), sum("a2"), sum("b"), sum("c"), sum("d1"), sum("d2"), sum("e"));
  } else {
    sums.push(0, 0, 0, 0, 0, 0, 0);
  }
  const totalElim = workedHouses.reduce((acc, h) => acc + (h.eliminados ?? 0), 0);
  const totalTreated = workedHouses.filter((h) => (h.larvicida ?? 0) > 0 || (h.eliminados ?? 0) > 0).length;
  const totalLarv = workedHouses.reduce((acc, h) => acc + (h.larvicida ?? 0), 0);
  const totalLarvStr = formatDouble(totalLarv);
  const totalDepsTreated = sums.reduce((a, b) => a + b, 0);

  const totalWidths = [
    wLabel,
    ...Array(7).fill(cwDepSingle),
    cwElim,
    cwAmostraSingle, cwAmostraSingle, cwAmostraSingle,
    cwInsp,
    cwImovTrat,
    cwLarvItem,
    cwLarvItem,
    cwLarvItem, cwLarvItem, cwLarvItem, cwLarvItem,
  ];
  const totalValues: string[] = ["TOTAIS"];
  totalValues.push(...sums.map((v) => String(v)));
  totalValues.push(String(totalElim));
  totalValues.push("", "", "", "");
  totalValues.push(String(totalTreated));
  totalValues.push(totalLarvStr);
  totalValues.push(String(totalDepsTreated));
  totalValues.push("", "", "", "");
  const totalBolds = totalWidths.map(() => true);
  drawRow(doc, MARGIN, cursorY, totalRowH, totalWidths, totalValues, {
    alignLefts: Array(totalWidths.length).fill(false),
    bolds: totalBolds,
    size: 8,
    bg: HEADER_BG,
  });
  cursorY += totalRowH + 10;

  // Rodapé: convenções e situação
  const footerRowH = 15;
  const wConv = PAGE_W / 2 - MARGIN - 10;
  const convX = MARGIN;
  drawRectBox(doc, convX, cursorY, wConv, footerRowH, "CONVENÇÕES", 8, true, HEADER_BG);
  drawRectBox(doc, convX, cursorY + footerRowH, wConv, footerRowH, "R-Residência  C-Comércio  TB-Terreno Baldio  PE-Ponto Estratégico  O-Outros  D.A.-Difícil Acesso", 7, false, null);
  const startSit = MARGIN + wConv + 20;
  drawRectBox(doc, startSit, cursorY, wConv, footerRowH, "SITUAÇÃO", 8, true, HEADER_BG);
  drawRectBox(doc, startSit, cursorY + footerRowH, wConv, footerRowH, "F-Fechado  REC-Recusado  A-Abandonado  V-Vazio", 7, false, null);
  cursorY += footerRowH * 2 + 15;
  if (cursorY + 30 > PAGE_H) cursorY = PAGE_H - 30;

  // Assinaturas
  const sigW = 160;
  const totalWidth = PAGE_W - 2 * MARGIN;
  const gapSig = (totalWidth - 4 * sigW) / 3;
  let sigX = MARGIN;

  const agLabel = "Agente: ";
  const labelW = textWidth(agLabel, 8, false) + 5;
  drawTextInRect(doc, agLabel, sigX, cursorY, labelW, 20, 8, false, true);
  const lineStart = sigX + labelW;
  const lineEnd = sigX + sigW - 5;
  setLine(doc);
  doc.line(lineStart, cursorY + 12, lineEnd, cursorY + 12);
  if (agentName.trim() !== "") {
    drawTextInRect(doc, agentName, lineStart, cursorY - 3, lineEnd - lineStart, 20, 10, true, true);
  }
  sigX += sigW + gapSig;
  drawTextInRect(doc, "Supervisor: ___________________", sigX, cursorY, sigW, 20, 8, false, true);
  sigX += sigW + gapSig;
  drawTextInRect(doc, "Sup. Geral: ___________________", sigX, cursorY, sigW, 20, 8, false, true);
  sigX += sigW + gapSig;
  drawTextInRect(doc, "Laboratório: __________________", sigX, cursorY, sigW, 20, 8, false, true);
  drawTextInRect(doc, "FAD-01(Frente)", MARGIN, cursorY + 12, 100, 15, 6, false, true);
}

// ------------------------------------------------------------
// Verso (drawBackPage)
// ------------------------------------------------------------

export function drawBackPage(doc: jsPDF, chunkHouses: HouseDoc[], date: string, quarteiraoConcluido: boolean, localidadeConcluida: boolean, workedPairs: Array<[string, string]>, completedPairs: Array<[string, string]>): void {
  let cursorY = MARGIN;
  const textSize = 8;
  const smallSize = 7;
  const dataSize = 10;

  // Block 1: Visita
  const wB1 = 130;
  const hRowB1 = 14;
  drawRectBox(doc, MARGIN, cursorY, wB1, hRowB1, "VISITA", textSize, true, HEADER_BG);
  const yR1Visita = cursorY + hRowB1;
  const wLabel = wB1 - 25;
  const wCheck = 25;
  drawRectBox(doc, MARGIN, yR1Visita, wLabel, hRowB1, "NORMAL", textSize, false, null, true);
  drawRectBox(doc, MARGIN + wLabel, yR1Visita, wCheck, hRowB1, "X", textSize, true, null);
  const yR2Visita = yR1Visita + hRowB1;
  drawRectBox(doc, MARGIN, yR2Visita, wLabel, hRowB1, "RECUPERAÇÃO", textSize, false, null, true);
  drawRectBox(doc, MARGIN + wLabel, yR2Visita, wCheck, hRowB1, "", textSize, true, null);

  // Block 2: Equipe / Agente
  const gap = 60;
  const xB2 = MARGIN + wB1 + gap;
  const wB2 = 250;
  const wLabelB2 = 90;
  const wValB2 = wB2 - wLabelB2;
  const hRowB2 = 14;
  drawRectBox(doc, xB2, cursorY, wLabelB2, hRowB2, "Equipe / Agente", textSize, false, null, true);
  const teamName = (chunkHouses[chunkHouses.length - 1]?.agentName ?? "").toUpperCase() || "PMBJ";
  drawRectBox(doc, xB2 + wLabelB2, cursorY, wValB2, hRowB2, teamName, textSize, true, null);
  const yR1Equipe = cursorY + hRowB2;
  drawRectBox(doc, xB2, yR1Equipe, wLabelB2, hRowB2, "Data", textSize, false, null, true);
  drawRectBox(doc, xB2 + wLabelB2, yR1Equipe, wValB2, hRowB2, date, textSize, true, null);
  const yR2Equipe = yR1Equipe + hRowB2;
  drawRectBox(doc, xB2, yR2Equipe, wLabelB2, hRowB2, "Localidade Concluída", textSize, false, null, true);
  drawRectBox(doc, xB2 + wLabelB2, yR2Equipe, wValB2, hRowB2, localidadeConcluida ? "SIM" : "NÃO", textSize, true, null);

  // Block 3: Quarteirão
  const xB3 = xB2 + wB2 + gap;
  const wB3 = PAGE_W - MARGIN - xB3;
  const wCommonLabel = 145;
  const wRestR1 = wB3 - wCommonLabel;
  const wBoxR1 = wRestR1 / 5;
  const hRowB3 = 14;
  drawRectBox(doc, xB3, cursorY, wCommonLabel, hRowB3, "Nº e sequência dos quarteirões", textSize, false, null, true);
  let qxTop = xB3 + wCommonLabel;
  for (let i = 0; i < 5; i++) {
    const pair = workedPairs[i];
    const qStr = pair ? (pair[1] !== "" ? `${pair[0]} / ${pair[1]}` : pair[0]) : "";
    drawRectBox(doc, qxTop, cursorY, wBoxR1, hRowB3, qStr, textSize, true, null);
    qxTop += wBoxR1;
  }
  const wRestR2 = wB3 - wCommonLabel;
  const wBoxR2 = wRestR2 / 4;
  drawRectBox(doc, xB3, cursorY + hRowB3, wCommonLabel, hRowB3, "Quarteirão Concluído?", textSize, false, null, true);
  let qxBot = xB3 + wCommonLabel;
  const yR2Right = cursorY + hRowB3;
  const qSim = quarteiraoConcluido ? "X" : "";
  const qNao = !quarteiraoConcluido ? "X" : "";
  drawRectBox(doc, qxBot, yR2Right, wBoxR2, hRowB3, "SIM", smallSize, false, null);
  qxBot += wBoxR2;
  drawRectBox(doc, qxBot, yR2Right, wBoxR2, hRowB3, qSim, textSize, true, null);
  qxBot += wBoxR2;
  drawRectBox(doc, qxBot, yR2Right, wBoxR2, hRowB3, "NÃO", smallSize, false, null);
  qxBot += wBoxR2;
  drawRectBox(doc, qxBot, yR2Right, wBoxR2, hRowB3, qNao, textSize, true, null);

  cursorY += hRowB1 * 3 + 8;

  // Gray header
  const gh = 15;
  drawRectBox(doc, MARGIN, cursorY, PAGE_W - 2 * MARGIN, gh, "RESUMO DIÁRIO DO TRABALHO DE CAMPO", textSize, true, HEADER_BG);
  cursorY += gh + 5;

  const isWorked = (h: HouseDoc) => isOpen(situationCode(h.situation));

  // Table 1
  const typeR = chunkHouses.filter((h) => h.propertyType === "R" && isWorked(h)).length;
  const typeC = chunkHouses.filter((h) => h.propertyType === "C" && isWorked(h)).length;
  const typeTB = chunkHouses.filter((h) => h.propertyType === "TB" && isWorked(h)).length;
  const typePE = chunkHouses.filter((h) => h.propertyType === "PE" && isWorked(h)).length;
  const typeO = chunkHouses.filter((h) => h.propertyType === "O" && isWorked(h)).length;
  const totalTypes = typeR + typeC + typeTB + typePE + typeO;

  const t1Labels = ["Residência", "Comércio", "TB", "PE", "Outros", "Total"];
  const t1Vals = [typeR, typeC, typeTB, typePE, typeO, totalTypes];
  const colW1 = 35;
  const wT1 = colW1 * 6;
  let cx = MARGIN;
  const hRowT = 15;

  drawRectBox(doc, cx, cursorY, wT1, hRowT, "Nº de Imóveis Trabalhados por tipo", textSize, true, HEADER_BG);
  drawRow(doc, cx, cursorY + hRowT, hRowT, Array(6).fill(colW1), t1Labels, {
    alignLefts: Array(6).fill(false),
    bolds: Array(6).fill(false),
    size: smallSize,
    bg: HEADER_BG,
  });
  const t1ValStrs = t1Vals.map((v) => dsh(v));
  drawRow(doc, cx, cursorY + hRowT * 2, hRowT * 2, Array(6).fill(colW1), t1ValStrs, {
    alignLefts: Array(6).fill(false),
    bolds: Array(6).fill(true),
    size: dataSize,
  });
  cx += wT1 + 10;

  // Table 2
  const t2Labels = ["Trat. Focal", "Trat. Perifocal", "Inspecionados"];
  const tratFocal = chunkHouses.filter((h) => (h.larvicida ?? 0) > 0).length;
  const t2Vals = [String(tratFocal), "—", "—"];
  const colW2 = 60;
  const wT2 = colW2 * 3;
  drawRectBox(doc, cx, cursorY, wT2, hRowT, "Nº de Imóveis", textSize, true, HEADER_BG);
  drawRow(doc, cx, cursorY + hRowT, hRowT, Array(3).fill(colW2), t2Labels, {
    alignLefts: Array(3).fill(false),
    bolds: Array(3).fill(false),
    size: smallSize,
    bg: HEADER_BG,
  });
  const t2ValStrs = t2Vals.map((v) => (v === "0" ? "—" : v));
  drawRow(doc, cx, cursorY + hRowT * 2, hRowT * 2, Array(3).fill(colW2), t2ValStrs, {
    alignLefts: Array(3).fill(false),
    bolds: Array(3).fill(true),
    size: dataSize,
  });
  cx += wT2 + 10;

  // Table 3
  const t3Labels = ["Fechados", "Recusas", "Aband.", "Vazios"];
  const pendF = chunkHouses.filter((h) => h.situation === "F").length;
  const pendR = chunkHouses.filter((h) => h.situation === "REC").length;
  const pendA = chunkHouses.filter((h) => h.situation === "A").length;
  const pendV = chunkHouses.filter((h) => h.situation === "V").length;
  const t3Vals = [pendF, pendR, pendA, pendV];
  const colW3 = 40;
  const wT3 = colW3 * 4;
  drawRectBox(doc, cx, cursorY, wT3, hRowT, "Pendência", textSize, true, HEADER_BG);
  drawRow(doc, cx, cursorY + hRowT, hRowT, Array(4).fill(colW3), t3Labels, {
    alignLefts: Array(4).fill(false),
    bolds: Array(4).fill(false),
    size: smallSize,
    bg: HEADER_BG,
  });
  const t3ValStrs = t3Vals.map((v) => dsh(v));
  drawRow(doc, cx, cursorY + hRowT * 2, hRowT * 2, Array(4).fill(colW3), t3ValStrs, {
    alignLefts: Array(4).fill(false),
    bolds: Array(4).fill(true),
    size: dataSize,
  });
  cx += wT3 + 10;

  // Table 4
  const t4Labels = ["A1", "A2", "B", "C", "D1", "D2", "E", "Total"];
  const sumK = (k: "a1" | "a2" | "b" | "c" | "d1" | "d2" | "e") => chunkHouses.reduce((acc, h) => acc + (h[k] ?? 0), 0);
  const sA1 = sumK("a1");
  const sA2 = sumK("a2");
  const sB = sumK("b");
  const sC = sumK("c");
  const sD1 = sumK("d1");
  const sD2 = sumK("d2");
  const sE = sumK("e");
  const sTotal = sA1 + sA2 + sB + sC + sD1 + sD2 + sE;
  const t4Vals = [sA1, sA2, sB, sC, sD1, sD2, sE, sTotal];
  const colW4 = 28;
  const wT4 = colW4 * 8;
  drawRectBox(doc, cx, cursorY, wT4, hRowT, "Nº de depósito por tipo", textSize, true, HEADER_BG);
  drawRow(doc, cx, cursorY + hRowT, hRowT, Array(8).fill(colW4), t4Labels, {
    alignLefts: Array(8).fill(false),
    bolds: Array(8).fill(false),
    size: smallSize,
    bg: HEADER_BG,
  });
  const t4ValStrs = t4Vals.map((v) => dsh(v));
  drawRow(doc, cx, cursorY + hRowT * 2, hRowT * 2, Array(8).fill(colW4), t4ValStrs, {
    alignLefts: Array(8).fill(false),
    bolds: Array(8).fill(true),
    size: dataSize,
  });

  cursorY += hRowT * 3 + 25;

  // Row 2: Depósitos & Quarteirões
  const r2Y = cursorY;
  const wElim = 50;
  const wTratItem = 45;
  const wTrat = wTratItem * 4;
  const wAdult = wTratItem * 2;
  const wTubitos = 60;

  let dx = MARGIN;
  const hHeader = 15;
  const hData = 18;
  const wTratGroup = wTrat;
  const wDepBlock = wElim + wTratGroup;

  drawRectBox(doc, dx, r2Y, wDepBlock, hHeader, "Depósitos", textSize, true, HEADER_BG);
  drawRectBox(doc, dx, r2Y + hHeader, wElim, hHeader * 3, "Eliminados", smallSize, false, null);
  drawRectBox(doc, dx, r2Y + hHeader * 4, wElim, hData, String(chunkHouses.reduce((acc, h) => acc + (h.eliminados ?? 0), 0)), dataSize, true, null);
  dx += wElim;

  drawRectBox(doc, dx, r2Y + hHeader, wTratGroup, hHeader, "Tratados", smallSize, false, null);
  const wBTI = wTratGroup / 2;
  drawRectBox(doc, dx, r2Y + hHeader * 2, wBTI, hHeader, "BTI WDG", smallSize, false, null);
  drawRectBox(doc, dx + wBTI, r2Y + hHeader * 2, wBTI, hHeader, "BTI G", smallSize, false, null);
  const wColTrat = wBTI / 2;
  const labelsTrat = ["Qtde. (g)", "Dep. Trat.", "Qtde. (g)", "Dep. Trat."];
  const larvOutput = chunkHouses.reduce((acc, h) => acc + (h.larvicida ?? 0), 0);
  const larvOutputStr = larvOutput > 0 ? larvOutput.toFixed(1).replace(".", ",") : "—";
  const larvDep = chunkHouses.filter((h) => (h.larvicida ?? 0) > 0).length;
  const larvDepStr = larvDep > 0 ? String(larvDep) : "—";
  const tratWidths = Array(4).fill(wColTrat);
  drawRow(doc, dx, r2Y + hHeader * 3, hHeader, tratWidths, labelsTrat, {
    alignLefts: Array(4).fill(false),
    bolds: Array(4).fill(false),
    size: 6,
  });
  drawRow(doc, dx, r2Y + hHeader * 4, hData, tratWidths, [larvOutputStr, larvDepStr, "—", "—"], {
    alignLefts: Array(4).fill(false),
    bolds: Array(4).fill(true),
    size: dataSize,
  });
  dx += wTratGroup;

  const hAdSubLabel = 45;
  drawRectBox(doc, dx, r2Y, wAdult, hHeader, "Adulticida", textSize, true, HEADER_BG);
  const wAdCol = wAdult / 2;
  drawRectBox(doc, dx, r2Y + hHeader, wAdCol, hAdSubLabel, "Tipo", smallSize, false, null);
  drawRectBox(doc, dx + wAdCol, r2Y + hHeader, wAdCol, hAdSubLabel, "Cargas", smallSize, false, null);
  drawRectBox(doc, dx, r2Y + hHeader + hAdSubLabel, wAdCol, hData, "—", dataSize, true, null);
  drawRectBox(doc, dx + wAdCol, r2Y + hHeader + hAdSubLabel, wAdCol, hData, "—", dataSize, true, null);
  dx += wAdult;

  const hTubHeader = 60;
  drawRectBox(doc, dx, r2Y, wTubitos, hTubHeader, "", textSize, true, HEADER_BG);
  const line1 = "Nº Tubitos/";
  const line2 = "Amostras";
  const line3 = "Coletadas";
  const b1 = tightBounds(line1, textSize, true);
  const b2 = tightBounds(line2, textSize, true);
  const b3 = tightBounds(line3, textSize, true);
  const ttH = b1.h + b2.h + b3.h + 10;
  let curTY = r2Y + (hTubHeader - ttH) / 2 + b1.h;
  setText(doc, textSize, true);
  pdfText(doc,line1, dx + (wTubitos - b1.w) / 2, curTY);
  curTY += b2.h + 4;
  pdfText(doc,line2, dx + (wTubitos - b2.w) / 2, curTY);
  curTY += b3.h + 4;
  pdfText(doc,line3, dx + (wTubitos - b3.w) / 2, curTY);
  drawRectBox(doc, dx, r2Y + hTubHeader, wTubitos, hData, "—", dataSize, true, null);
  dx += wTubitos;

  const qGap = 15;
  const qx = dx + qGap;
  const wRightSection = PAGE_W - MARGIN - qx;
  const hGrid = hHeader + hData * 2;
  drawRectBox(doc, qx, r2Y, wRightSection, hHeader, "Nº e sequência dos quarteirões trabalhados", textSize, true, HEADER_BG);
  const qColW = wRightSection / 6;
  for (let r = 0; r < 2; r++) {
    let curQx = qx;
    for (let c = 0; c < 6; c++) {
      const idx = r * 6 + c;
      const pair = workedPairs[idx];
      drawBlockCell(doc, pair?.[0] ?? null, pair?.[1] ?? null, curQx, r2Y + hHeader + r * hData, qColW, hData, dataSize);
      curQx += qColW;
    }
  }
  const q2Y = r2Y + hGrid;
  drawRectBox(doc, qx, q2Y, wRightSection, hHeader, "Nº e sequência dos quarteirões concluídos", textSize, true, HEADER_BG);
  for (let r = 0; r < 2; r++) {
    let curQx = qx;
    for (let c = 0; c < 6; c++) {
      const idx = r * 6 + c;
      const pair = completedPairs[idx];
      drawBlockCell(doc, pair?.[0] ?? null, pair?.[1] ?? null, curQx, q2Y + hHeader + r * hData, qColW, hData, dataSize);
      curQx += qColW;
    }
  }

  cursorY = r2Y + hGrid * 2 + 15;

  // Resumo do Laboratório
  const hLabHeader = 15;
  drawRectBox(doc, MARGIN, cursorY, PAGE_W - 2 * MARGIN, hLabHeader, "RESUMO DO LABORATÓRIO", textSize, true, HEADER_BG);
  cursorY += hLabHeader + 5;

  const wAes = (PAGE_W - 2 * MARGIN - 10) / 2;
  const hAesRow = 18;
  drawRectBox(doc, MARGIN, cursorY, wAes, hAesRow, "Nº e sequência dos quarteirões com Aedes aegypti", smallSize, false, HEADER_BG);
  const wColAe = wAes / 8;
  for (let r = 0; r < 2; r++) {
    let ax = MARGIN;
    for (let c = 0; c < 8; c++) {
      drawRectBox(doc, ax, cursorY + hAesRow * (r + 1), wColAe, hAesRow, " /", textSize, false, null);
      ax += wColAe;
    }
  }
  const xRight = MARGIN + wAes + 10;
  drawRectBox(doc, xRight, cursorY, wAes, hAesRow, "Nº e sequência dos quarteirões com Aedes albopictus", smallSize, false, HEADER_BG);
  for (let r = 0; r < 2; r++) {
    let ax = xRight;
    for (let c = 0; c < 8; c++) {
      drawRectBox(doc, ax, cursorY + hAesRow * (r + 1), wColAe, hAesRow, " /", textSize, false, null);
      ax += wColAe;
    }
  }
  cursorY += hAesRow * 3 + 10;

  const wStatsLabel = 120;
  const wStatsCol = 42;
  const wStatsTotal = wStatsLabel + wStatsCol * 8;
  const hStHeader = 18;
  const hStRow = 22;

  let sx = MARGIN;
  drawRectBox(doc, sx, cursorY, wStatsLabel, hStHeader, "", textSize, false, HEADER_BG);
  sx += wStatsLabel;
  for (const l of ["A1", "A2", "B", "C", "D1", "D2", "E", "Total"]) {
    drawRectBox(doc, sx, cursorY, wStatsCol, hStHeader, l, smallSize, false, HEADER_BG);
    sx += wStatsCol;
  }
  sx = MARGIN;
  const yR1Stats = cursorY + hStHeader;
  drawRectBox(doc, sx, yR1Stats, wStatsLabel, hStRow, "Com Aedes aegypti", smallSize, false, null, true);
  sx += wStatsLabel;
  for (let c = 0; c < 8; c++) {
    drawRectBox(doc, sx, yR1Stats, wStatsCol, hStRow, "—", textSize, false, null);
    sx += wStatsCol;
  }
  sx = MARGIN;
  const yR2Stats = cursorY + hStHeader + hStRow;
  drawRectBox(doc, sx, yR2Stats, wStatsLabel, hStRow, "Com Aedes albopictus", smallSize, false, null, true);
  sx += wStatsLabel;
  for (let c = 0; c < 8; c++) {
    drawRectBox(doc, sx, yR2Stats, wStatsCol, hStRow, "—", textSize, false, null);
    sx += wStatsCol;
  }

  const xStage = 533;
  const wStageSection = PAGE_W - MARGIN - xStage;
  const wStageCol = wStageSection / 4;
  const stageLabels = ["Larvas", "Pupas", "Exúvia de Pupa", "Adultos"];
  let stX = xStage;
  for (const l of stageLabels) {
    drawRectBox(doc, stX, cursorY, wStageCol, hStHeader + hStRow - 5, l, 6.5, false, HEADER_BG);
    stX += wStageCol;
  }
  const sRH = 22;
  let currStY = cursorY + hStHeader + hStRow - 5;
  for (let r = 0; r < 3; r++) {
    stX = xStage;
    for (let c = 0; c < 4; c++) {
      drawRectBox(doc, stX, currStY, wStageCol, sRH, "", textSize, false, null);
      stX += wStageCol;
    }
    currStY += sRH;
  }

  cursorY = yR2Stats + hStRow + 25;

  // Legendas
  const colW = wStatsTotal / 4;
  const legH = 10;
  const lP = 5.5;
  const legends: Array<[string, number]> = [
    ["A1 - Caixa d'água (elevado)", MARGIN],
    ["A2 - Outros depósitos de armazenamento de água (baixo)", MARGIN + colW],
    ["B - Pequenos depósitos móveis", MARGIN + 2 * colW + 50],
    ["C - Depósitos fixos", MARGIN + 3 * colW + 50],
    ["D1 - Pneus e outros materiais rodantes", MARGIN],
    ["D2 - Lixo (recipientes plásticos, latas), sucatas, entulhos", MARGIN + colW],
    ["E - Depósitos naturais", MARGIN + 2 * colW + 50],
  ];
  for (let i = 0; i < 4; i++) {
    drawTextInBox(doc, legends[i][0], legends[i][1], cursorY, colW, legH, lP, false, true);
  }
  cursorY += legH;
  for (let i = 4; i < 7; i++) {
    drawTextInBox(doc, legends[i][0], legends[i][1], cursorY, colW, legH, lP, false, true);
  }
  cursorY += legH + 20;

  // Assinaturas
  const sigH = 30;
  const boxGap = 10;
  const w5 = (PAGE_W - 2 * MARGIN - boxGap * 4) / 5;
  let sigX = MARGIN;
  const footLabels = ["Data da Entrada", "Data da Conclusão", "Laboratório", "Laboratorista", "Assinatura"];
  for (const l of footLabels) {
    drawRectBox(doc, sigX, cursorY, w5, 15, l, smallSize, false, HEADER_BG);
    drawRectBox(doc, sigX, cursorY + 15, w5, sigH, "", textSize, false, null);
    sigX += w5 + boxGap;
  }
}

// ------------------------------------------------------------
// Geração
// ------------------------------------------------------------

export async function generateBoletimPdf(houses: HouseDoc[], date: string, agentName: string): Promise<jsPDF> {
  if (houses.length === 0) {
    throw new Error("Nenhum imóvel para gerar boletim");
  }
  const doc = new jsPDF({ orientation: "landscape", unit: "pt", format: [PAGE_W, PAGE_H], compress: true });
  setLine(doc);

  const sortedHouses = [...houses].sort((a, b) => (a.listOrder ?? 0) - (b.listOrder ?? 0));
  const chunks = createChunks(sortedHouses);
  const totalFolhas = chunks.length;

  for (let i = 0; i < chunks.length; i++) {
    const chunk = chunks[i];
    const stats = calculateBlockStats(sortedHouses, chunk);
    await drawFrontPage(doc, chunk, date, agentName, i + 1, totalFolhas);
    doc.addPage([PAGE_W, PAGE_H], "landscape");
    drawBackPage(doc, chunk, date, stats.quarteiraoConcluido, stats.localidadeConcluida, stats.workedBlocks, stats.completedBlocks);
    if (i < chunks.length - 1) doc.addPage([PAGE_W, PAGE_H], "landscape");
  }

  return doc;
}

export async function downloadBoletim(houses: HouseDoc[], date: string, agentName: string): Promise<void> {
  if (!houses.length) throw new Error("Nenhum imóvel para gerar boletim");
  const doc = await generateBoletimPdf(houses, date, agentName);
  const safe = agentName.trim().replace(/\s+/g, "_").replace(/[^a-zA-Z0-9_-]/g, "_");
  const blob = doc.output("blob");
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `Boletim_${date}_${safe || "agente"}.pdf`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
