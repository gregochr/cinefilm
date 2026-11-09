package gregochr.cinefilm.transcode;

import gregochr.cinefilm.TestData;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Hand-driven stand-ins for everything the session manager touches: no process, no disk, no sleep, no real time. */
final class SessionFakes {

    private SessionFakes() {
    }

    /** A clock that only moves when the test says so. */
    static final class MutableClock extends Clock {
        private Instant now = TestData.NOW;

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return TestData.ZONE;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** An encoder that is alive until it is stopped; tests report its exit by calling its listener. */
    static final class FakeEncoder implements EncoderProcess {
        boolean alive = true;
        int stops;

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public OptionalInt exitCode() {
            return alive ? OptionalInt.empty() : OptionalInt.of(143);
        }

        @Override
        public void stop() {
            alive = false;
            stops++;
        }
    }

    /** Records every plan it is asked to run and hands out {@link FakeEncoder}s. */
    static final class FakeTranscoder implements Transcoder {
        EncoderMode mode = EncoderMode.VAAPI;
        IOException failure;
        final List<TranscodePlan> plans = new ArrayList<>();
        final List<FakeEncoder> encoders = new ArrayList<>();
        final List<EncoderListener> listeners = new ArrayList<>();

        @Override
        public EncoderMode mode() {
            return mode;
        }

        @Override
        public EncoderProcess start(TranscodePlan plan, EncoderListener listener) throws IOException {
            if (failure != null) {
                throw failure;
            }
            FakeEncoder encoder = new FakeEncoder();
            plans.add(plan);
            encoders.add(encoder);
            listeners.add(listener);
            return encoder;
        }

        TranscodePlan lastPlan() {
            return plans.get(plans.size() - 1);
        }

        EncoderListener lastListener() {
            return listeners.get(listeners.size() - 1);
        }
    }

    /** A disk that is a set of paths. */
    static final class FakeFiles implements SegmentFiles {
        final Set<Path> existing = new LinkedHashSet<>();
        final List<Path> deletedTrees = new ArrayList<>();
        final List<Path> directories = new ArrayList<>();
        final Map<Path, Long> sizes = new HashMap<>();
        int existsCalls;

        void add(Path file) {
            existing.add(file);
        }

        @Override
        public boolean exists(Path file) {
            existsCalls++;
            return existing.contains(file);
        }

        @Override
        public void delete(Path file) {
            existing.remove(file);
        }

        @Override
        public void deleteFrom(Path rungDir, int firstIndex) {
            existing.removeIf(file -> rungDir.equals(file.getParent()) && index(file) >= firstIndex);
        }

        @Override
        public void deleteTree(Path dir) {
            existing.removeIf(file -> file.startsWith(dir));
            deletedTrees.add(dir);
        }

        @Override
        public List<Path> directories(Path dir) {
            return directories;
        }

        @Override
        public long size(Path dir) {
            return sizes.getOrDefault(dir, 0L);
        }

        private static int index(Path file) {
            String name = file.getFileName().toString();
            return Integer.parseInt(name.substring(4, name.length() - 3));
        }
    }

    /** Runs a script while "waiting", then checks the condition once, and keeps the condition for the test. */
    static final class ScriptedWaiter implements SegmentWaiter {
        Runnable whileWaiting = () -> { };
        final List<Duration> timeouts = new ArrayList<>();
        BooleanSupplier condition;

        @Override
        public void await(BooleanSupplier condition, Duration timeout) {
            this.condition = condition;
            timeouts.add(timeout);
            whileWaiting.run();
            condition.getAsBoolean();
        }
    }

    /** Remembers the one task scheduled; the test runs it. */
    static final class FakeScheduler implements SessionScheduler {
        Duration period;
        Runnable task;
        boolean cancelled;

        @Override
        public Runnable scheduleEvery(Duration period, Runnable task) {
            this.period = period;
            this.task = task;
            return () -> cancelled = true;
        }
    }
}
