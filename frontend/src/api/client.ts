import type {
  ApiErrorBody,
  HandDetailDto,
  HandListQuery,
  HandListResponse,
  ImportResponse,
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
  return response.json() as Promise<T>;
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
  params.set("page", String(query.page ?? 1));
  params.set("pageSize", String(query.pageSize ?? 50));
  if (query.sortDir) params.set("sortDir", query.sortDir);
  return request<HandListResponse>(`/api/v1/hands?${params.toString()}`);
}

export function fetchHandDetail(handId: number): Promise<HandDetailDto> {
  return request<HandDetailDto>(`/api/v1/hands/${handId}`);
}
