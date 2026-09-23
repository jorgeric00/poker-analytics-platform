import type {
  ApiErrorBody,
  HandDetailDto,
  HandListQuery,
  HandListResponse,
  ImportResponse,
  LiveSessionStatusDto,
  PlayerDetailDto,
  PlayerListQuery,
  PlayerListResponse,
  StatsQueryRequest,
  StatsQueryResponse,
  SystemMetaResponse,
} from "./types";

/** Thrown for a non-2xx response, carrying docs/10-api-design.md's error envelope. */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly body: ApiErrorBody,
  ) {
    super(body.message);
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: { "Content-Type": "application/json", ...init?.headers },
  });
  if (!response.ok) {
    const errorBody = (await response.json().catch(() => ({
      code: "UNKNOWN",
      message: response.statusText,
    }))) as { error?: ApiErrorBody };
    throw new ApiError(response.status, errorBody.error ?? { code: "UNKNOWN", message: response.statusText });
  }
  // PUT/POST/DELETE endpoints returning `void` server-side (notes/tags) send an empty body —
  // response.json() would throw on that, so treat "nothing to parse" as a valid `undefined`.
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

// Frontend has no business logic of its own (docs/04-architecture.md) — these are thin wrappers
// around docs/10-api-design.md's endpoints, one per implemented endpoint, nothing more.

export function triggerImport(filePaths: string[]): Promise<ImportResponse> {
  return request<ImportResponse>("/api/v1/imports", {
    method: "POST",
    body: JSON.stringify({ filePaths }),
  });
}

export function fetchSystemMeta(): Promise<SystemMetaResponse> {
  return request<SystemMetaResponse>("/api/v1/system/meta");
}

export function queryStats(query: StatsQueryRequest): Promise<StatsQueryResponse> {
  return request<StatsQueryResponse>("/api/v1/stats/query", {
    method: "POST",
    body: JSON.stringify(query),
  });
}

export function fetchHands(query: HandListQuery): Promise<HandListResponse> {
  const params = new URLSearchParams();
  if (query.room) params.set("room", query.room);
  if (query.wonLost) params.set("wonLost", query.wonLost);
  if (query.position?.length) params.set("position", query.position.join(","));
  if (query.handType?.length) params.set("handType", query.handType.join(","));
  if (query.endedOnStreet?.length) params.set("endedOnStreet", query.endedOnStreet.join(","));
  if (query.showdownOnly != null) params.set("showdownOnly", String(query.showdownOnly));
  if (query.opponentPlayerId != null) params.set("opponentPlayerId", String(query.opponentPlayerId));
  params.set("page", String(query.page ?? 1));
  params.set("pageSize", String(query.pageSize ?? 50));
  if (query.sortDir) params.set("sortDir", query.sortDir);
  return request<HandListResponse>(`/api/v1/hands?${params.toString()}`);
}

export function fetchHandDetail(handId: number): Promise<HandDetailDto> {
  return request<HandDetailDto>(`/api/v1/hands/${handId}`);
}

export function fetchPlayers(query: PlayerListQuery): Promise<PlayerListResponse> {
  const params = new URLSearchParams();
  if (query.search) params.set("search", query.search);
  if (query.minHands != null) params.set("minHands", String(query.minHands));
  params.set("page", String(query.page ?? 1));
  params.set("pageSize", String(query.pageSize ?? 50));
  return request<PlayerListResponse>(`/api/v1/players?${params.toString()}`);
}

export function fetchPlayerDetail(playerId: number): Promise<PlayerDetailDto> {
  return request<PlayerDetailDto>(`/api/v1/players/${playerId}`);
}

export function savePlayerNotes(playerId: number, freeText: string): Promise<void> {
  return request<void>(`/api/v1/players/${playerId}/notes`, {
    method: "PUT",
    body: JSON.stringify({ freeText }),
  });
}

export function addPlayerTag(playerId: number, tag: string): Promise<void> {
  return request<void>(`/api/v1/players/${playerId}/tags`, {
    method: "POST",
    body: JSON.stringify({ tag }),
  });
}

export function removePlayerTag(playerId: number, tag: string): Promise<void> {
  return request<void>(`/api/v1/players/${playerId}/tags/${encodeURIComponent(tag)}`, {
    method: "DELETE",
  });
}

export function fetchLiveSessionStatus(): Promise<LiveSessionStatusDto> {
  return request<LiveSessionStatusDto>("/api/v1/live-session");
}

export function startLiveSession(): Promise<LiveSessionStatusDto> {
  return request<LiveSessionStatusDto>("/api/v1/live-session/start", { method: "POST" });
}

export function stopLiveSession(): Promise<LiveSessionStatusDto> {
  return request<LiveSessionStatusDto>("/api/v1/live-session/stop", { method: "POST" });
}
