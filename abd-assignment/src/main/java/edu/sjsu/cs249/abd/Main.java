package edu.sjsu.cs249.abd;

import edu.sjsu.cs249.abd.Grpc.Read1Response;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Visibility;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static edu.sjsu.cs249.abd.CliUtil.debug;
import static edu.sjsu.cs249.abd.CliUtil.fatal;
import static edu.sjsu.cs249.abd.CliUtil.info;

public class Main {
    public static void main(String[] args) {
        System.exit(new CliUtil(new Cli()).execute(args));
    }

    @Command(name = "ABD", subcommands = {ClientCli.class}, mixinStandardHelpOptions = true)
    static class Cli {
        @Option(names="--debug", description = "show debug messages", defaultValue = "false", showDefaultValue = Visibility.ALWAYS)
        private void setDebug(boolean enabled) {
            CliUtil.enableDebug(enabled);
        }
        @Option(names="--timestamp", description = "show timestamps", defaultValue = "false", showDefaultValue = Visibility.ALWAYS)
        private void setTimestamp(boolean enabled) {
            CliUtil.enableTimestamp(enabled);
        }
    }

    @Command(name = "client", mixinStandardHelpOptions = true, description = "start and ADB client.")
    static class ClientCli {
        ABDServiceGrpc.ABDServiceBlockingStub[] stubs;
        int majority;

        BitSet bottomMajority = new BitSet();
        BitSet bottomMinority = new BitSet();
        BitSet topMajority = new BitSet();
        BitSet topMinority = new BitSet();
        BitSet innerMajority = new BitSet();
        BitSet outerMinority = new BitSet();
        BitSet all = BitSet.valueOf(new long[] { 0xffffffffffffffffL });


        @Parameters(index = "0", description = "comma separated list of servers to use.")
        void setServerPorts(String serverPorts) {
            this.serverPorts = serverPorts;
            var parts = serverPorts.split(",");
            stubs = new ABDServiceGrpc.ABDServiceBlockingStub[parts.length];
            for (int i = 0; i < parts.length; i++) {
                stubs[i] = ABDServiceGrpc.newBlockingStub(ManagedChannelBuilder.forTarget(parts[i]).usePlaintext().build());
            }

            bottomMajority.set(0, majority);
            bottomMinority.set(0, majority-1);
            topMajority.set((stubs.size() - majority), stubs.size());
            topMinority.set((stubs.size() - majority)+1, stubs.size());
            var innerStart = (stubs.size() - majority)/2;
            innerMajority.set(innerStart, innerStart+majority);
            outerMinority.set(0, innerStart);
            outerMinority.set(innerStart+majority, stubs.size());
        }

        String serverPorts;

        static String[] concat(String[] first, String rest) {
            return Stream.concat(Arrays.stream(first), Arrays.stream(rest.split(" ")))
                    .toArray(String[]::new);
        }

        static class OutputCollector extends Thread {
            private InputStream is;
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

        @Command
        public void testClient(@Parameters(description = "commandline to run the client", index = "*") String[] cmd)
                throws InterruptedException, IOException {
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
            runCommand(cmd, "read 117", "1717("+rsp.getLabel()+")");
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
            runCommand(cmd, "read 217", "2717("+rsp.getLabel()+")");
            info("PASSED \u2705");
            info("test with single write");
            var writeReq = WriteRequest.newBuilder().setAddr(218).setLabel(2).setValue(3).build();
            var lastStub = stubs.get(stubs.size()-1);
            lastStub.withDeadlineAfter(3, TimeUnit.SECONDS).write(writeReq);
            enableRead(topMajority, bottomMinority);
            runCommand(cmd, "read 218", "failed");
            enableRead(bottomMajority, topMinority);
            runCommand(cmd, "read 218", "failed");
            enableRead(bottomMajority, innerMajority);
            runCommand(cmd, "read 218", "3(2)");
            info("PASSED \u2705");
        }

        private static void runCommand(String[] cmd, String rest, String expectedResult)
                throws IOException, InterruptedException {
            var torun = concat(cmd, rest);
            debug("running {0}", String.join(" ", torun));
            var proc = new ProcessBuilder(torun).redirectErrorStream(true).start();
            var collector = new OutputCollector(proc);
            collector.start();
            if (!proc.waitFor(10, TimeUnit.SECONDS)) {
                fatal("execution timeout:", String.join(" ", torun));
            }
            var result = collector.collect();
            result = result.stripTrailing();
            if (!result.endsWith(expectedResult)) {
                var lastNl = result.indexOf('\n');
                fatal("expected {0} found {1}", expectedResult, result.substring(lastNl + 1));
            }
        }
        long getMaxLabel(long address) {

        }
    }
}
