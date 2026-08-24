import { jsPDF } from "jspdf";

// ============================================================
// Primitivas de desenho compartilhadas (PdfComponents.kt /
// PdfTableBuilder.kt / StringExtensions.kt do app Android).
// Port 1:1 para jsPDF. Layout, cores e métricas idênticos.
// ============================================================

export const FONT = "helvetica";
export const FONT_ASCENT = 0.905;
export const FONT_DESCENT = 0.212;
export const LINE_W = 0.5;
export const HEADER_BG: [number, number, number] = [192, 192, 192];
export const LIGHT_BG: [number, number, number] = [224, 224, 224];

// ------------------------------------------------------------
// Métricas de texto (getTextBounds do Android)
// ------------------------------------------------------------

export interface Bounds {
  w: number;
  h: number;
  bottom: number;
}

let measureCtx: CanvasRenderingContext2D | null = null;
const boundsCache = new Map<string, Bounds>();
const BOUNDS_CACHE_LIMIT = 800;

export function tightBounds(text: string, size: number, bold: boolean): Bounds {
  const key = `${text}|${size}|${bold ? 1 : 0}`;
  const cached = boundsCache.get(key);
  if (cached) return cached;

  if (!measureCtx && typeof document !== "undefined") {
    measureCtx = document.createElement("canvas").getContext("2d");
  }

  let b: Bounds;
  if (measureCtx) {
    measureCtx.font = `${bold ? "700 " : ""}${size}px ${FONT}, sans-serif`;
    const m = measureCtx.measureText(text);
    const w = m.actualBoundingBoxRight + m.actualBoundingBoxLeft;
    b = {
      w: Number.isFinite(w) && w > 0 ? w : size * text.length * 0.5,
      h: m.actualBoundingBoxAscent + m.actualBoundingBoxDescent,
      bottom: m.actualBoundingBoxDescent,
    };
  } else {
    b = { w: size * text.length * 0.5, h: size * 0.76, bottom: size * 0.12 };
  }
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
  doc.setFont(FONT, bold ? "bold" : "normal");
  doc.setFontSize(size);
}

export function setLine(doc: jsPDF): void {
  doc.setDrawColor(0, 0, 0);
  doc.setLineWidth(LINE_W);
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
  doc.text(text, x + (w - tw) / 2, y + (h + b.h) / 2 - b.bottom);
}

export function drawTextInRect(doc: jsPDF, text: string, x: number, y: number, w: number, h: number, size: number, bold: boolean, alignLeft = false): void {
  if (!text) return;
  if (alignLeft) {
    const b = tightBounds(text, size, bold);
    setText(doc, size, bold);
    doc.text(text, x + 2, y + (h + b.h) / 2 - b.bottom);
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
    doc.text(text, x + 2, y + (h + b.h) / 2 - b.bottom);
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
  if (lines.length === 1) {
    const b = tightBounds(label, size, bold);
    const tx = rotX + (rotW - b.w) / 2;
    const ty = rotY + (rotH + b.h) / 2 - b.bottom;
    const ax = cx + (ty - cy);
    const ay = cy - (tx - cx);
    setText(doc, size, bold);
    doc.text(label, ax, ay, { angle: 90 });
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
      doc.text(line, ax, ay, { angle: 90 });
    });
  }
}

export function drawTextInBox(doc: jsPDF, text: string, x: number, y: number, w: number, h: number, size: number, bold: boolean, alignLeft = false): void {
  if (alignLeft) {
    const b = tightBounds("A", size, bold);
    setText(doc, size, bold);
    const fitted = fitToWidth(text, size, bold, w - 4);
    doc.text(fitted, x + 2, y + (h + b.h) / 2 - b.bottom);
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
  doc.text(sep, sepX, centerY);
  if (blockNum !== null && blockNum !== undefined && blockNum !== "") {
    const bw = doc.getTextWidth(blockNum);
    doc.text(blockNum, sepX - bw - 2, centerY);
  }
  if (blockSeq !== null && blockSeq !== undefined && blockSeq !== "") {
    doc.text(blockSeq, sepX + sepFullWidth + 2, centerY);
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
        doc.text(v, cx + 2, y + (h + b.h) / 2 - b.bottom);
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