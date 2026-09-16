import { jsPDF } from "jspdf";
import { CHOCO_TTF_BASE64 } from "./assets";
import { CHOCO_METRICS } from "./fonts/chocoMetrics";

// ============================================================
// Primitivas de desenho compartilhadas (PdfComponents.kt /
// PdfTableBuilder.kt / StringExtensions.kt do app Android).
// Port 1:1 para jsPDF. Fonte Chococooky = Typeface.DEFAULT do device.
// ============================================================

export const FONT = "Chococooky";
const FONT_FILE = "Chococooky.ttf";
export const FONT_ASCENT = 0.76514;
export const FONT_DESCENT = 0.24023;
export const LINE_W = 0.5;
export const HEADER_BG: [number, number, number] = [192, 192, 192];
export const LIGHT_BG: [number, number, number] = [224, 224, 224];

// Fake-bold do device (Skia): não altera avanços, só traça o contorno.
// Espessura calibrada contra os PDFs reais do app.
const BOLD_STROKE_W = 0.22;

let currentLineW = LINE_W;
const registeredDocs = new WeakSet<object>();

export function ensureFont(doc: jsPDF): void {
  if (registeredDocs.has(doc)) return;
  doc.addFileToVFS(FONT_FILE, CHOCO_TTF_BASE64);
  doc.addFont(FONT_FILE, FONT, "normal");
  doc.addFont(FONT_FILE, FONT, "bold");
  registeredDocs.add(doc);
}

// ------------------------------------------------------------
// Métricas de texto (equivalente a getTextBounds do Android,
// usando as métricas reais da Chococooky)
// ------------------------------------------------------------

export interface Bounds {
  w: number;
  h: number;
  bottom: number;
}

const boundsCache = new Map<string, Bounds>();
const BOUNDS_CACHE_LIMIT = 800;
// Expansão do fake-bold sobre os avanços (em/em por caractere) — calibrada
const BOLD_EXPAND = 0; // fake-bold do device NÃO altera avanços (confirmado contra PDFs reais)

export function tightBounds(text: string, size: number, bold: boolean): Bounds {
  const key = `${text}|${size}|${bold ? 1 : 0}`;
  const cached = boundsCache.get(key);
  if (cached) return cached;

  let adv = 0;
  let ymin = Number.POSITIVE_INFINITY;
  let ymax = Number.NEGATIVE_INFINITY;
  for (const ch of text) {
    const m = CHOCO_METRICS[ch] ?? CHOCO_METRICS["?"];
    if (!m) continue;
    adv += m[0];
    if (m[1] < ymin) ymin = m[1];
    if (m[2] > ymax) ymax = m[2];
  }
  if (!Number.isFinite(ymin)) {
    ymin = 0;
    ymax = FONT_ASCENT;
  }
  if (bold) adv += BOLD_EXPAND * Math.max(text.length - 0, 0);

  const b: Bounds = {
    w: adv * size,
    h: (ymax - ymin) * size,
    bottom: -ymin * size,
  };
  if (boundsCache.size >= BOUNDS_CACHE_LIMIT) {
    const firstKey = boundsCache.keys().next().value;
    if (firstKey) boundsCache.delete(firstKey);
  }
  boundsCache.set(key, b);
  return b;
}

export function textWidth(text: string, size: number, bold: boolean): number {
  return tightBounds(text, size, bold).w;
}

// ------------------------------------------------------------
// Primitivas de desenho
// ------------------------------------------------------------

export function setText(doc: jsPDF, size: number, bold: boolean): void {
  ensureFont(doc);
  doc.setFont(FONT, bold ? "bold" : "normal");
  doc.setFontSize(size);
}

export function setLine(doc: jsPDF, w = LINE_W): void {
  currentLineW = w;
  doc.setDrawColor(0, 0, 0);
  doc.setLineWidth(w);
}

/** doc.text com fake-bold sintético (contorno) quando estilo = bold */
export function pdfText(doc: jsPDF, text: string, x: number, y: number, options?: Record<string, unknown>): void {
  const style = (doc.getFont() as unknown as [string, string])[1];
  if (style === "bold") {
    doc.setDrawColor(0, 0, 0);
    doc.setLineWidth(BOLD_STROKE_W);
    doc.text(text, x, y, { ...(options ?? {}), renderingMode: "fillThenStroke" } as never);
    doc.setLineWidth(currentLineW);
  } else {
    doc.text(text, x, y, options as never);
  }
}

// Pad esquerdo de células alinhadas à esquerda — difere por gerador no app:
// PdfComponents (Boletim) = 2 · RGPdfGenerator local = 4 · Semanal (Metrics) = 0
export let TEXT_LEFT_PAD = 2;
export function setTextLeftPad(v: number): void {
  TEXT_LEFT_PAD = v;
}

// Modo vertical do texto — Boletim/RG usam Bounds; Semanal usa Metrics (Paint.ascent/descent)
export type TextMode = "bounds" | "metrics";
let textMode: TextMode = "bounds";
export function setTextMode(m: TextMode): void {
  textMode = m;
}

function cellBaselineY(y: number, h: number, size: number, b: Bounds): number {
  if (textMode === "metrics") return metricsBaselineY(y, h, size);
  return y + (h + b.h) / 2 - b.bottom;
}

// Centro vertical pela métrica da fonte (Paint.ascent/descent do Android):
// textY = y + h/2 − (descent + ascent)/2, com ascent negativo
function metricsBaselineY(y: number, h: number, size: number): number {
  return y + h / 2 - (size * (FONT_DESCENT - FONT_ASCENT)) / 2;
}

export function fillRect(doc: jsPDF, x: number, y: number, w: number, h: number, c: [number, number, number]): void {
  doc.setFillColor(...c);
  doc.rect(x, y, w, h, "F");
}

export function strokeRect(doc: jsPDF, x: number, y: number, w: number, h: number): void {
  setLine(doc);
  doc.rect(x, y, w, h, "D");
}

export function drawCenteredText(doc: jsPDF, text: string, x: number, y: number, w: number, h: number, size: number, bold: boolean): void {
  if (!text) return;
  setText(doc, size, bold);
  const tw = doc.getTextWidth(text);
  const b = tightBounds(text, size, bold);
  pdfText(doc,text, x + (w - tw) / 2, cellBaselineY(y, h, size, b));
}

export function drawTextInRect(doc: jsPDF, text: string, x: number, y: number, w: number, h: number, size: number, bold: boolean, alignLeft = false): void {
  if (!text) return;
  if (alignLeft) {
    const b = tightBounds(text, size, bold);
    setText(doc, size, bold);
    pdfText(doc,text, x + TEXT_LEFT_PAD, cellBaselineY(y, h, size, b));
  } else {
    drawCenteredText(doc, text, x, y, w, h, size, bold);
  }
}

export function drawRectBox(
  doc: jsPDF,
  x: number,
  y: number,
  w: number,
  h: number,
  text: string,
  size: number,
  bold: boolean,
  bg?: [number, number, number] | null,
  alignLeft = false
): void {
  if (bg) fillRect(doc, x, y, w, h, bg);
  strokeRect(doc, x, y, w, h);
  drawTextInRect(doc, text, x, y, w, h, size, bold, alignLeft);
}

export function drawCell(doc: jsPDF, text: string, x: number, y: number, w: number, h: number, size: number, bold: boolean, alignLeft = false): void {
  strokeRect(doc, x, y, w, h);
  if (alignLeft) {
    const b = tightBounds(text, size, bold);
    setText(doc, size, bold);
    pdfText(doc,text, x + TEXT_LEFT_PAD, cellBaselineY(y, h, size, b));
  } else {
    drawCenteredText(doc, text, x, y, w, h, size, bold);
  }
}

export function drawHeaderBox(
  doc: jsPDF,
  x: number,
  y: number,
  w: number,
  h: number,
  label: string,
  value: string,
  labelSize: number,
  valueSize: number
): void {
  const stripH = h > 15 ? 12 : h / 2;
  fillRect(doc, x, y, w, stripH, HEADER_BG);
  strokeRect(doc, x, y, w, h);
  setLine(doc);
  doc.line(x, y + stripH, x + w, y + stripH);
  drawCenteredText(doc, label, x, y, w, stripH, labelSize, false);
  drawCenteredText(doc, value, x, y + stripH, w, h - stripH, valueSize, true);
}

export function drawVerticalHeader(
  doc: jsPDF,
  label: string,
  x: number,
  y: number,
  w: number,
  h: number,
  size: number,
  bold: boolean,
  bg?: [number, number, number] | null
): void {
  if (bg) fillRect(doc, x, y, w, h, bg);
  strokeRect(doc, x, y, w, h);

  const cx = x + w / 2;
  const cy = y + h / 2;
  const rotX = cx - h / 2;
  const rotY = cy - w / 2;
  const rotW = h;
  const rotH = w;

  const lines = label.split("\n");

  // Modo Metrics (Semanal): espelha drawVerticalTextInBox (1 linha, rotacionado,
  // baseline = centerY + textSize/3) e drawVerticalHeaderWithMetrics (multi-linha
  // horizontal, 7f fixo, lineHeight = size+2, bloco centrado)
  if (textMode === "metrics") {
    if (lines.length === 1) {
      setText(doc, size, bold);
      const b = tightBounds(label, size, bold);
      const tx = rotX + (rotW - b.w) / 2;
      const ty = rotY + rotH / 2 + size / 3;
      const ax = cx + (ty - cy);
      const ay = cy - (tx - cx);
      pdfText(doc,label, ax, ay, { angle: 90 });
    } else {
      const fs = 7;
      const lh = fs + 2;
      const total = lines.length * lh;
      let curY = y + (h - total) / 2 + fs;
      setText(doc, fs, bold);
      for (const line of lines) {
        const lw = textWidth(line, fs, bold);
        pdfText(doc,line, x + w / 2 - lw / 2, curY);
        curY += lh;
      }
    }
    return;
  }

  if (lines.length === 1) {
    const b = tightBounds(label, size, bold);
    const tx = rotX + (rotW - b.w) / 2;
    const ty = rotY + (rotH + b.h) / 2 - b.bottom;
    const ax = cx + (ty - cy);
    const ay = cy - (tx - cx);
    setText(doc, size, bold);
    pdfText(doc,label, ax, ay, { angle: 90 });
  } else {
    const lineHeight = (FONT_ASCENT + FONT_DESCENT) * size;
    const totalTextH = lines.length * lineHeight;
    const blockTop = rotY + (rotH - totalTextH) / 2;
    lines.forEach((line, i) => {
      const b = tightBounds(line, size, bold);
      const tx = rotX + (rotW - b.w) / 2;
      const ty = blockTop + i * lineHeight + FONT_ASCENT * size;
      const ax = cx + (ty - cy);
      const ay = cy - (tx - cx);
      setText(doc, size, bold);
      pdfText(doc,line, ax, ay, { angle: 90 });
    });
  }
}

export function drawTextInBox(doc: jsPDF, text: string, x: number, y: number, w: number, h: number, size: number, bold: boolean, alignLeft = false): void {
  if (alignLeft) {
    const b = tightBounds("A", size, bold);
    setText(doc, size, bold);
    const fitted = fitToWidth(text, size, bold, w - 4);
    pdfText(doc,fitted, x + TEXT_LEFT_PAD, cellBaselineY(y, h, size, b));
  } else {
    const fitted = fitToWidth(text, size, bold, w - 4);
    drawCenteredText(doc, fitted, x, y, w, h, size, bold);
  }
}

export function drawBlockCell(doc: jsPDF, blockNum: string | null, blockSeq: string | null, x: number, y: number, w: number, h: number, size: number): void {
  strokeRect(doc, x, y, w, h);
  const sep = " / ";
  setText(doc, size, true);
  const sepFullWidth = doc.getTextWidth(sep);
  const centerX = x + w / 2;
  const sb = tightBounds(sep, size, true);
  const centerY = y + (h + sb.h) / 2 - sb.bottom;
  const sepX = centerX - sepFullWidth / 2;
  pdfText(doc,sep, sepX, centerY);
  if (blockNum !== null && blockNum !== undefined && blockNum !== "") {
    const bw = doc.getTextWidth(blockNum);
    pdfText(doc,blockNum, sepX - bw - 2, centerY);
  }
  if (blockSeq !== null && blockSeq !== undefined && blockSeq !== "") {
    pdfText(doc,blockSeq, sepX + sepFullWidth + 2, centerY);
  }
}

export interface RowOpts {
  alignLefts: boolean[];
  bolds: boolean[];
  size: number;
  bg?: [number, number, number] | null;
}

export function drawRow(doc: jsPDF, x: number, y: number, h: number, widths: number[], values: string[], opts: RowOpts): void {
  let cx = x;
  for (let i = 0; i < widths.length; i++) {
    if (opts.bg) fillRect(doc, cx, y, widths[i], h, opts.bg);
    strokeRect(doc, cx, y, widths[i], h);
    const v = values[i] ?? "";
    if (v !== "") {
      const alignLeft = opts.alignLefts[i] ?? false;
      const bold = opts.bolds[i] ?? false;
      if (alignLeft) {
        const b = tightBounds(v, opts.size, bold);
        setText(doc, opts.size, bold);
        pdfText(doc,v, cx + TEXT_LEFT_PAD, cellBaselineY(y, h, opts.size, b));
      } else {
        drawCenteredText(doc, v, cx, y, widths[i], h, opts.size, bold);
      }
    }
    cx += widths[i];
  }
}

// ------------------------------------------------------------
// Helpers de string (StringExtensions.kt)
// ------------------------------------------------------------

const LOWER_CASE_PREPOSITIONS = new Set(["de", "da", "do", "das", "dos", "e", "di"]);

export function formatStreetName(s: string): string {
  if (s.trim() === "") return s;
  return s
    .toLowerCase()
    .split(" ")
    .filter((word) => word !== "")
    .map((word) =>
      word
        .split("-")
        .map((part) => {
          if (LOWER_CASE_PREPOSITIONS.has(part)) return part;
          if (part.length === 2) return part.toUpperCase();
          return part.charAt(0).toUpperCase() + part.slice(1);
        })
        .join("-")
    )
    .join(" ");
}

const PREFIX_ABBR: Array<[string, string]> = [
  ["Avenida", "Av."], ["Rua", "R."], ["Doutor", "Dr."], ["Professor", "Prof."], ["Engenheiro", "Eng."],
  ["Alameda", "Al."], ["Estrada", "Est."], ["Rodovia", "Rod."], ["Travessa", "Tv."], ["Praça", "Pç."],
  ["Coronel", "Cel."], ["General", "Gen."], ["Major", "Maj."], ["Capitão", "Cap."], ["Tenente", "Ten."],
  ["Sargento", "Sgt."],
];

function abbreviateStreetPrefixes(s: string): string {
  let result = s;
  for (const [full, abbr] of PREFIX_ABBR) {
    const escaped = full.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    result = result.replace(new RegExp(`(^|[^\\p{L}])${escaped}(?=[^\\p{L}]|$)`, "giu"), `$1${abbr}`);
  }
  return result;
}

function abbreviateMiddleNames(s: string): string {
  const words = s.split(" ").filter((w) => w !== "");
  if (words.length <= 2) return s;
  const prefixes = new Set([
    "Av.", "R.", "Dr.", "Prof.", "Eng.", "Al.", "Est.", "Rod.", "Tv.", "Pç.",
    "Cel.", "Gen.", "Maj.", "Cap.", "Ten.", "Sgt.",
    "Avenida", "Rua", "Doutor", "Professor", "Engenheiro", "Alameda", "Estrada",
    "Rodovia", "Travessa", "Praça", "Coronel", "General", "Major", "Capitão",
    "Tenente", "Sargento",
  ]);
  const firstWord = words[0];
  const isPrefix = prefixes.has(firstWord) || firstWord.endsWith(".");
  let startIndex = 0;
  if (isPrefix && words.length > 2) startIndex = 1;
  const firstName = words[startIndex];
  const lastName = words[words.length - 1];
  if (startIndex + 1 >= words.length - 1) {
    return startIndex > 0 ? `${words[0]} ${firstName} ${lastName}` : `${firstName} ${lastName}`;
  }
  const middle = words.slice(startIndex + 1, words.length - 1);
  const abbreviated = middle
    .map((word) => (LOWER_CASE_PREPOSITIONS.has(word.toLowerCase()) ? word : `${word.charAt(0)}.`))
    .join(" ");
  return startIndex > 0 ? `${words[0]} ${firstName} ${abbreviated} ${lastName}` : `${firstName} ${abbreviated} ${lastName}`;
}

export function fitToWidth(text: string, size: number, bold: boolean, maxWidth: number): string {
  const original = text;
  if (textWidth(original, size, bold) <= maxWidth) return original;
  const prefixAbbr = abbreviateStreetPrefixes(original);
  if (textWidth(prefixAbbr, size, bold) <= maxWidth) return prefixAbbr;
  const middleAbbr = abbreviateMiddleNames(prefixAbbr);
  if (textWidth(middleAbbr, size, bold) <= maxWidth) return middleAbbr;
  const ellipsis = "...";
  const availForText = maxWidth - textWidth(ellipsis, size, bold);
  if (availForText <= 0) {
    return maxWidth > textWidth(".", size, bold) ? "." : "";
  }
  let measuredCount = 0;
  let acc = 0;
  for (let i = 0; i < middleAbbr.length; i++) {
    acc += textWidth(middleAbbr[i], size, bold);
    if (acc > availForText) break;
    measuredCount = i + 1;
  }
  return measuredCount < middleAbbr.length ? middleAbbr.slice(0, measuredCount) + ellipsis : middleAbbr;
}

// ------------------------------------------------------------
// Dados (BoletimDataMapper.kt + Enums.kt)
// ------------------------------------------------------------

export function situationCode(s: string | undefined): string {
  if (s === "NONE" || s === "EMPTY") return "—";
  return s ?? "";
}

export function propertyTypeCode(s: string | undefined): string {
  if (s === "EMPTY") return "";
  return s ?? "";
}

export function isOpen(sit: string): boolean {
  return sit === "—";
}

export function calculateCicloFromDate(dateStr: string): string {
  const parts = dateStr.split("-");
  if (parts.length !== 3) return "1º";
  const month = Number(parts[1]) - 1;
  return ["1º", "1º", "2º", "2º", "3º", "3º", "4º", "4º", "5º", "5º", "6º", "6º"][month] ?? "1º";
}

export function calculateCicloSemanal(dateStr: string): string {
  const parts = dateStr.split("-");
  if (parts.length === 3) {
    const month = Number(parts[1]);
    return `${Math.floor((month - 1) / 2) + 1}º`;
  }
  return "";
}

export function dsh(v: number): string {
  return v === 0 ? "—" : String(v);
}

export function formatDouble(v: number): string {
  if (v % 1 === 0) return String(v);
  return new Intl.NumberFormat("pt-BR", { minimumFractionDigits: 1, maximumFractionDigits: 1 }).format(v);
}