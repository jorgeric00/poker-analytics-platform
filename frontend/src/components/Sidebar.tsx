import { NavLink } from "react-router-dom";
import {
  Broadcast,
  Cards,
  ChartBar,
  ClockCounterClockwise,
  DownloadSimple,
  FileText,
  Gauge,
  GearSix,
  UsersThree,
} from "@phosphor-icons/react";

// Matches the approved Nocturne mockups' sidebar exactly (claude.ai/design project "Poker
// analytics UI mockups", Overview.dc.html / Reports.dc.html — docs/12-ui-designs.md), including
// Session History and Reports as two separate entries per docs/05-product-map.md's Reports
// module note ("reachable as two separate sidebar entries"), which the first cut of this sidebar
// had missed. Only Overview has a real screen behind it so far (docs/11-development-tracking.md).
const MODULES = [
  { path: "/", label: "Overview", icon: Gauge },
  { path: "/live-session", label: "Live Session", icon: Broadcast },
  { path: "/hands", label: "Hands", icon: Cards },
  { path: "/players", label: "Players", icon: UsersThree },
  { path: "/statistics", label: "Statistics", icon: ChartBar },
  { path: "/session-history", label: "Session History", icon: ClockCounterClockwise },
  { path: "/reports", label: "Reports", icon: FileText },
];

const FOOTER_ITEMS = [
  { path: "/import", label: "Import hands", icon: DownloadSimple },
  { path: "/settings", label: "Settings", icon: GearSix },
];

export function Sidebar() {
  return (
    <nav className="sidebar">
      <div className="sidebar-brand">
        <span className="sidebar-brand-mark">P</span>
        <span className="sidebar-brand-name">Poker Analytics</span>
      </div>

      <ul className="sidebar-nav">
        {MODULES.map((module) => (
          <li key={module.path}>
            <NavLink
              to={module.path}
              className={({ isActive }) => (isActive ? "sidebar-link sidebar-link-active" : "sidebar-link")}
              end={module.path === "/"}
            >
              <module.icon size={15} weight="regular" />
              {module.label}
            </NavLink>
          </li>
        ))}
      </ul>

      <ul className="sidebar-footer">
        {FOOTER_ITEMS.map((item) => (
          <li key={item.path}>
            <NavLink
              to={item.path}
              className={({ isActive }) => (isActive ? "sidebar-link sidebar-link-active" : "sidebar-link")}
            >
              <item.icon size={15} weight="regular" />
              {item.label}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  );
}
