package gregochr.cinefilm.credits.learn;

import ai.onnxruntime.OrtEnvironment;
import java.lang.management.ManagementFactory;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * What the encoder benchmark reads from the machine, as one seam so a test gets exact figures.
 *
 * @param cpuNanos the process's CPU time, native threads included
 * @param wallNanos a monotonic wall clock
 * @param maxHeapBytes the JVM's heap limit
 * @param onnxVersion the ONNX Runtime library's version
 */
record BenchProbe(LongSupplier cpuNanos, LongSupplier wallNanos, LongSupplier maxHeapBytes,
                  Supplier<String> onnxVersion) {

    /**
     * The real machine. The CPU time is {@code com.sun.management.OperatingSystemMXBean.getProcessCpuTime()}, which
     * counts the library's native threads. The version is read from the jar's manifest without loading the native
     * library.
     *
     * @return the probe
     */
    static BenchProbe system() {
        com.sun.management.OperatingSystemMXBean os =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        return new BenchProbe(os::getProcessCpuTime, System::nanoTime, Runtime.getRuntime()::maxMemory,
            () -> Objects.requireNonNullElse(OrtEnvironment.class.getPackage().getImplementationVersion(), "unknown"));
    }
}
