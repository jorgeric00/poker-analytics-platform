import { useState, type FormEvent } from "react";
import { triggerImport } from "../api/client";
import type { FileImportResult } from "../api/types";

// The mockups (docs/12-ui-designs.md) show "Import hands" as its own sidebar destination, not
// folded into Overview — moved here to match, keeping the same working POST /api/v1/imports call
// docs/10-api-design.md's ImportController backs. The file-path textarea (rather than a native
// picker) is a known, tracked gap — see docs/11-development-tracking.md's Frontend follow-ups.
export function ImportPage() {
  const [filePathsInput, setFilePathsInput] = useState("");
  const [importing, setImporting] = useState(false);
  const [results, setResults] = useState<FileImportResult[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function handleImport(e: FormEvent) {
    e.preventDefault();
    const filePaths = filePathsInput
      .split("\n")
      .map((line) => line.trim())
      .filter((line) => line.length > 0);
    if (filePaths.length === 0) {
      return;
    }

    setImporting(true);
    setResults(null);
    setError(null);
    try {
      const response = await triggerImport(filePaths);
      setResults(response.results);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Import failed");
    } finally {
      setImporting(false);
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <div className="kicker">Import</div>
          <h1>Import hand histories</h1>
        </div>
      </div>

      {error && <div className="banner banner-error">{error}</div>}

      <div className="panel">
        <p className="placeholder-note" style={{ margin: 0 }}>
          Cash games only, for now — PokerStars, Winamax, PartyPoker, 888, iPoker, Winning Poker
          Network. The room is auto-detected from each file's own content, one absolute file path
          per line (see docs/11-development-tracking.md for the Import Engine's current coverage).
        </p>
        <form onSubmit={handleImport} className="import-form">
          <textarea
            value={filePathsInput}
            onChange={(e) => setFilePathsInput(e.currentTarget.value)}
            placeholder={"C:\\path\\to\\hand-history.txt"}
            rows={4}
          />
          <button type="submit" className="btn-outline" disabled={importing}>
            {importing ? "Importing…" : "Import"}
          </button>
        </form>
        {results && (
          <ul className="import-results">
            {results.map((result) => (
              <li key={result.filePath}>
                <strong>{result.status}</strong> — {result.filePath}: {result.handsImported} imported,{" "}
                {result.handsSkipped} skipped, {result.handsFailed} failed
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
