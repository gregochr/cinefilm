/** A section's `<h1>`. Focusable by script only, so focus has somewhere to land when the row it was on goes away. */
export default function SectionTitle({ children }) {
  return (
    <h1 id="admin-section-title" className="admin__section-title" tabIndex={-1}>
      {children}
    </h1>
  );
}
