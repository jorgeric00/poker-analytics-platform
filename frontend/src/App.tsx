import { HashRouter, Route, Routes } from "react-router-dom";
import { Sidebar } from "./components/Sidebar";
import { OverviewPage } from "./pages/OverviewPage";
import { HandsPage } from "./pages/HandsPage";
import { ImportPage } from "./pages/ImportPage";
import { PlaceholderPage } from "./pages/PlaceholderPage";
import "./theme.css";
import "./App.css";

// HashRouter, not BrowserRouter: this app is served from a file:// origin inside the packaged
// Tauri app (docs/decisions/ADR-004-backend-packaging.md), where a path-based router can't resolve
// a real server-side route on refresh/deep-link the way it can on a normal web host.
function App() {
  return (
    <HashRouter>
      <div className="app-shell">
        <Sidebar />
        <main className="app-content">
          <Routes>
            <Route path="/" element={<OverviewPage />} />
            <Route path="/live-session" element={<PlaceholderPage title="Live Session" />} />
            <Route path="/hands" element={<HandsPage />} />
            <Route path="/players" element={<PlaceholderPage title="Players" />} />
            <Route path="/statistics" element={<PlaceholderPage title="Statistics" />} />
            <Route path="/session-history" element={<PlaceholderPage title="Session History" />} />
            <Route path="/reports" element={<PlaceholderPage title="Reports" />} />
            <Route path="/import" element={<ImportPage />} />
            <Route path="/settings" element={<PlaceholderPage title="Settings" />} />
          </Routes>
        </main>
      </div>
    </HashRouter>
  );
}

export default App;
