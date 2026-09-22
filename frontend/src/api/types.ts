// Mirrors backend/src/main/java/com/pokeranalytics/api/dto — docs/10-api-design.md.
// Field names match the Java records as-is (Jackson's default naming), so these types are kept
// as close to 1:1 translations of those DTOs as TypeScript allows, not reshaped for convenience.

export interface FileImportResult {
  filePath: string;
  handsImported: number;
  handsSkipped: number;
  handsFailed: number;
  unparsedLineCount: number;
  status: string;
}

export interface ImportResponse {
  status: string;
  results: FileImportResult[];
}

export interface SystemMetaResponse {
  databaseSizeBytes: number;
  lastImportAt: string | null;
  lastImportRoom: string | null;
}

export interface ScopeDto {
  type: "HERO";
  playerId?: number | null;
}

export interface StatsQueryRequest {
  scope: ScopeDto;
  statSet: string[];
  breakdown: string[];
  minSampleSize?: number;
}

export interface StatRowDto {
  breakdownKey: Record<string, string> | null;
  sampleSize: number;
  values: Record<string, number>;
}

export interface WarningDto {
  stat: string;
  breakdownKey: Record<string, string> | null;
  reason: string;
}

export interface StatsQueryResponse {
  rows: StatRowDto[];
  warnings: WarningDto[];
}

export interface ApiErrorBody {
  code: string;
  message: string;
  details?: Record<string, unknown>;
}

export interface MoneyDto {
  amount: string;
  currency: string;
}

export interface StakesDto {
  smallBlind: MoneyDto | null;
  bigBlind: MoneyDto | null;
}

export interface HandListItemDto {
  handId: number;
  room: string;
  format: string;
  handTimestamp: string | null;
  handTimestampRaw: string;
  stakes: StakesDto;
  heroPosition: string;
  heroHoleCards: string[] | null;
  playerCount: number;
  handType: string;
  endedOnStreet: string;
  pot: MoneyDto | null;
  result: string | null;
  amount: MoneyDto | null;
  tags: string[];
}

export interface HandListResponse {
  items: HandListItemDto[];
  total: number;
  page: number;
  pageSize: number;
}

export interface HandListQuery {
  room?: string;
  wonLost?: string;
  position?: string[];
  handType?: string[];
  endedOnStreet?: string[];
  showdownOnly?: boolean;
  page?: number;
  pageSize?: number;
  sortDir?: string;
}

export interface StakesDtoWithAnte extends StakesDto {
  ante: MoneyDto | null;
}

export interface HandSeatDto {
  seatNumber: number;
  nickname: string | null;
  isHero: boolean;
  startingStack: MoneyDto | null;
  position: string;
  holeCards: string[] | null;
}

export interface HandActionDto {
  sequenceNo: number;
  street: string;
  nickname: string | null;
  isHero: boolean;
  actionType: string;
  amount: MoneyDto | null;
}

export interface HandBoardDto {
  runIndex: number;
  street: string;
  cards: string;
}

export interface HandResultDto {
  nickname: string | null;
  isHero: boolean;
  wentToShowdown: boolean;
  amountWon: MoneyDto | null;
  netResult: MoneyDto | null;
  outcomeRaw: string | null;
}

export interface HandDetailDto {
  handId: number;
  externalHandId: string;
  room: string;
  format: string;
  gameTypeRaw: string;
  handTimestampRaw: string;
  stakes: StakesDtoWithAnte;
  tableName: string | null;
  maxSeats: number;
  buttonSeat: number;
  runItTwice: boolean;
  totalPot: MoneyDto | null;
  rake: MoneyDto | null;
  handType: string;
  endedOnStreet: string;
  seats: HandSeatDto[];
  actions: HandActionDto[];
  boards: HandBoardDto[];
  results: HandResultDto[];
  previousHandId: number | null;
  nextHandId: number | null;
}
