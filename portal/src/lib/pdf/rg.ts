import { jsPDF } from "jspdf";
import type { HouseDoc } from "../types";
import { getVigilanciaBase64 } from "./logoLoader";
import {
  drawCenteredText,
  drawRectBox,
  drawTextInRect,
  drawVerticalHeader,
  fitToWidth,
  formatStreetName,
  propertyTypeCode,
  setLine,
  setText,
  strokeRect,
  situationCode,
  tightBounds,
} from "./shared";

// ============================================================
// Port 1:1 de RGPdfGenerator.kt (app Android).
// RG do quarteirão (A4 retrato, 52 casas por página, 2 colunas).
// ============================================================

const PAGE_WIDTH = 595;
const PAGE_HEIGHT = 842;
const MARGIN = 20;

const LOGO_VIG_W_H = 540 / 2108;

async function drawHeaderSection(doc: jsPDF, headerAreaWidth: number, headerY: number, cursorY: number): Promise<number> {
  const prefText = "PREFEITURA MUNICIPAL DE BOM JARDIM";
  const secText = "SECRETARIA MUNICIPAL DE SAÚDE";

  drawTextInRect(doc, prefText, MARGIN, headerY, headerAreaWidth, 10, 8, true, true);

  const prefWidth = doc.getTextWidth(prefText);
  const secWidth = doc.getTextWidth(secText);
  const secBounds = tightBounds(secText, 8, false);
  const secTy = headerY + 10 + (10 + secBounds.h) / 2 - secBounds.bottom;
  const centerPref = MARGIN + 4 + prefWidth / 2;
  const secX = Math.max(centerPref - secWidth / 2, MARGIN);
  setText(doc, 8, false);
  doc.text(secText, secX, secTy);

  const nextY = headerY + 10 + 10.2;
  const logoWidth = Math.min(140, headerAreaWidth);
  const logoHeight = LOGO_VIG_W_H * logoWidth;
  const vigilanciaBase64 = await getVigilanciaBase64();
  doc.addImage(`data:image/png;base64,${vigilanciaBase64}`, "PNG", MARGIN + 5, nextY, logoWidth, logoHeight);
  const logoBottom = nextY + logoHeight;
  return Math.max(logoBottom, cursorY + 60);
}

function drawResponsavelTable(doc: jsPDF, tableX: number, tableY: number, tableWidth: number, responsavelRowH: number, rowH: number): number {
  drawRectBox(doc, tableX, tableY, tableWidth, responsavelRowH, "RESPONSÁVEL", 8, true, null);

  const r1y = tableY + responsavelRowH;
  const c1w = tableWidth * 0.25;
  const c2w = tableWidth * 0.25;
  const c3w = tableWidth * 0.35;
  const c4w = tableWidth * 0.15;

  strokeRect(doc, tableX, r1y, c1w, rowH);
  drawTextInRect(doc, "Gerente", tableX, r1y, c1w, rowH, 8, false, true);
  strokeRect(doc, tableX + c1w, r1y, c2w, rowH);
  strokeRect(doc, tableX + c1w + c2w, r1y, c3w, rowH);
  drawTextInRect(doc, "Supervisor de Turma", tableX + c1w + c2w, r1y, c3w, rowH, 8, false, true);
  strokeRect(doc, tableX + c1w + c2w + c3w, r1y, c4w, rowH);

  const r2y = r1y + rowH;
  strokeRect(doc, tableX, r2y, c1w, rowH);
  drawTextInRect(doc, "Supervisor", tableX, r2y, c1w, rowH, 8, false, true);
  strokeRect(doc, tableX + c1w, r2y, c2w, rowH);
  strokeRect(doc, tableX + c1w + c2w, r2y, c3w, rowH);
  drawTextInRect(doc, "Agente", tableX + c1w + c2w, r2y, c3w, rowH, 8, false, true);
  strokeRect(doc, tableX + c1w + c2w + c3w, r2y, c4w, rowH);
  drawCenteredText(doc, "X", tableX + c1w + c2w + c3w, r2y, c4w, rowH, 8, false);

  return r2y + rowH;
}

function drawLocationHeaderTable(doc: jsPDF, cursorY: number, municipio: string, bairro: string, block: string, rowHeight: number, categoria?: string): void {
  const totalRowWidth = PAGE_WIDTH - 2 * MARGIN;
  const halfWidth = totalRowWidth / 2;
  const w1 = halfWidth / 2;
  const w2 = halfWidth / 2;
  const w3 = halfWidth / 2;
  const w4 = halfWidth / 2;

  let curX = MARGIN;
  strokeRect(doc, curX, cursorY, w1, rowHeight);
  drawTextInRect(doc, "MUNICÍPIO", curX, cursorY, w1, rowHeight, 8, false, true);
  curX += w1;
  strokeRect(doc, curX, cursorY, w2, rowHeight);
  drawCenteredText(doc, municipio, curX, cursorY, w2, rowHeight, 8, true);
  curX += w2;
  strokeRect(doc, curX, cursorY, w3, rowHeight);
  drawTextInRect(doc, "CATEGORIA", curX, cursorY, w3, rowHeight, 8, false, true);
  curX += w3;
  strokeRect(doc, curX, cursorY, w4, rowHeight);
  const categoriaSafe = (categoria || "BRR").trim().toUpperCase() || "BRR";
  drawCenteredText(doc, categoriaSafe, curX, cursorY, w4, rowHeight, 8, true);

  const line2y = cursorY + rowHeight;
  curX = MARGIN;
  strokeRect(doc, curX, line2y, w1, rowHeight);
  drawTextInRect(doc, "BAIRRO", curX, line2y, w1, rowHeight, 8, false, true);
  curX += w1;
  strokeRect(doc, curX, line2y, w2, rowHeight);
  drawCenteredText(doc, bairro, curX, line2y, w2, rowHeight, 8, true);
  curX += w2;
  strokeRect(doc, curX, line2y, w3, rowHeight);
  drawTextInRect(doc, "QUART. Nº", curX, line2y, w3, rowHeight, 8, false, true);
  curX += w3;
  strokeRect(doc, curX, line2y, w4, rowHeight);
  drawCenteredText(doc, block, curX, line2y, w4, rowHeight, 8, true);
}

function drawListHeader(doc: jsPDF, x: number, y: number, wLog: number, wN: number, wSeq: number, wComp: number, wTipo: number, wPend: number, h: number): void {
  strokeRect(doc, x, y, wLog, h);
  drawCenteredText(doc, "Logradouro", x, y, wLog, h, 8, false);
  strokeRect(doc, x + wLog, y, wN, h);
  drawCenteredText(doc, "Nº", x + wLog, y, wN, h, 8, false);

  drawVerticalHeader(doc, "Sequência", x + wLog + wN, y, wSeq, h, 8, false, null);
  drawVerticalHeader(doc, "Complemento", x + wLog + wN + wSeq, y, wComp, h, 8, false, null);
  drawVerticalHeader(doc, "Tipo do Imóvel", x + wLog + wN + wSeq + wComp, y, wTipo, h, 8, false, null);
  drawVerticalHeader(doc, "Pendência", x + wLog + wN + wSeq + wComp + wTipo, y, wPend, h, 8, false, null);
}

function drawHouseRow(doc: jsPDF, house: HouseDoc, x: number, y: number, wLog: number, wN: number, wSeq: number, wComp: number, wTipo: number, wPend: number, h: number): void {
  let cur = x;

  const streetNameRaw = formatStreetName(house.streetName || "");
  const availableDescWidth = wLog - 6;
  const streetName = fitToWidth(streetNameRaw, 8, false, availableDescWidth);

  strokeRect(doc, cur, y, wLog, h);
  drawTextInRect(doc, streetName, cur, y, wLog, h, 8, false, true);
  cur += wLog;
  const nText = (house.number || "").trim() === "" ? "—" : String(house.number);
  strokeRect(doc, cur, y, wN, h);
  drawCenteredText(doc, nText, cur, y, wN, h, 8, false);
  cur += wN;
  const seqText = (house.sequence ?? 0) === 0 ? "—" : String(house.sequence);
  strokeRect(doc, cur, y, wSeq, h);
  drawCenteredText(doc, seqText, cur, y, wSeq, h, 8, false);
  cur += wSeq;
  const compText = (house.complement ?? 0) === 0 ? "—" : String(house.complement);
  strokeRect(doc, cur, y, wComp, h);
  drawCenteredText(doc, compText, cur, y, wComp, h, 8, false);
  cur += wComp;
  strokeRect(doc, cur, y, wTipo, h);
  drawCenteredText(doc, propertyTypeCode(house.propertyType), cur, y, wTipo, h, 8, false);
  cur += wTipo;
  const pendText = situationCode(house.situation);
  strokeRect(doc, cur, y, wPend, h);
  drawCenteredText(doc, pendText, cur, y, wPend, h, 8, false);
}

function drawEmptyRow(doc: jsPDF, x: number, y: number, wLog: number, wN: number, wSeq: number, wComp: number, wTipo: number, wPend: number, h: number): void {
  let cur = x;
  strokeRect(doc, cur, y, wLog, h);
  cur += wLog;
  strokeRect(doc, cur, y, wN, h);
  cur += wN;
  strokeRect(doc, cur, y, wSeq, h);
  cur += wSeq;
  strokeRect(doc, cur, y, wComp, h);
  cur += wComp;
  strokeRect(doc, cur, y, wTipo, h);
  cur += wTipo;
  strokeRect(doc, cur, y, wPend, h);
}

function drawFooterCell(doc: jsPDF, label: string, code: string, value: string, x: number, y: number, w1: number, w2: number, w3: number, h: number): void {
  strokeRect(doc, x, y, w1, h);
  drawTextInRect(doc, label, x, y, w1, h, 8, false, true);
  strokeRect(doc, x + w1, y, w2, h);
  drawCenteredText(doc, code, x + w1, y, w2, h, 8, false);
  strokeRect(doc, x + w1 + w2, y, w3, h);
  drawCenteredText(doc, value, x + w1 + w2, y, w3, h, 8, false);
}

export async function drawRgPage(
  doc: jsPDF,
  pageHouses: HouseDoc[],
  bairro: string,
  block: string,
  municipio: string,
  participatingAgents?: string[],
  pageNumber?: number,
  totalPages?: number
): Promise<void> {
  participatingAgents = participatingAgents ?? [];
  pageNumber = pageNumber ?? 1;
  totalPages = totalPages ?? Math.ceil(pageHouses.length / 52);
  let cursorY = MARGIN;

  const totalWidth = PAGE_WIDTH - 2 * MARGIN;
  const colGap = 10;
  const listWidth = (totalWidth - colGap) / 2;

  const cmN = 35;
  const cmSeq = 25;
  const cmComp = 25;
  const cmTipo = 30;
  const cmPend = 25;
  const cmLog = listWidth - cmN - cmSeq - cmComp - cmTipo - cmPend;

  const alignX = MARGIN + cmLog + cmN + cmSeq + cmComp;

  const tableX = alignX;
  const tableWidth = PAGE_WIDTH - MARGIN - tableX;
  const tableY = MARGIN;
  const rowH = 25;
  const responsavelRowH = 16;

  const headerAreaWidth = tableX - MARGIN - 10;

  const headerBottomLogo = await drawHeaderSection(doc, headerAreaWidth, cursorY, cursorY);

  const tableBottomY = drawResponsavelTable(doc, tableX, tableY, tableWidth, responsavelRowH, rowH);

  const headerBottom = Math.max(headerBottomLogo, tableBottomY);

  const folhaY = headerBottom;
  drawCenteredText(doc, `FOLHA ${pageNumber}/${totalPages}`, MARGIN, folhaY, PAGE_WIDTH - 2 * MARGIN, 10, 8, false);

  cursorY = folhaY + 12;

  const rowHeight = 20;

  const categoriaSafe = pageHouses[0]?.categoria || "BRR";
  drawLocationHeaderTable(doc, cursorY, municipio, bairro, block, rowHeight, categoriaSafe);

  cursorY += 2 * rowHeight + 10;

  const cwN = 35;
  const cwSeq = 25;
  const cwComp = 25;
  const cwTipo = 30;
  const cwPend = 25;
  const cwLog = listWidth - cwN - cwSeq - cwComp - cwTipo - cwPend;

  const listHeaderHeight = 60;
  const itemHeight = 15;

  const leftX = MARGIN;
  const rightX = MARGIN + listWidth + colGap;

  drawListHeader(doc, leftX, cursorY, cwLog, cwN, cwSeq, cwComp, cwTipo, cwPend, listHeaderHeight);
  drawListHeader(doc, rightX, cursorY, cwLog, cwN, cwSeq, cwComp, cwTipo, cwPend, listHeaderHeight);

  let listY = cursorY + listHeaderHeight;

  const maxRows = 26;
  const housesLeft = pageHouses.slice(0, Math.min(pageHouses.length, maxRows));
  const housesRight = pageHouses.length > maxRows ? pageHouses.slice(maxRows) : [];

  for (const house of housesLeft) {
    drawHouseRow(doc, house, leftX, listY, cwLog, cwN, cwSeq, cwComp, cwTipo, cwPend, itemHeight);
    listY += itemHeight;
  }

  let rightListY = cursorY + listHeaderHeight;
  for (const house of housesRight) {
    drawHouseRow(doc, house, rightX, rightListY, cwLog, cwN, cwSeq, cwComp, cwTipo, cwPend, itemHeight);
    rightListY += itemHeight;
  }

  const filledLeft = housesLeft.length;
  for (let k = filledLeft; k < maxRows; k++) {
    drawEmptyRow(doc, leftX, listY, cwLog, cwN, cwSeq, cwComp, cwTipo, cwPend, itemHeight);
    listY += itemHeight;
  }
  const filledRight = housesRight.length;
  for (let k = filledRight; k < maxRows; k++) {
    drawEmptyRow(doc, rightX, rightListY, cwLog, cwN, cwSeq, cwComp, cwTipo, cwPend, itemHeight);
    rightListY += itemHeight;
  }

  const footerY = cursorY + listHeaderHeight + maxRows * itemHeight + 10;

  const totalResidencial = pageHouses.filter((h) => h.propertyType === "R" && (h.situation === "NONE" || h.situation === "EMPTY")).length;
  const totalComercial = pageHouses.filter((h) => h.propertyType === "C" && (h.situation === "NONE" || h.situation === "EMPTY")).length;
  const totalTerreno = pageHouses.filter((h) => h.propertyType === "TB" && (h.situation === "NONE" || h.situation === "EMPTY")).length;
  const totalPonto = pageHouses.filter((h) => h.propertyType === "PE" && (h.situation === "NONE" || h.situation === "EMPTY")).length;
  const totalOutros = pageHouses.filter((h) => h.propertyType === "O" && (h.situation === "NONE" || h.situation === "EMPTY")).length;
  const totalPendentes = pageHouses.filter((h) => {
    const code = situationCode(h.situation);
    return code !== "—" && code !== "";
  }).length;
  const totalGeral = pageHouses.length;

  const gap = 0;
  const totalTableWidth = PAGE_WIDTH - 2 * MARGIN;
  const blockWidth = (totalTableWidth - gap) / 2;

  const fw2 = 35;
  const fw3 = 80;
  const fw1 = blockWidth - fw2 - fw3;
  const midX = MARGIN + blockWidth + gap;

  const fh = 15;
  drawRectBox(doc, MARGIN, footerY, PAGE_WIDTH - 2 * MARGIN, fh, "FECHAMENTO", 8, true, null);

  let fy = footerY + fh;

  drawFooterCell(doc, "Residencial", "R", String(totalResidencial), MARGIN, fy, fw1, fw2, fw3, fh);
  drawFooterCell(doc, "Ponto Estratégico", "PE", String(totalPonto), midX, fy, fw1, fw2, fw3, fh);

  fy += fh;
  drawFooterCell(doc, "Comercial", "C", String(totalComercial), MARGIN, fy, fw1, fw2, fw3, fh);
  drawFooterCell(doc, "Outros", "O", String(totalOutros), midX, fy, fw1, fw2, fw3, fh);

  fy += fh;
  drawFooterCell(doc, "Terreno Baldio", "TB", String(totalTerreno), MARGIN, fy, fw1, fw2, fw3, fh);
  strokeRect(doc, midX, fy, fw1 + fw2, fh);
  drawTextInRect(doc, "Total Geral", midX, fy, fw1 + fw2, fh, 8, false, true);
  strokeRect(doc, midX + fw1 + fw2, fy, fw3, fh);
  drawCenteredText(doc, String(totalGeral), midX + fw1 + fw2, fy, fw3, fh, 8, true);

  fy += fh;
  strokeRect(doc, MARGIN, fy, fw1, fh);
  drawTextInRect(doc, "Pendentes", MARGIN, fy, fw1, fh, 8, false, true);
  strokeRect(doc, MARGIN + fw1, fy, fw2, fh);
  drawCenteredText(doc, "P", MARGIN + fw1, fy, fw2, fh, 8, false);
  const startValueX = MARGIN + fw1 + fw2;
  const fullValueWidth = PAGE_WIDTH - MARGIN - startValueX;
  strokeRect(doc, startValueX, fy, fullValueWidth, fh);
  drawCenteredText(doc, String(totalPendentes), startValueX, fy, fw3, fh, 8, false);

  fy += fh + 12;

  const rawAgentName =
    participatingAgents.length > 0
      ? participatingAgents.join(" / ").toUpperCase()
      : [...new Set(pageHouses.map((h) => h.agentName || "").filter((n) => n.trim() !== ""))].join(" / ").toUpperCase();

  const sigH = 20;
  const totalSigWidth = PAGE_WIDTH - 2 * MARGIN;
  const maxSigNameWidth = totalSigWidth - (doc.getTextWidth("ASSINATURA:") + 15);
  const agentNameDisplay = fitToWidth(rawAgentName, 8, false, maxSigNameWidth);
  const dateValue = pageHouses[pageHouses.length - 1]?.data ?? "";

  strokeRect(doc, MARGIN, fy, totalSigWidth, sigH);
  const nomeLabel = "NOME:";
  const nomeLabelWidth = doc.getTextWidth(nomeLabel);
  drawTextInRect(doc, nomeLabel, MARGIN, fy, nomeLabelWidth + 10, sigH, 8, false, true);
  drawTextInRect(doc, agentNameDisplay, MARGIN + nomeLabelWidth + 5, fy, totalSigWidth - nomeLabelWidth - 5, sigH, 8, false, true);

  fy += sigH;

  const dateColWidth = totalSigWidth * 0.25;
  const sigColWidth = totalSigWidth - dateColWidth;
  strokeRect(doc, MARGIN, fy, sigColWidth, sigH);
  const sigLabel = "ASSINATURA:";
  const sigLabelWidth = doc.getTextWidth(sigLabel);
  drawTextInRect(doc, sigLabel, MARGIN, fy, sigLabelWidth + 10, sigH, 8, false, true);
  // assinatura em branco para coleta manuscrita

  const dataX = MARGIN + sigColWidth;
  strokeRect(doc, dataX, fy, dateColWidth, sigH);
  const dataLabel = "DATA";
  const dataLabelWidth = doc.getTextWidth(dataLabel);
  drawTextInRect(doc, dataLabel, dataX, fy, dataLabelWidth + 10, sigH, 8, false, true);
  drawTextInRect(doc, dateValue, dataX + dataLabelWidth + 15, fy, dateColWidth - dataLabelWidth - 15, sigH, 8, false, true);
}

export async function generateRgPdf(
  houses: HouseDoc[],
  bairro: string,
  block: string,
  municipio = "Bom Jardim"
): Promise<jsPDF> {
  const housesPerPage = 52;
  const totalPages = houses.length === 0 ? 1 : Math.ceil(houses.length / housesPerPage);
  const safeBairro = bairro.trim().toUpperCase();

  const doc = new jsPDF({ orientation: "portrait", unit: "pt", format: [PAGE_WIDTH, PAGE_HEIGHT], compress: true });
  setLine(doc);

  for (let i = 0; i < totalPages; i++) {
    if (i > 0) doc.addPage([PAGE_WIDTH, PAGE_HEIGHT], "portrait");
    const startIndex = i * housesPerPage;
    const endIndex = Math.min(startIndex + housesPerPage, houses.length);
    const pageHouses = houses.slice(startIndex, endIndex);
    await drawRgPage(doc, pageHouses, safeBairro, block, municipio);
  }
  return doc;
}

export async function downloadRg(
  houses: HouseDoc[],
  bairro: string,
  block: string,
  municipio = "Bom Jardim"
): Promise<void> {
  if (!houses.length) throw new Error("Nenhum imóvel para gerar RG");
  const doc = await generateRgPdf(houses, bairro, block, municipio);
  const safeBairro = bairro.trim().toUpperCase().replace(/\s+/g, "_").replace(/[^A-Z0-9_-]/g, "_");
  const safeBlock = block.replace(/[^a-zA-Z0-9_-]/g, "_");
  const blob = doc.output("blob");
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `RG_${safeBairro}_Q${safeBlock}.pdf`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}