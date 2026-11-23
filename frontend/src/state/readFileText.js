/**
 * The text of a file the browser holds, read here and nowhere else. `FileReader` is used rather than `Blob.text()`
 * because it is there in every browser and in the test environment. Rejects when the file cannot be read.
 */
export function readFileText(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error);
    reader.readAsText(file);
  });
}
