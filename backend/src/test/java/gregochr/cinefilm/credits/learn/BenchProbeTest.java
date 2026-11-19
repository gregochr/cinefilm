package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BenchProbeTest {

    @Test
    void theSystemProbeReadsTheRealMachine() {
        BenchProbe probe = BenchProbe.system();

        long cpu = probe.cpuNanos().getAsLong();
        long wall = probe.wallNanos().getAsLong();

        assertThat(cpu).isPositive();
        assertThat(probe.cpuNanos().getAsLong()).isGreaterThanOrEqualTo(cpu);
        assertThat(probe.wallNanos().getAsLong()).isGreaterThanOrEqualTo(wall);
        assertThat(probe.maxHeapBytes().getAsLong()).isEqualTo(Runtime.getRuntime().maxMemory());
    }

    @Test
    void theOnnxVersionComesFromTheJarWithoutLoadingTheNativeLibrary() {
        assertThat(BenchProbe.system().onnxVersion().get()).matches("\\d+\\.\\d+\\.\\d+");
    }
}
