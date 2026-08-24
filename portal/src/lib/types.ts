export interface UserDoc {
  uid: string;
  email?: string;
  role?: "ADMIN" | "SUPERVISOR" | "AGENT";
  isAuthorized?: boolean;
  displayName?: string;
  bairro?: string;
  block?: string;
}

export interface AgentDoc {
  id: string;
  agentName?: string;
  email?: string;
  lastSyncTime?: number;
  photoUrl?: string;
  isPreRegistered?: boolean;
}

export interface HouseDoc {
  id: string;
  data?: string;
  streetName?: string;
  number?: string;
  blockNumber?: string;
  blockSequence?: string;
  sequence?: number;
  complement?: number;
  visitSegment?: number;
  situation?: string;
  propertyType?: string;
  comFoco?: boolean;
  a1?: number;
  a2?: number;
  b?: number;
  c?: number;
  d1?: number;
  d2?: number;
  e?: number;
  eliminados?: number;
  larvicida?: number;
  latitude?: number | null;
  longitude?: number | null;
  lastUpdated?: unknown;
  lastSyncTime?: number;
  agentName?: string;
  agentUid?: string;
  municipio?: string;
  bairro?: string;
  categoria?: string;
  zona?: string;
  tipo?: string;
  atividade?: string;
  localidadeConcluida?: boolean;
  quarteiraoConcluido?: boolean;
  listOrder?: number;
  createdAt?: number | { seconds?: number };
}

export interface DayActivityDoc {
  id: string;
  date?: string;
  status?: string;
  isClosed?: boolean;
  isManualUnlock?: boolean;
  agentUid?: string;
  agentName?: string;
  lastUpdated?: unknown;
}

export interface MonthlySummaryDoc {
  id: string;
  monthYear?: string;
  totalHouses?: number;
  treatedCount?: number;
  focusCount?: number;
  daysWorked?: number;
  situationCounts?: Record<string, number>;
  propertyTypeCounts?: Record<string, number>;
  lastUpdated?: number;
}

export interface HouseStats {
  worked: number;
  vacant: number;
  closed: number;
  abandoned: number;
  refused: number;
  focuses: number;
  treated: number;
  visits: number;
  res: number;
  com: number;
  tb: number;
  pe: number;
  out: number;
  activeDays: number;
}