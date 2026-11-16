import AdminErrorBanner from '../../components/AdminErrorBanner.jsx';
import { SkeletonBlock } from '../../components/Skeleton.jsx';
import { todoRow } from '../../state/adminCopy.js';

/**
 * What stands in for a To do list that has not arrived: a static skeleton (`error` null) or, when its request
 * failed, an inline banner in the error colour (never amber) with its own retry. `label` names the list for
 * assistive technology ("Loading the films missing English subtitles").
 */
export default function AdminTodoNotice({ label, error, onRetry }) {
  if (error) {
    return (
      <AdminErrorBanner onRetry={onRetry} retryLabel={todoRow.retry}>
        {error}
      </AdminErrorBanner>
    );
  }
  return (
    <div role="status" aria-label={`${todoRow.loadingLabel} ${label}`}>
      <SkeletonBlock className="admin__todo-skeleton" />
    </div>
  );
}
