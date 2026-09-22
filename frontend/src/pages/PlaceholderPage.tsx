interface PlaceholderPageProps {
  title: string;
}

/** For every product-map module (docs/05-product-map.md) with no real backend/screen yet — a
 *  design exists for most of these already (docs/12-ui-designs.md), just not implemented here. */
export function PlaceholderPage({ title }: PlaceholderPageProps) {
  return (
    <div className="page">
      <div className="page-header">
        <div>
          <div className="kicker">{title}</div>
          <h1>Not implemented yet</h1>
        </div>
      </div>
      <p className="placeholder-note">See docs/11-development-tracking.md for current status.</p>
    </div>
  );
}
