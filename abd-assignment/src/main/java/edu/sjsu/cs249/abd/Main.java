package edu.sjsu.cs249.abd;

import edu.sjsu.cs249.abd.ABDServiceGrpc.ABDServiceBlockingStub;
import edu.sjsu.cs249.abd.Grpc.EnableRequest;
import edu.sjsu.cs249.abd.Grpc.ExitRequest;
import edu.sjsu.cs249.abd.Grpc.NameRequest;
import edu.sjsu.cs249.abd.Grpc.Read1Request;
import edu.sjsu.cs249.abd.Grpc.Read1Response;
import edu.sjsu.cs249.abd.Grpc.Read2Request;
import edu.sjsu.cs249.abd.Grpc.WriteRequest;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.AbstractStub;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Visibility;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static edu.sjsu.cs249.abd.CliUtil.debug;
import static edu.sjsu.cs249.abd.CliUtil.fatal;
import static edu.sjsu.cs249.abd.CliUtil.info;
import static java.util.concurrent.TimeUnit.SECONDS;

public class Main {
    public static void main(String[] args) {
        System.exit(new CliUtil(new Cli()).execute(args));
    }

    @Command(name = "ABD", subcommands = {ClientCli.class, ServerCli.class}, mixinStandardHelpOptions = true)
    static class Cli {
        @Option(names = "--debug", description = "show debug messages", defaultValue = "false", showDefaultValue = Visibility.ALWAYS)
        private void setDebug(boolean enabled) {
            CliUtil.enableDebug(enabled);
        }

        @Option(names = "--timestamp", description = "show timestamps", defaultValue = "false", showDefaultValue = Visibility.ALWAYS)
        private void setTimestamp(boolean enabled) {
            CliUtil.enableTimestamp(enabled);
        }
    }

    @Command(name = "server", mixinStandardHelpOptions = true, description = "start an ADB server.")
    static class ServerCli implements Callable<Integer> {
        @Parameters(index = "0", description = "host:port listen on.")
        int serverPort;

        @Override
        public Integer call() {
            System.out.printf("you need to write the code to serve on port %s\n", serverPort);
            return 0;
        }
    }

    @Command(name = "client", mixinStandardHelpOptions = true, description = "start an ADB client.")
    static class ClientCli {
        @SuppressWarnings("rawtypes")
        final private Map<AbstractStub, String> stubNames = new HashMap<>();
        List<ABDServiceBlockingStub> stubs;
        int majority;
        BitSet bottomMajority = new BitSet();
        BitSet bottomMinority = new BitSet();
        BitSet topMajority = new BitSet();
        BitSet topMinority = new BitSet();
        BitSet innerMajority = new BitSet();
        BitSet outerMinority = new BitSet();
        BitSet all = BitSet.valueOf(new long[]{0xffffffffffffffffL});
        String serverPorts;

        static String[] concat(String[] first, String rest) {
            return Stream.concat(Arrays.stream(first), Arrays.stream(rest.split(" "))).toArray(String[]::new);
        }

        private static void runCommand(String[] cmd, String rest, String expectedResult) throws IOException, InterruptedException {
            var torun = concat(cmd, rest);
            debug("running {0}", String.join(" ", torun));
            var proc = new ProcessBuilder(torun).redirectErrorStream(true).start();
            var collector = new OutputCollector(proc);
            collector.start();
            if (!proc.waitFor(10, SECONDS)) {
                fatal("execution timeout:", String.join(" ", torun));
            }
            var result = collector.collect();
            result = result.stripTrailing();
            if (!result.endsWith(expectedResult)) {
                var lastNl = result.indexOf('\n');
                fatal("expected {0} found {1}", expectedResult, result.substring(lastNl + 1));
            }
        }

        @Parameters(description = "comma separated list of servers to use.")
        void setServerPorts(String serverPorts) {
            try {
                this.serverPorts = serverPorts;
                var parts = serverPorts.split(",");
                stubs = new ArrayList<>(parts.length);
                var nameRequest = NameRequest.newBuilder().build();
                for (int i = 0; i < parts.length; i++) {
                    stubs.add(ABDServiceGrpc.newBlockingStub(ManagedChannelBuilder.forTarget(parts[i]).usePlaintext().build()));
                    try {
                        var rsp = stubs.get(i).withDeadlineAfter(2, SECONDS).name(nameRequest);
                        stubNames.put(stubs.get(i), rsp.getName());
                    } catch (StatusRuntimeException e) {
                        stubNames.put(stubs.get(i), "none");
                    }
                }

                majority = stubs.size() / 2 + 1;
                bottomMajority.set(0, majority);
                bottomMinority.set(0, majority - 1);
                topMajority.set((stubs.size() - majority), stubs.size());
                topMinority.set((stubs.size() - majority) + 1, stubs.size());
                var innerStart = (stubs.size() - majority) / 2;
                innerMajority.set(innerStart, innerStart + majority);
                outerMinority.set(0, innerStart);
                outerMinority.set(innerStart + majority, stubs.size());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @SuppressWarnings("rawtypes")
        private String ppStub(AbstractStub stub) {
            return MessageFormat.format("{0}({1})", stub.getChannel().authority(), stubNames.get(stub));
        }

        private String ppMissing(Set<ABDServiceBlockingStub> present) {
            var all = new HashSet<>(stubs);
            all.removeAll(present);
            return ppPresent(all);
        }

        private String ppPresent(Set<ABDServiceBlockingStub> all) {
            return all.stream().map(this::ppStub).collect(Collectors.joining(","));
        }

        @Command
        public void testServerProcesses() {
            info("checking that server operations are working");
            checkEnable(1000);
            checkEnable(1001);
        }

        private void checkEnable(long address) {
            debug("===> seeeding initial write");
            checkWrite(address, 10, 1, true);

            debug("everything disabled but read1");
            enableRequests(false, true, false);
            checkWrite(address, 11, 1, false);
            checkRead2(address, 12, 2, false);
            checkRead1(address, 10, 1, true);

            debug("everything disabled but read2");
            enableRequests(false, false, true);
            checkRead1(address, 10, 1, false);
            checkRead2(address, 13, 2, true);
            checkWrite(address, 14, 4, false);

            debug("everything enabled");
            enableRequests(true, true, true);
            checkRead1(address, 13, 2, true);
            checkRead2(address, 15, 5, true);
            checkRead1(address, 15, 5, true);
            checkWrite(address, 16, 6, true);
            checkRead1(address, 16, 6, true);

            info("enable is working on all processeses");
        }

        /* return null on exception */
        private <R> Function<ABDServiceBlockingStub, R> noe(Function<ABDServiceBlockingStub, R> func) {
            return o -> {
                try {
                    return func.apply(o);
                } catch (Exception e) {
                    debug("Skipping {0}", o.getChannel().authority());
                }
                return null;
            };
        }

        private void checkWrite(long address, long label, long value, boolean expectSuccess) {
            debug("== checking write");
            var wreq = WriteRequest.newBuilder().setAddr(address).setLabel(label).setValue(value).build();
            var results = stubs.stream().parallel().map(noe(s -> Map.entry(s, s.withDeadlineAfter(2, SECONDS).write(wreq)))).filter(Objects::nonNull).collect(Collectors.toMap(Entry::getKey, Entry::getValue));
            if (expectSuccess) {
                if (results.size() != stubs.size()) {
                    fatal("these processes do not support basic write: {0}", ppMissing(results.keySet()));
                }
            } else {
                if (results.size() != 0) {
                    fatal("these processes ignored write disable: {0}", ppPresent(results.keySet()));
                }
            }
        }

        private void checkRead2(long address, long label, long value, boolean expectSuccess) {
            debug("== checking read2");
            var read2 = Read2Request.newBuilder().setAddr(address).setLabel(label).setValue(value).build();
            var results = stubs.stream().parallel().map(noe(s -> Map.entry(s, s.withDeadlineAfter(2, SECONDS).read2(read2)))).filter(Objects::nonNull).collect(Collectors.toMap(Entry::getKey, Entry::getValue));
            if (expectSuccess) {
                if (results.size() != stubs.size()) {
                    fatal("these processes do not support basic read2: {0}", ppMissing(results.keySet()));
                }
            } else {
                if (results.size() != 0) {
                    fatal("these processes ignored read2 disable: {0}", ppPresent(results.keySet()));
                }
            }
        }

        private void checkRead1(long addr, long expectedLabel, long expectedValue, boolean expectResponse) {
            debug("== checking read1");
            var read1 = Read1Request.newBuilder().setAddr(addr).build();
            var results = stubs.stream().parallel().map(noe(s -> Map.entry(s, s.withDeadlineAfter(2, SECONDS).read1(read1)))).filter(Objects::nonNull).collect(Collectors.toMap(Entry::getKey, Entry::getValue));
            if (expectResponse) {
                if (results.size() != stubs.size()) {
                    fatal("these processes do not support read1 {0}", ppMissing(results.keySet()));
                }
                for (var entry : results.entrySet()) {
                    Read1Response read1rsp = entry.getValue();
                    if (read1rsp.getRc() == 1 || read1rsp.getLabel() != expectedLabel || read1rsp.getValue() != expectedValue) {
                        fatal("{0} returned bad response: {1}", entry.getKey().getChannel().authority(), read1rsp);
                    }
                }
            } else {
                if (results.size() != 0) {
                    fatal("these processes did not disable read1 {0}", ppPresent(results.keySet()));
                }
            }
        }

        private void enableRequests(boolean write, boolean read1, boolean read2) {
            debug("==enabling request");
            var enable = EnableRequest.newBuilder().setWrite(write).setRead1(read1).setRead2(read2).build();
            var results = stubs.stream().parallel().map(noe(s -> Map.entry(s, s.withDeadlineAfter(2, SECONDS).enableRequests(enable)))).filter(Objects::nonNull).collect(Collectors.toMap(Entry::getKey, Entry::getValue));
            if (results.size() != stubs.size()) {
                fatal("these processes do not support enable: {0}", ppMissing(results.keySet()));
            }
        }

        @Command
        public void shutdownProcesses() {
            var exitRequest = ExitRequest.newBuilder().build();
            for (var s : stubs) {
                s.exit(exitRequest);
            }
        }

        @Command
        public void testClient(@Parameters(description = "commandline to run the client", paramLabel = "command_with_args", arity = "1..*", index = "*") String[] cmd) throws InterruptedException, IOException {
            enableWrites(all);
            info("testing basic read and write");
            cmd = concat(cmd, serverPorts);
            runCommand(cmd, "write 17 1717", "success");
            Read1Response rsp = getMaxLabel(17);
            runCommand(cmd, "read 17", "1717(" + rsp.getLabel() + ")");
            runCommand(cmd, "read 18", "failed");
            info("PASSED \u2705");

            info("testing with minority write failures");
            enableWrites(topMajority);
            runCommand(cmd, "write 117 1717", "success");
            rsp = getMaxLabel(117);
            runCommand(cmd, "read 117", "1717(" + rsp.getLabel() + ")");
            runCommand(cmd, "read 118", "failed");
            runCommand(cmd, "read 118", "failed");
            info("PASSED \u2705");
            info("testing with majority write failures");
            enableWrites(topMinority);
            runCommand(cmd, "write 217 2717", "failure");
            info("PASSED \u2705");
            info("testing with minority read failures");
            // note, this will force the client to see some of the failed write
            enableRead(topMajority, bottomMajority);
            rsp = getMaxLabel(217);
            runCommand(cmd, "read 217", "2717(" + rsp.getLabel() + ")");
            info("PASSED \u2705");
            info("test with single write");
            var writeReq = WriteRequest.newBuilder().setAddr(218).setLabel(2).setValue(3).build();
            var lastStub = stubs.get(stubs.size() - 1);
            lastStub.withDeadlineAfter(3, SECONDS).write(writeReq);
            enableRead(topMajority, bottomMinority);
            runCommand(cmd, "read 218", "failed");
            enableRead(bottomMajority, topMinority);
            runCommand(cmd, "read 218", "failed");
            enableRead(bottomMajority, innerMajority);
            runCommand(cmd, "read 218", "3(2)");
            info("PASSED \u2705");
        }

        Read1Response getMaxLabel(long address) {
            debug("== getMaxLabel");
            return stubs.stream().parallel().map(noe(s -> s.withDeadlineAfter(2, SECONDS).read1(Read1Request.newBuilder().setAddr(address).build()))).filter(Objects::nonNull).max(Comparator.comparingLong(Read1Response::getLabel)).get();

        }

        void enableWrites(BitSet mask) {
            debug("enabling writes on {0}", mask);
            for (int i = 0; i < stubs.size(); i++) {
                var req = EnableRequest.newBuilder().setWrite(mask.get(i)).setRead1(true).setRead2(true).build();
                try {
                    stubs.get(i).withDeadlineAfter(2, SECONDS).enableRequests(req);
                } catch (Exception e) {
                    fatal("failed enabling writes on {0}", stubs.get(i).getChannel().authority());
                }
            }
        }

        private void enableRead(BitSet r1Mask, BitSet r2Mask) {
            debug("enabling read on {0} {1}", r1Mask, r2Mask);
            for (int i = 0; i < stubs.size(); i++) {
                var req = EnableRequest.newBuilder().setWrite(true).setRead1(r1Mask.get(i)).setRead2(r2Mask.get(i)).build();
                try {
                    stubs.get(i).enableRequests(req);
                } catch (Exception e) {
                    fatal("failed enabling reads on {0}", stubs.get(i).getChannel().authority());
                }
            }
        }

        static class OutputCollector extends Thread {
            private final InputStream is;
            private String result;

            OutputCollector(Process proc) {
                is = proc.getInputStream();
            }

            public void run() {
                try {
                    result = new String(is.readAllBytes());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            String collect() throws InterruptedException {
                this.join();
                return result;
            }
        }
    }
}
