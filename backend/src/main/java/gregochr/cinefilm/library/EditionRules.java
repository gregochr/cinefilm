package gregochr.cinefilm.library;

import gregochr.cinefilm.config.FieldError;
import gregochr.cinefilm.library.EditionDtos.EditionRequest;
import gregochr.cinefilm.library.EditionDtos.FileRequest;
import gregochr.cinefilm.library.EditionDtos.SaveRequest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The rules an Edit editions save must keep, as field-level errors in the owner's words. The panel runs the same
 * rules in {@code frontend/src/state/editionRules.js}; both suites read the one table of cases in
 * {@code backend/src/test/resources/shared/edition-validation-cases.json}, so the wording and the order cannot drift.
 *
 * <p>Fields are paths into the request: {@code files}, {@code editions}, {@code editions[i].name},
 * {@code editions[i].parts}, {@code editions[i].files} and {@code default}. The order is fixed: {@code files}, then
 * each edition in turn (its name, then its parts), then {@code editions}, then {@code default}.
 */
public final class EditionRules {

    /** The longest edition name. */
    public static final int NAME_MAX = 32;
    /** The highest part number. */
    public static final int PART_MAX = 8;

    static final String UNPLACED = "Every file has to be in an edition or marked Not this film.";
    static final String TWICE = "Each file can only be in one edition, or marked Not this film.";
    static final String EMPTY_EDITION = "Every edition needs at least one file.";
    static final String NAME_BLANK = "Give this edition a name.";
    static final String NAME_LONG = "Keep the name to 32 characters.";
    static final String PART_RANGE = "Parts go from 1 to 8.";
    static final String SINGLE_MANY = "Single file only works when an edition has one file — give each file a part "
        + "number.";
    static final String PART_ONE = "Only one file — set it to Single file or Part 1.";
    static final String NO_EDITIONS = "Keep at least one file with this film.";
    static final String NO_DEFAULT = "Choose a default edition.";

    private EditionRules() {
    }

    /**
     * Checks a save against the film's files.
     *
     * @param request the save (null lists count as empty)
     * @param filmFileIds the ids of every original file of the film
     * @return the broken rules in the fixed order; empty when the save is fine
     */
    public static List<FieldError> validate(SaveRequest request, Collection<Long> filmFileIds) {
        List<EditionRequest> editions = request.editions() == null ? List.of() : request.editions();
        List<Long> notThis = request.notThisFilm() == null ? List.of() : request.notThisFilm();
        List<FieldError> errors = new ArrayList<>();
        files(errors, editions, notThis, filmFileIds);
        for (int i = 0; i < editions.size(); i++) {
            name(errors, editions, i);
            parts(errors, editions.get(i), i);
        }
        if (editions.isEmpty()) {
            errors.add(new FieldError("editions", NO_EDITIONS));
        } else if (editions.stream().filter(EditionRequest::isDefault).count() != 1) {
            errors.add(new FieldError("default", NO_DEFAULT));
        }
        return errors;
    }

    /** Formats parts as the owner reads them: "Parts 1 and 3 — part 2 is missing." */
    static String gap(List<Integer> sortedParts, List<Integer> missing) {
        return "Parts " + sortedParts.stream().map(String::valueOf).collect(Collectors.joining(" and "))
            + " — part " + missing.stream().map(String::valueOf).collect(Collectors.joining(" and "))
            + (missing.size() > 1 ? " are" : " is") + " missing.";
    }

    private static void files(List<FieldError> errors, List<EditionRequest> editions, List<Long> notThis,
                              Collection<Long> filmFileIds) {
        List<Long> assigned = new ArrayList<>(notThis);
        for (EditionRequest edition : editions) {
            if (edition.files() != null) {
                edition.files().forEach(f -> assigned.add(f.fileId()));
            }
        }
        Set<Long> distinct = new HashSet<>(assigned);
        if (!distinct.equals(new HashSet<>(filmFileIds))) {
            errors.add(new FieldError("files", UNPLACED));
        } else if (distinct.size() != assigned.size()) {
            errors.add(new FieldError("files", TWICE));
        }
    }

    private static void name(List<FieldError> errors, List<EditionRequest> editions, int index) {
        String field = "editions[" + index + "].name";
        String name = trimmed(editions.get(index));
        if (name.isEmpty()) {
            errors.add(new FieldError(field, NAME_BLANK));
        } else if (name.length() > NAME_MAX) {
            errors.add(new FieldError(field, NAME_LONG));
        } else {
            for (int j = 0; j < editions.size(); j++) {
                if (j != index && beats(editions, j, index)
                    && trimmed(editions.get(j)).toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
                    errors.add(new FieldError(field, "Another edition is already called " + trimmed(editions.get(j))
                        + "."));
                    return;
                }
            }
        }
    }

    /** An existing edition keeps its name over a new one; between two of a kind the earlier one keeps it. */
    private static boolean beats(List<EditionRequest> editions, int other, int index) {
        boolean otherExisting = editions.get(other).id() != null;
        boolean existing = editions.get(index).id() != null;
        return otherExisting != existing ? otherExisting : other < index;
    }

    private static String trimmed(EditionRequest edition) {
        return edition.name() == null ? "" : edition.name().strip();
    }

    private static void parts(List<FieldError> errors, EditionRequest edition, int index) {
        List<FileRequest> files = edition.files() == null ? List.of() : edition.files();
        if (files.isEmpty()) {
            errors.add(new FieldError("editions[" + index + "].files", EMPTY_EDITION));
            return;
        }
        String message = partsMessage(files);
        if (message != null) {
            errors.add(new FieldError("editions[" + index + "].parts", message));
        }
    }

    private static String partsMessage(List<FileRequest> files) {
        if (files.stream().anyMatch(f -> f.part() != null && (f.part() < 1 || f.part() > PART_MAX))) {
            return PART_RANGE;
        }
        if (files.size() == 1) {
            Integer part = files.get(0).part();
            return part != null && part != 1 ? PART_ONE : null;
        }
        if (files.stream().anyMatch(f -> f.part() == null)) {
            return SINGLE_MANY;
        }
        List<Integer> sorted = files.stream().map(FileRequest::part).sorted().toList();
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).equals(sorted.get(i - 1))) {
                return "Two files are both part " + sorted.get(i) + ".";
            }
        }
        Set<Integer> missing = new TreeSet<>();
        for (int n = 1; n <= sorted.get(sorted.size() - 1); n++) {
            if (!sorted.contains(n)) {
                missing.add(n);
            }
        }
        return missing.isEmpty() ? null : gap(sorted, List.copyOf(missing));
    }
}
