package gregochr.cinefilm.library;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.library.FileNameParser.ParsedName;
import org.junit.jupiter.api.Test;

class FileNameParserTest {

    private static ParsedName parse(String path) {
        return FileNameParser.parse(path);
    }

    private static void sameFilm(String a, String b) {
        assertThat(parse(a).comparisonTitle()).as(a + " vs " + b).isEqualTo(parse(b).comparisonTitle());
    }

    private static void differentFilms(String a, String b) {
        assertThat(parse(a).comparisonTitle()).as(a + " vs " + b).isNotEqualTo(parse(b).comparisonTitle());
    }

    // --- the owner's own split files ---

    @Test
    void twoTowersPartOneWithAnEditionTag() {
        ParsedName p = parse("Lord of the Rings/The Two Towers {edition-Extended Edition} pt1.m4v");

        assertThat(p.title()).isEqualTo("The Two Towers");
        assertThat(p.editionTag()).isEqualTo("Extended Edition");
        assertThat(p.partTag()).isEqualTo(1);
        assertThat(p.tagConflict()).isFalse();
        assertThat(p.year()).isNull();
        assertThat(p.comparisonTitle()).isEqualTo("the two towers");
        assertThat(p.groupKey()).isEqualTo("Lord of the Rings|the two towers||extended edition");
    }

    @Test
    void twoTowersPartTwoWithADoubledExtensionSharesTheGroupKey() {
        ParsedName one = parse("Lord of the Rings/The Two Towers {edition-Extended Edition} pt1.m4v");
        ParsedName two = parse("Lord of the Rings/The Two Towers {edition-Extended Edition} pt2.m4v.m4v");

        assertThat(two.partTag()).isEqualTo(2);
        assertThat(two.title()).isEqualTo("The Two Towers");
        assertThat(two.editionTag()).isEqualTo("Extended Edition");
        assertThat(two.groupKey()).isEqualTo(one.groupKey());
    }

    @Test
    void repeatedExtensionsAreStrippedInAnyCaseAndAllVideoExtensionsCount() {
        assertThat(parse("Heat.MP4.m4v.MKV").title()).isEqualTo("Heat");
        assertThat(parse("Heat.mov").title()).isEqualTo("Heat");
        assertThat(parse("Heat.avi").title()).isEqualTo("Heat");
    }

    @Test
    void aFileNamedOnlyByAnExtensionKeepsItsName() {
        assertThat(parse(".m4v").title()).isEqualTo("m4v");
    }

    @Test
    void theTheatricalCutOfTheSameFilmInTheSameFolderIsADifferentGroupKey() {
        ParsedName extended = parse("LOTR/The Two Towers {edition-Extended Edition} pt1.m4v");
        ParsedName theatrical = parse("LOTR/The Two Towers {edition-Theatrical Cut}.m4v");

        assertThat(theatrical.partTag()).isNull();
        assertThat(theatrical.editionTag()).isEqualTo("Theatrical Cut");
        assertThat(theatrical.comparisonTitle()).isEqualTo(extended.comparisonTitle());
        assertThat(theatrical.groupKey()).isNotEqualTo(extended.groupKey());
    }

    // --- part suffixes ---

    @Test
    void everyPartSuffixSpellingIsRecognisedAndRemoved() {
        String[][] cases = {
            {"Movie - pt1.m4v", "1"}, {"Movie.part2.m4v", "2"}, {"Movie cd1.m4v", "1"}, {"Movie cd2.m4v", "2"},
            {"Movie pt.1.m4v", "1"}, {"Movie cd-1.m4v", "1"}, {"Movie_disc 2.m4v", "2"}, {"Movie disk3.m4v", "3"},
            {"Movie DVD4.m4v", "4"}, {"Movie - PT5.m4v", "5"}, {"Movie-part6.m4v", "6"}, {"Movie.Pt7.m4v", "7"},
            {"Movie_cd8.m4v", "8"}, {"Movie - pt 2.m4v", "2"}
        };
        for (String[] c : cases) {
            ParsedName p = parse(c[0]);
            assertThat(p.partTag()).as(c[0]).isEqualTo(Integer.parseInt(c[1]));
            assertThat(p.title()).as(c[0]).isEqualTo("Movie");
        }
    }

    @Test
    void aPartSuffixFollowedByQualityWordsOrSeparatorsIsStillFound() {
        assertThat(parse("Movie pt1 1080p.m4v").partTag()).isEqualTo(1);
        assertThat(parse("Movie pt1 1080p.m4v").title()).isEqualTo("Movie");
        assertThat(parse("Movie pt2._-.m4v").partTag()).isEqualTo(2);
    }

    @Test
    void partNumbersOutsideOneToEightAreNotParts() {
        assertThat(parse("Movie pt0.m4v").partTag()).isNull();
        assertThat(parse("Movie pt9.m4v").partTag()).isNull();
        assertThat(parse("Movie pt12.m4v").partTag()).isNull();
        assertThat(parse("Movie pt9.m4v").title()).isEqualTo("Movie pt9");
    }

    @Test
    void partNeedsASeparatorBeforeItAndTheNumberAtTheEnd() {
        assertThat(parse("Spt1.m4v").partTag()).isNull();
        assertThat(parse("pt1.m4v").partTag()).isNull();
        assertThat(parse("pt1.m4v").title()).isEqualTo("pt1");
        assertThat(parse("Movie pt1 extra.m4v").partTag()).isNull();
    }

    @Test
    void titleDashPt2IsAPartOfTheTitle() {
        ParsedName p = parse("Title - pt2.m4v");

        assertThat(p.partTag()).isEqualTo(2);
        assertThat(p.title()).isEqualTo("Title");
    }

    @Test
    void thePartWordWithASpaceIsASequelMarkerInTheTitleNotAPart() {
        ParsedName one = parse("Harry Potter and the Deathly Hallows - Part 1.m4v");
        ParsedName two = parse("Harry Potter and the Deathly Hallows - Part 2.m4v");

        assertThat(one.partTag()).isNull();
        assertThat(two.partTag()).isNull();
        assertThat(one.title()).isEqualTo("Harry Potter and the Deathly Hallows - Part 1");
        assertThat(one.comparisonTitle()).isEqualTo("harry potter and the deathly hallows part 1");
        differentFilms(one.title(), two.title());
        assertThat(one.comparisonTitle()).isNotEqualTo(two.comparisonTitle());
    }

    @Test
    void aPartSuffixAfterABracketedYearIsStillFound() {
        ParsedName p = parse("Two Towers (2002) {edition-Extended} - pt2.mkv");

        assertThat(p.year()).isEqualTo(2002);
        assertThat(p.title()).isEqualTo("Two Towers");
        assertThat(p.partTag()).isEqualTo(2);
        assertThat(p.editionTag()).isEqualTo("Extended");
    }

    @Test
    void aPartSuffixBeforeABracketedYearWins() {
        ParsedName p = parse("Movie pt1 (2002) pt2.mkv");

        assertThat(p.partTag()).isEqualTo(1);
        assertThat(p.title()).isEqualTo("Movie");
    }

    @Test
    void textAfterAYearWithoutAPartSuffixGivesNoPart() {
        assertThat(parse("Movie (2002) Remastered.mkv").partTag()).isNull();
    }

    // --- editions ---

    @Test
    void aFileWithAnEditionTagAndNoPartsIsASingleEdition() {
        ParsedName p = parse("Gladiator/Gladiator {edition-Theatrical Cut}.m4v");

        assertThat(p.editionTag()).isEqualTo("Theatrical Cut");
        assertThat(p.partTag()).isNull();
        assertThat(p.title()).isEqualTo("Gladiator");
    }

    @Test
    void anUnrelatedFileInTheSameFolderHasADifferentGroupKeyAndComparisonTitle() {
        ParsedName towers = parse("Box/The Two Towers {edition-Extended Edition} pt1.m4v");
        ParsedName other = parse("Box/Making Of.m4v");

        assertThat(other.comparisonTitle()).isEqualTo("making of");
        assertThat(other.groupKey()).isNotEqualTo(towers.groupKey());
        assertThat(other.editionTag()).isNull();
    }

    @Test
    void aTagOnlyOnTheFolderIsTheEditionOfItsFiles() {
        ParsedName p = parse("Gladiator {edition-Extended}/Gladiator pt1.m4v");

        assertThat(p.editionTag()).isEqualTo("Extended");
        assertThat(p.tagConflict()).isFalse();
        assertThat(p.partTag()).isEqualTo(1);
    }

    @Test
    void theSameTagOnFileAndFolderIsNotAConflictEvenInADifferentCase() {
        ParsedName p = parse("Gladiator {edition-Extended}/Gladiator {edition-EXTENDED}.m4v");

        assertThat(p.editionTag()).isEqualTo("EXTENDED");
        assertThat(p.tagConflict()).isFalse();
    }

    @Test
    void differentTagsOnFileAndFolderTakeTheFilesAndFlagAConflict() {
        ParsedName p = parse("Gladiator {edition-Extended}/Gladiator {edition-Theatrical Cut}.m4v");

        assertThat(p.editionTag()).isEqualTo("Theatrical Cut");
        assertThat(p.tagConflict()).isTrue();
    }

    @Test
    void twoDifferentTagsInOneFileNameTakeTheFirstAndFlagAConflict() {
        ParsedName p = parse("Gladiator {edition-Extended} {edition-Theatrical}.m4v");

        assertThat(p.editionTag()).isEqualTo("Extended");
        assertThat(p.tagConflict()).isTrue();
        assertThat(p.title()).isEqualTo("Gladiator");
    }

    @Test
    void twoDifferentTagsInTheFolderNameFlagAConflict() {
        ParsedName p = parse("Gladiator {edition-A} {edition-B}/Gladiator.m4v");

        assertThat(p.editionTag()).isEqualTo("A");
        assertThat(p.tagConflict()).isTrue();
    }

    @Test
    void theSameTagTwiceInOneFileNameIsNotAConflict() {
        ParsedName p = parse("Gladiator {edition-Extended} {edition-extended}.m4v");

        assertThat(p.editionTag()).isEqualTo("Extended");
        assertThat(p.tagConflict()).isFalse();
    }

    @Test
    void anEditionNameIsCutAtThirtyTwoCharactersAndNotAtThirtyOne() {
        String thirtyTwo = "A".repeat(32);

        assertThat(parse("Film {edition-" + thirtyTwo + "}.m4v").editionTag()).isEqualTo(thirtyTwo);
        assertThat(parse("Film {edition-" + thirtyTwo + "B}.m4v").editionTag()).isEqualTo(thirtyTwo);
        assertThat(parse("Film {edition-" + "A".repeat(31) + "}.m4v").editionTag()).isEqualTo("A".repeat(31));
    }

    @Test
    void tagsThatDifferOnlyBeyondThirtyTwoCharactersAreTheSameTag() {
        String prefix = "A".repeat(32);

        ParsedName p = parse(prefix + "x {edition-" + prefix + "y}/Film {edition-" + prefix + "z}.m4v");

        assertThat(p.tagConflict()).isFalse();
        assertThat(p.editionTag()).isEqualTo(prefix);
    }

    @Test
    void aBlankEditionTagIsIgnoredButStillRemovedFromTheTitle() {
        ParsedName p = parse("Film {edition- }.m4v");

        assertThat(p.editionTag()).isNull();
        assertThat(p.title()).isEqualTo("Film");
    }

    @Test
    void editionTagWhitespaceIsTrimmedAndTheTagIsMatchedInAnyCase() {
        assertThat(parse("Film {EDITION-  Final Cut  }.m4v").editionTag()).isEqualTo("Final Cut");
    }

    // --- ids ---

    @Test
    void tmdbAndImdbIdsAreReadAndRemoved() {
        ParsedName p = parse("Heat (1995) {tmdb-949} {imdb-tt0113277}.m4v");

        assertThat(p.tmdbId()).isEqualTo(949L);
        assertThat(p.imdbId()).isEqualTo("tt0113277");
        assertThat(p.title()).isEqualTo("Heat");
        assertThat(p.year()).isEqualTo(1995);
    }

    @Test
    void idTagsAreCaseInsensitiveAndTheImdbIdIsLowerCased() {
        ParsedName p = parse("Heat {TMDB-949} {IMDB-TT0113277}.m4v");

        assertThat(p.tmdbId()).isEqualTo(949L);
        assertThat(p.imdbId()).isEqualTo("tt0113277");
    }

    @Test
    void idsFallBackToTheFolderAndAreRemovedFromItsYearReading() {
        ParsedName p = parse("Heat (1995) {tmdb-949} {imdb-tt0113277}/heat.m4v");

        assertThat(p.tmdbId()).isEqualTo(949L);
        assertThat(p.imdbId()).isEqualTo("tt0113277");
        assertThat(p.year()).isEqualTo(1995);
    }

    @Test
    void theFilesIdBeatsTheFoldersAndNoIdsGiveNulls() {
        assertThat(parse("Heat {tmdb-1}/Heat {tmdb-2} {imdb-tt2}.m4v").tmdbId()).isEqualTo(2L);
        assertThat(parse("Heat {imdb-tt1}/Heat {imdb-tt2}.m4v").imdbId()).isEqualTo("tt2");
        ParsedName none = parse("Heat.m4v");
        assertThat(none.tmdbId()).isNull();
        assertThat(none.imdbId()).isNull();
    }

    @Test
    void anIdTooLongToBeAnIdIsNotOne() {
        ParsedName p = parse("Heat {tmdb-" + "9".repeat(19) + "}.m4v");

        assertThat(p.tmdbId()).isNull();
    }

    // --- quality tokens ---

    @Test
    void qualityTokensAfterTheFirstWordAreRemovedAsWholeTokens() {
        assertThat(parse("Robin Hood - HD.m4v").title()).isEqualTo("Robin Hood");
        assertThat(parse("Heat 1080p BluRay x264 AAC.m4v").title()).isEqualTo("Heat");
        assertThat(parse("Heat 4K HDR10 HEVC DTS.m4v").title()).isEqualTo("Heat");
        assertThat(parse("Heat WEB-DL H.264 AC3 SD.m4v").title()).isEqualTo("Heat");
        assertThat(parse("Heat Blu-ray Remux UHD 2160p.m4v").title()).isEqualTo("Heat");
        assertThat(parse("Heat BRRip BDRip WEBRip DVDRip 720p 576p 480p 1080i H264 x265 HDR.m4v").title())
            .isEqualTo("Heat");
    }

    @Test
    void aQualityWordIsKeptWhenItIsTheFirstWordOrPartOfALongerWord() {
        assertThat(parse("HD Wars.m4v").title()).isEqualTo("HD Wars");
        assertThat(parse("The HDTV Years.m4v").title()).isEqualTo("The HDTV Years");
        assertThat(parse("My SDK.m4v").title()).isEqualTo("My SDK");
        assertThat(parse("Heat HD4.m4v").title()).isEqualTo("Heat HD4");
    }

    @Test
    void qualityTokensAreRemovedFromDottedNamesToo() {
        assertThat(parse("The.Wicker.Man.1080p.BluRay.m4v").title()).isEqualTo("The Wicker Man");
    }

    // --- years ---

    @Test
    void aBracketedYearAnywhereIsTheYearAndEverythingAfterItGoes() {
        ParsedName round = parse("Heat (1995) extra words.m4v");
        ParsedName square = parse("Heat [1995].m4v");

        assertThat(round.year()).isEqualTo(1995);
        assertThat(round.title()).isEqualTo("Heat");
        assertThat(square.year()).isEqualTo(1995);
        assertThat(square.title()).isEqualTo("Heat");
    }

    @Test
    void aBareYearTokenThatIsNotFirstIsTheYearAndEverythingAfterItGoes() {
        ParsedName p = parse("Heat.1995.Remastered.Something.m4v");

        assertThat(p.year()).isEqualTo(1995);
        assertThat(p.title()).isEqualTo("Heat");
    }

    @Test
    void inANameWithSpacesABareNumberIsTitleNotYear() {
        ParsedName p = parse("Movie 1999 - pt1.m4v");
        assertThat(p.title()).isEqualTo("Movie 1999");
        assertThat(p.year()).isNull();
        assertThat(p.partTag()).isEqualTo(1);

        ParsedName runner = parse("Blade Runner 2049.m4v");
        assertThat(runner.title()).isEqualTo("Blade Runner 2049");
        assertThat(runner.year()).isNull();
        assertThat(runner.comparisonTitle()).isNotEqualTo(parse("Blade Runner (1982).m4v").comparisonTitle());
        assertThat(parse("Heat 1995 Remastered.m4v").title()).isEqualTo("Heat 1995 Remastered");
        assertThat(parse("2001 A Space Odyssey (1968).m4v").title()).isEqualTo("2001 A Space Odyssey");
        assertThat(parse("2001 A Space Odyssey (1968).m4v").year()).isEqualTo(1968);
    }

    @Test
    void inASceneNameTheLastBareYearThatIsNotFirstWins() {
        ParsedName p = parse("Blade.Runner.2049.2017.1080p.BluRay.x264.mkv");

        assertThat(p.title()).isEqualTo("Blade Runner 2049");
        assertThat(p.year()).isEqualTo(2017);
    }

    @Test
    void aYearAtTheStartOfTheNameIsTheTitle() {
        ParsedName p = parse("1917.m4v");
        assertThat(p.title()).isEqualTo("1917");
        assertThat(p.year()).isNull();

        ParsedName odyssey = parse("2001 A Space Odyssey.m4v");
        assertThat(odyssey.title()).isEqualTo("2001 A Space Odyssey");
        assertThat(odyssey.year()).isNull();

        ParsedName dotted = parse("2012.1080p.m4v");
        assertThat(dotted.title()).isEqualTo("2012");
        assertThat(dotted.year()).isNull();
    }

    @Test
    void aBracketedYearWithNoTitleBeforeItStaysInTheTitle() {
        ParsedName p = parse("(1999).m4v");

        assertThat(p.title()).isEqualTo("(1999)");
        assertThat(p.year()).isNull();
    }

    @Test
    void aLaterBracketedYearIsUsedWhenAnEarlierOneHasNoTitleBeforeIt() {
        ParsedName p = parse("(1999) Heat (1995).m4v");

        assertThat(p.year()).isEqualTo(1995);
        assertThat(p.title()).isEqualTo("(1999) Heat");
    }

    @Test
    void aBracketedYearBeatsABareOneBeforeIt() {
        ParsedName p = parse("Blade Runner 2049 (2017).m4v");

        assertThat(p.year()).isEqualTo(2017);
        assertThat(p.title()).isEqualTo("Blade Runner 2049");
    }

    @Test
    void yearBoundsAre1888To2100Inclusive() {
        assertThat(parse("Garden (1888).m4v").year()).isEqualTo(1888);
        assertThat(parse("Garden (1887).m4v").year()).isNull();
        assertThat(parse("Garden (1887).m4v").title()).isEqualTo("Garden (1887)");
        assertThat(parse("Future (2100).m4v").year()).isEqualTo(2100);
        assertThat(parse("Future (2101).m4v").year()).isNull();
        assertThat(parse("Garden.1888.x.m4v").year()).isEqualTo(1888);
        assertThat(parse("Garden.1887.x.m4v").year()).isNull();
        assertThat(parse("Future.2100.x.m4v").year()).isEqualTo(2100);
        assertThat(parse("Future.2101.x.m4v").year()).isNull();
    }

    @Test
    void aFolderTitleYearSuppliesTheYearWhenTheFileHasNone() {
        ParsedName p = parse("Heat (1995)/heat.m4v");

        assertThat(p.year()).isEqualTo(1995);
        assertThat(p.title()).isEqualTo("heat");
    }

    @Test
    void aFileYearBeatsTheFolderYear() {
        assertThat(parse("Heat (1995)/Heat (1996).m4v").year()).isEqualTo(1996);
    }

    @Test
    void aFolderYearOutsideTheFilmRangeIsIgnoredAndALaterOneIsUsed() {
        assertThat(parse("Heat (1000)/heat.m4v").year()).isNull();
        assertThat(parse("Heat (1000) [1995]/heat.m4v").year()).isEqualTo(1995);
    }

    @Test
    void aFolderWithoutAYearGivesNoYear() {
        assertThat(parse("Movies/heat.m4v").year()).isNull();
    }

    @Test
    void apollo13IsNotAYearNorAPart() {
        ParsedName p = parse("Apollo 13.m4v");

        assertThat(p.title()).isEqualTo("Apollo 13");
        assertThat(p.year()).isNull();
        assertThat(p.partTag()).isNull();
        assertThat(p.comparisonTitle()).isEqualTo("apollo 13");

        ParsedName withYear = parse("Apollo 13 (1995).m4v");
        assertThat(withYear.title()).isEqualTo("Apollo 13");
        assertThat(withYear.year()).isEqualTo(1995);
        assertThat(parse("Apollo.13.1995.1080p.m4v").title()).isEqualTo("Apollo 13");
    }

    @Test
    void sceneNamesKeepTheTitleAndTheYearAndDropTheRest() {
        ParsedName p = parse("Rocky.IV.1985.1080p.BluRay.x264-SPARKS.mkv");

        assertThat(p.title()).isEqualTo("Rocky IV");
        assertThat(p.year()).isEqualTo(1985);
        assertThat(p.partTag()).isNull();
        assertThat(p.comparisonTitle()).isEqualTo("rocky 4");
    }

    @Test
    void aSceneNameWithAPartAfterTheYearStillHasThePart() {
        ParsedName p = parse("Movie.1999.1080p.BluRay-pt2.mkv");

        assertThat(p.title()).isEqualTo("Movie");
        assertThat(p.partTag()).isEqualTo(2);
    }

    // --- tidying ---

    @Test
    void dotsAndUnderscoresReadAsSpacesOnlyWhenThereAreNoSpaces() {
        assertThat(parse("The.Wicker_Man.m4v").title()).isEqualTo("The Wicker Man");
        assertThat(parse("Mr. Nobody.m4v").title()).isEqualTo("Mr. Nobody");
        assertThat(parse("A__B..C.m4v").title()).isEqualTo("A B C");
    }

    @Test
    void trailingSeparatorsAreTrimmed() {
        assertThat(parse("Heat - .m4v").title()).isEqualTo("Heat");
        assertThat(parse("Heat._-.m4v").title()).isEqualTo("Heat");
    }

    @Test
    void aNameWithNothingLeftFallsBackToTheRawName() {
        ParsedName p = parse("{edition-Extended}.m4v");

        assertThat(p.title()).isEqualTo("{edition-Extended}");
        assertThat(p.editionTag()).isEqualTo("Extended");
    }

    @Test
    void windowsSeparatorsAreTreatedLikeSlashes() {
        ParsedName p = parse("Heat (1995)\\heat.m4v");

        assertThat(p.year()).isEqualTo(1995);
        assertThat(p.groupKey()).startsWith("Heat (1995)|");
    }

    @Test
    void aFileAtTheRootHasAnEmptyFolderInItsGroupKey() {
        assertThat(parse("Heat.m4v").groupKey()).isEqualTo("|heat||");
    }

    @Test
    void theGroupKeyUsesTheWholeFolderPathAndTheLowerCasedTagAndTheYear() {
        assertThat(parse("A/B/Heat (1995) {edition-Final Cut}.m4v").groupKey())
            .isEqualTo("A/B|heat|1995|final cut");
    }

    // --- comparison titles ---

    @Test
    void rockyIvIsRocky4ButNotRockyV() {
        sameFilm("Rocky IV.m4v", "Rocky 4.m4v");
        differentFilms("Rocky IV.m4v", "Rocky V.m4v");
        differentFilms("Rocky 4.m4v", "Rocky 5.m4v");
    }

    @Test
    void romanNumeralsTwoToTwentyBecomeNumbersAndOneIsLeftAlone() {
        String[] numerals = {"II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII", "XIII", "XIV", "XV",
            "XVI", "XVII", "XVIII", "XIX", "XX"};
        for (int i = 0; i < numerals.length; i++) {
            assertThat(parse("Film " + numerals[i] + ".m4v").comparisonTitle()).isEqualTo("film " + (i + 2));
        }
        assertThat(parse("I Robot.m4v").comparisonTitle()).isEqualTo("i robot");
        assertThat(parse("Film XXI.m4v").comparisonTitle()).isEqualTo("film xxi");
        assertThat(parse("Film Mix.m4v").comparisonTitle()).isEqualTo("film mix");
    }

    @Test
    void aStandaloneNumeralAtTheStartIsMappedConsistently() {
        assertThat(parse("V for Vendetta.m4v").comparisonTitle()).isEqualTo("5 for vendetta");
        sameFilm("V for Vendetta.m4v", "5 for Vendetta.m4v");
    }

    @Test
    void robinHoodIsTheSameFilmAsRobinHoodHd() {
        sameFilm("Robin Hood.m4v", "Robin Hood - HD.m4v");
    }

    @Test
    void karateKidIsNotKarateKid2() {
        differentFilms("The Karate Kid.m4v", "The Karate Kid 2.m4v");
        differentFilms("The Karate Kid.m4v", "The Karate Kid II.m4v");
        sameFilm("The Karate Kid 2.m4v", "The Karate Kid II.m4v");
    }

    @Test
    void killBillVolumesAreDifferentFilms() {
        differentFilms("Kill Bill Vol. 1.m4v", "Kill Bill Vol. 2.m4v");
        assertThat(parse("Kill Bill Vol. 1.m4v").partTag()).isNull();
        assertThat(parse("Kill Bill Vol. 1.m4v").comparisonTitle()).isEqualTo("kill bill vol 1");
    }

    @Test
    void alienIsTheSameFilmAsAlienDirectorsCut() {
        ParsedName plain = parse("Alien.m4v");
        ParsedName cut = parse("Alien Director's Cut.m4v");

        assertThat(cut.comparisonTitle()).isEqualTo("alien");
        assertThat(cut.comparisonTitle()).isEqualTo(plain.comparisonTitle());
        assertThat(cut.plainEdition()).isEqualTo("Director's Cut");
        assertThat(cut.title()).isEqualTo("Alien Director's Cut");
        assertThat(cut.editionTag()).isNull();
        assertThat(plain.plainEdition()).isNull();
    }

    @Test
    void everyPlainEditionWordIsRemovedFromTheComparisonAndNamedByItsCanonicalSpelling() {
        String[][] cases = {
            {"Director's Cut", "Director's Cut"}, {"Directors Cut", "Director's Cut"},
            {"Director’s Cut", "Director's Cut"}, {"Extended Edition", "Extended Edition"},
            {"Extended Cut", "Extended Cut"}, {"Extended", "Extended"}, {"Theatrical Cut", "Theatrical Cut"},
            {"Theatrical", "Theatrical"}, {"Unrated", "Unrated"}, {"Final Cut", "Final Cut"},
            {"Special Edition", "Special Edition"}, {"Remastered", "Remastered"}, {"IMAX", "IMAX"},
            {"Uncut", "Uncut"}, {"extended edition", "Extended Edition"}, {"SPECIAL  EDITION", "Special Edition"}
        };
        for (String[] c : cases) {
            ParsedName p = parse("Alien " + c[0] + ".m4v");
            assertThat(p.comparisonTitle()).as(c[0]).isEqualTo("alien");
            assertThat(p.plainEdition()).as(c[0]).isEqualTo(c[1]);
        }
    }

    @Test
    void theLongestEditionPhraseWinsAndTheFirstWordFoundIsTheNameEvenWhenSeveralAreRemoved() {
        ParsedName p = parse("Alien Extended Edition Unrated.m4v");

        assertThat(p.plainEdition()).isEqualTo("Extended Edition");
        assertThat(p.comparisonTitle()).isEqualTo("alien");
    }

    @Test
    void editionWordsInsideLongerWordsAreNotEditionWords() {
        ParsedName p = parse("Uncutting Edges.m4v");

        assertThat(p.comparisonTitle()).isEqualTo("uncutting edges");
        assertThat(p.plainEdition()).isNull();
    }

    @Test
    void aSequelMarkerIsNeverRemoved() {
        assertThat(parse("Dune Part II.m4v").comparisonTitle()).isEqualTo("dune part 2");
        assertThat(parse("Rocky 4 Extended.m4v").comparisonTitle()).isEqualTo("rocky 4");
    }

    @Test
    void accentsAreDroppedSoLeonIsLeon() {
        sameFilm("Léon.m4v", "Leon.m4v");
        assertThat(parse("Léon.m4v").title()).isEqualTo("Léon");
        assertThat(parse("Amélie.m4v").comparisonTitle()).isEqualTo("amelie");
    }

    @Test
    void punctuationRunsAndCaseDoNotMatterButWordsDo() {
        sameFilm("Mission: Impossible.m4v", "mission impossible.m4v");
        sameFilm("Spider-Man.m4v", "Spider Man.m4v");
        differentFilms("Spider-Man.m4v", "Spiderman.m4v");
    }

    @Test
    void theCuttingOfAnEditionTagDoesNotAffectTheComparisonTitle() {
        assertThat(parse("Alien {edition-Director's Cut}.m4v").comparisonTitle()).isEqualTo("alien");
    }
}
