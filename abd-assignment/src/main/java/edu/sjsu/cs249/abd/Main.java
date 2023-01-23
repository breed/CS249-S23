package edu.sjsu.cs249.abd;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

public class Main {

    public static void main(String[] args) {
        System.exit(new CommandLine(new Cli()).execute(args));
    }

    @Command(subcommands = {ServerCli.class, ClientCli.class})
    static class Cli {}

    @Command(name = "server", mixinStandardHelpOptions = true, description = "start an ABD register server.")
    static class ServerCli implements Callable<Integer> {
        @Parameters(index = "0", description = "host:port listen on.")
        int serverPort;

        @Override
        public Integer call() throws Exception {
            System.out.printf("will contact %s\n", serverPort);
            return 0;
        }
    }

    @Command(name = "client", mixinStandardHelpOptions = true, description = "start and ADB client.")
    static class ClientCli {
        @Parameters(index = "0", description = "comma separated list of servers to use.")
        String serverPorts;

        @Command
        public void read(@Parameters(paramLabel = "register") String register) {
            System.out.printf("Going to read %s from %s\n", register, serverPorts);
        }

        @Command
        public void write(@Parameters(paramLabel = "register") String register,
                          @Parameters(paramLabel = "value") String value) {
            System.out.printf("Going to write %s to %s on %s\n", value, register, serverPorts);
        }
    }
}
