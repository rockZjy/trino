/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.server;

import com.google.common.base.StandardSystemProperty;
import com.google.common.collect.ImmutableSet;
import com.sun.management.UnixOperatingSystemMXBean;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import org.joda.time.DateTime;

import java.lang.Runtime.Version;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.lang.String.format;

final class TrinoSystemRequirements
{
    private static final int MIN_FILE_DESCRIPTORS = 4096;
    private static final int RECOMMENDED_FILE_DESCRIPTORS = 8192;
    private static boolean DEV_ON_WINDOWS = true;

    private TrinoSystemRequirements() {}

    public static void verifyJvmRequirements()
    {
        verifyJavaVersion();
        verify64BitJvm();
        verifyOsArchitecture();
        verifyByteOrder();
        verifyUsingG1Gc();
        verifyFileDescriptor();
        verifySlice();
    }

    private static void verify64BitJvm()
    {
        String dataModel = System.getProperty("sun.arch.data.model");
        if (!"64".equals(dataModel)) {
            failRequirement("Trino requires a 64-bit JVM (found %s)", dataModel);
        }
    }

    private static void verifyByteOrder()
    {
        ByteOrder order = ByteOrder.nativeOrder();
        if (!order.equals(ByteOrder.LITTLE_ENDIAN)) {
            failRequirement("Trino requires a little endian platform (found %s)", order);
        }
    }

    private static void verifyOsArchitecture()
    {
        String osName = StandardSystemProperty.OS_NAME.value();
        String osArch = StandardSystemProperty.OS_ARCH.value();
        if ("Linux".equals(osName)) {
            if (!ImmutableSet.of("amd64", "aarch64", "ppc64le").contains(osArch)) {
                failRequirement("Trino requires amd64, aarch64, or ppc64le on Linux (found %s)", osArch);
            }
            if ("aarch64".equals(osArch)) {
                warnRequirement("Support for the ARM architecture is experimental");
            }
            else if ("ppc64le".equals(osArch)) {
                warnRequirement("Support for the POWER architecture is experimental");
            }
        }
        else if ("Mac OS X".equals(osName)) {
            if (!"x86_64".equals(osArch)) {
                failRequirement("Trino requires x86_64 on Mac OS X (found %s)", osArch);
            }
        }
        else {
            if(!DEV_ON_WINDOWS){
                failRequirement("Trino requires Linux or Mac OS X (found %s)", osName);
            }
        }
    }

    private static void verifyJavaVersion()
    {
        Version required = Version.parse("11.0.11");
        if (Runtime.version().compareTo(required) < 0) {
            failRequirement("Trino requires Java %s at minimum (found %s)", required, Runtime.version());
        }
    }

    private static void verifyUsingG1Gc()
    {
        try {
            List<String> garbageCollectors = ManagementFactory.getGarbageCollectorMXBeans().stream()
                    .map(GarbageCollectorMXBean::getName)
                    .collect(toImmutableList());

            if (garbageCollectors.stream().noneMatch(name -> name.toUpperCase(Locale.US).startsWith("G1 "))) {
                warnRequirement("Current garbage collectors are %s. Trino recommends the G1 garbage collector.", garbageCollectors);
            }
        }
        catch (RuntimeException e) {
            // This should never happen since we have verified the OS and JVM above
            failRequirement("Cannot read garbage collector information: %s", e);
        }
    }

    private static void verifyFileDescriptor()
    {
        OptionalLong maxFileDescriptorCount = getMaxFileDescriptorCount();
        if (maxFileDescriptorCount.isEmpty()) {
            // This should never happen since we have verified the OS and JVM above
            if(DEV_ON_WINDOWS){
                maxFileDescriptorCount = OptionalLong.of(MIN_FILE_DESCRIPTORS);
            }else{
                failRequirement("Cannot read OS file descriptor limit");
            }
        }
        if (maxFileDescriptorCount.getAsLong() < MIN_FILE_DESCRIPTORS) {
            failRequirement("Trino requires at least %s file descriptors (found %s)", MIN_FILE_DESCRIPTORS, maxFileDescriptorCount.getAsLong());
        }
        if (maxFileDescriptorCount.getAsLong() < RECOMMENDED_FILE_DESCRIPTORS) {
            warnRequirement("Current OS file descriptor limit is %s. Trino recommends at least %s", maxFileDescriptorCount.getAsLong(), RECOMMENDED_FILE_DESCRIPTORS);
        }
    }

    private static OptionalLong getMaxFileDescriptorCount()
    {
        return Stream.of(ManagementFactory.getOperatingSystemMXBean())
                .filter(UnixOperatingSystemMXBean.class::isInstance)
                .map(UnixOperatingSystemMXBean.class::cast)
                .mapToLong(UnixOperatingSystemMXBean::getMaxFileDescriptorCount)
                .findFirst();
    }

    private static void verifySlice()
    {
        Slice slice = Slices.wrappedBuffer(new byte[5]);
        slice.setByte(4, 0xDE);
        slice.setByte(3, 0xAD);
        slice.setByte(2, 0xBE);
        slice.setByte(1, 0xEF);
        if (slice.getInt(1) != 0xDEADBEEF) {
            failRequirement("Slice library produced an unexpected result");
        }
    }

    /**
     * Perform a sanity check to make sure that the year is reasonably current, to guard against
     * issues in third party libraries.
     */
    public static void verifySystemTimeIsReasonable()
    {
        int currentYear = DateTime.now().year().get();
        if (currentYear < 2021) {
            failRequirement("Trino requires the system time to be current (found year %s)", currentYear);
        }
    }

    private static void failRequirement(String format, Object... args)
    {
        System.err.println("ERROR: " + format(format, args));
        System.exit(100);
    }

    private static void warnRequirement(String format, Object... args)
    {
        System.err.println("WARNING: " + format(format, args));
    }
}
