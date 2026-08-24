export const SITUATION = {
  NONE: "NONE",
  EMPTY: "EMPTY",
  CLOSED: "CLOSED",
  VACANT: "VACANT",
  REFUSED: "REFUSED",
  ABANDONED: "ABANDONED",
  L_F: "F",
  L_V: "V",
  L_REC: "REC",
  L_A: "A",
  L_NONE: "—",
} as const;

export const PROPERTY_TYPE = {
  RES: "R",
  COM: "C",
  TB: "TB",
  PE: "PE",
  OUT: "O",
  EMPTY: "EMPTY",
} as const;

export const WORKED_EXCLUDED = [
  SITUATION.VACANT,
  SITUATION.CLOSED,
  SITUATION.REFUSED,
  SITUATION.ABANDONED,
  SITUATION.L_V,
  SITUATION.L_F,
  SITUATION.L_REC,
  SITUATION.L_A,
] as const;

export const MONTHS = [
  "Ano Todo", "Jan", "Fev", "Mar", "Abr", "Mai", "Jun",
  "Jul", "Ago", "Set", "Out", "Nov", "Dez",
] as const;

export const SITUATION_LABELS: Record<string, string> = {
  NONE: "Aberto",
  EMPTY: "Aberto",
  F: "Fechado",
  CLOSED: "Fechado",
  REC: "Recusado",
  REFUSED: "Recusado",
  A: "Abandonado",
  ABANDONED: "Abandonado",
  V: "Vazio",
  VACANT: "Vazio",
};

export const PROPERTY_TYPE_LABELS: Record<string, string> = {
  R: "Residência",
  C: "Comércio",
  TB: "Terreno Baldio",
  O: "Outros",
  PE: "Ponto Estratégico",
  EMPTY: "Não Informado",
};