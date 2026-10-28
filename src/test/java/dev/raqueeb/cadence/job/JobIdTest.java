package dev.raqueeb.cadence.job;

import static dev.raqueeb.cadence.harness.Assert.contains;
import static dev.raqueeb.cadence.harness.Assert.equal;
import static dev.raqueeb.cadence.harness.Assert.isTrue;
import static dev.raqueeb.cadence.harness.Assert.throws_;

import dev.raqueeb.cadence.harness.Test;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Job identity: shape, ordering and parsing. */
final class JobIdTest {

    @Test("a generated id has the documented length and alphabet")
    static void shape() {
        JobId id = JobId.generate();

        equal(JobId.LENGTH, id.text().length(), "the id length");
        for (char c : id.text().toCharArray()) {
            isTrue("0123456789ABCDEFGHJKMNPQRSTVWXYZ".indexOf(c) >= 0,
                    "'" + c + "' to be in the alphabet");
        }
    }

    @Test("the alphabet excludes the characters that are misread")
    static void alphabetAvoidsAmbiguity() {
        // The whole reason for Crockford base32 over plain base32.
        JobId id = JobId.generate();

        for (char ambiguous : new char[] {'I', 'L', 'O', 'U'}) {
            isTrue(id.text().indexOf(ambiguous) < 0,
                    "'" + ambiguous + "' to be absent from " + id);
        }
    }

    @Test("an id carries back the time it was minted at")
    static void timestampRoundTrips() {
        long when = 1_793_000_000_000L;

        equal(when, JobId.generate(when).createdAtMillis(), "the encoded creation time");
    }

    @Test("lexicographic order is creation order")
    static void sortsByCreationTime() {
        // The property the journal relies on to avoid a sequence column.
        JobId older = JobId.generate(1_700_000_000_000L);
        JobId newer = JobId.generate(1_800_000_000_000L);

        isTrue(older.compareTo(newer) < 0, "the older id to sort first");
        isTrue(newer.compareTo(older) > 0, "the newer id to sort last");
        isTrue(older.text().compareTo(newer.text()) < 0, "plain string order to agree");
    }

    @Test("sorting a shuffled batch recovers the order they were minted in")
    static void sortingRecoversMintOrder() {
        List<JobId> minted = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            minted.add(JobId.generate(1_700_000_000_000L + i));
        }

        List<JobId> shuffled = new ArrayList<>(minted);
        java.util.Collections.shuffle(shuffled, new java.util.Random(7));
        java.util.Collections.sort(shuffled);

        equal(minted, shuffled, "the sorted ids");
    }

    @Test("ids minted in the same millisecond are distinct")
    static void sameMillisecondDoesNotCollide() {
        // A random tail alone collides about once in a thousand here, and a
        // bulk submit mints hundreds of ids in one millisecond.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            seen.add(JobId.generate(1_700_000_000_000L).text());
        }

        equal(2_000, seen.size(), "the number of distinct ids");
    }

    @Test("ids minted in the same millisecond still ascend")
    static void sameMillisecondStillAscends() {
        JobId first = JobId.generate(1_700_000_000_000L);
        JobId second = JobId.generate(1_700_000_000_000L);

        isTrue(first.compareTo(second) < 0, "the first minted id to sort first");
    }

    @Test("parse accepts lower case")
    static void parseAcceptsLowerCase() {
        JobId id = JobId.generate();

        equal(id, JobId.parse(id.text().toLowerCase()), "the id read back in lower case");
    }

    @Test("parse accepts the characters Crockford treats as interchangeable")
    static void parseNormalisesAmbiguousCharacters() {
        // Someone reading an id off a screen may well type O for 0 or l for
        // 1. The alphabet exists so that still resolves.
        JobId zeroes = JobId.parse("0000000000000000");

        equal(zeroes, JobId.parse("OOOOOOOOOOOOOOOO"), "letter O read as zero");
        equal(JobId.parse("1111111111111111"), JobId.parse("IIIIIIIIIIIIIIII"), "I read as one");
        equal(JobId.parse("1111111111111111"), JobId.parse("llllllllllllllll"), "l read as one");
    }

    @Test("parse trims surrounding whitespace")
    static void parseTrims() {
        JobId id = JobId.generate();

        equal(id, JobId.parse("  " + id.text() + "\n"), "an id pasted with whitespace");
    }

    @Test("the wrong length is rejected, with the length given")
    static void wrongLengthIsRejected() {
        IllegalArgumentException error =
                throws_(IllegalArgumentException.class, () -> JobId.parse("ABC"));

        contains(error.getMessage(), "16 characters", "the message states the expected length");
        contains(error.getMessage(), "3 characters", "the message states what was given");
    }

    @Test("a character outside the alphabet is rejected, and named")
    static void badCharacterIsRejected() {
        IllegalArgumentException error =
                throws_(IllegalArgumentException.class, () -> JobId.parse("ABCDEFGH-JKLMNOP"));

        contains(error.getMessage(), "'-'", "the message quotes the offending character");
    }

    @Test("null is rejected")
    static void nullIsRejected() {
        throws_(IllegalArgumentException.class, () -> JobId.parse(null));
        throws_(IllegalArgumentException.class, () -> new JobId(null));
    }

    @Test("a negative creation time is rejected")
    static void negativeTimeIsRejected() {
        throws_(IllegalArgumentException.class, () -> JobId.generate(-1));
    }

    @Test("the short form is the entropy-carrying tail")
    static void shortFormIsTheTail() {
        JobId id = JobId.generate();

        equal(6, id.shortForm().length(), "the short form length");
        isTrue(id.text().endsWith(id.shortForm()), "the short form to be a suffix");
    }

    @Test("an id equals itself read back from its text")
    static void roundTripsThroughText() {
        JobId id = JobId.generate();

        equal(id, JobId.parse(id.toString()), "the id round-tripped through its text");
        equal(id.hashCode(), JobId.parse(id.toString()).hashCode(), "the hash");
    }
}
