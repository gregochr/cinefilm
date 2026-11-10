// Reads a file that lives outside frontend/ because the backend suite reads the same one (the edition
// validation cases, the search vectors). Tests reach such a file only through here, never with a relative
// import or process.cwd(): Stryker runs the suite from a copy of frontend/ (frontend/.stryker-tmp/sandbox-*),
// where both lead nowhere and its dry run fails. Walking up from this file finds the repository from either
// place. docs/engineering/dev-and-testing.md, "Mutation testing".
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Parses a JSON file given by its path from the repository root, e.g. `docs/design/search/vectors.json`.
 * @throws when no folder above this file holds that path
 */
export function readRepoJson(repoPath) {
  let dir = dirname(fileURLToPath(import.meta.url));
  while (!existsSync(join(dir, repoPath))) {
    const parent = dirname(dir);
    if (parent === dir) {
      throw new Error(`${repoPath} was not found in any folder above ${import.meta.url}`);
    }
    dir = parent;
  }
  return JSON.parse(readFileSync(join(dir, repoPath), 'utf8'));
}
