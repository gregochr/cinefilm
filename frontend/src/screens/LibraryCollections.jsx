import { useEffect } from 'react';
import { useOutletContext } from 'react-router-dom';
import CollectionCard from '../components/CollectionCard.jsx';
import { GridSkeleton } from '../components/Skeleton.jsx';
import { ErrorState } from '../components/StateMessages.jsx';
import { useCollections } from '../state/usePeopleAndCollections.js';

export default function LibraryCollections() {
  const { setCountLabel } = useOutletContext();
  const { data, error, loading, reload } = useCollections();
  const collections = data?.collections;
  const total = collections?.length;
  useEffect(() => {
    setCountLabel(total == null ? '' : `${total} ${total === 1 ? 'collection' : 'collections'}`);
  }, [total, setCountLabel]);

  if (loading && !data) return <GridSkeleton />;
  if (error && !data) return <ErrorState onRetry={reload} />;
  if (!collections) return null;
  return (
    <div className="collection-grid">
      {collections.map((c) => (
        <CollectionCard key={c.id} collection={c} />
      ))}
    </div>
  );
}
