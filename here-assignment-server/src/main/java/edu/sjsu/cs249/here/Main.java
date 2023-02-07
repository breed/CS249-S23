package edu.sjsu.cs249.here;

import edu.sjsu.cs249.iamhere.Grpc;
import edu.sjsu.cs249.iamhere.HereServiceGrpc;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.net.SocketAddress;
import java.util.concurrent.Callable;

import static io.grpc.Grpc.TRANSPORT_ATTR_REMOTE_ADDR;

public class Main {

    @Command(subcommands = { ClientCli.class, ServerCli.class})
    static class Cli {}
    @Command(name = "client", mixinStandardHelpOptions = true, description = "register attendance for class.")
    static class ClientCli implements Callable<Integer> {
        @Parameters(index = "0", description = "host:port to connect to.")
        String serverPort;

        @Override
        public Integer call() throws Exception {
            System.out.printf("will contact %s\n", serverPort);
            var lastColon = serverPort.lastIndexOf(':');
            var host = serverPort.substring(0, lastColon);
            var port = Integer.parseInt(serverPort.substring(lastColon+1));
            var channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
            var stub = HereServiceGrpc.newBlockingStub(channel);
            System.out.println("here says " + stub.hello(Grpc.HelloRequest.newBuilder().setName("ben").build()).getMessage());
            var rsp = stub.here(Grpc.HereRequest.newBuilder().setName("ben reed").setId(23432).setCode("hello world!").build());
            System.out.println("RC = " + rsp.getRc() + " " + rsp.getMessage());
            return 0;
        }
    }

    @Command(name = "server", mixinStandardHelpOptions = true, description = "attendance server for class.")
    static class ServerCli implements Callable<Integer> {
        @Parameters(index = "0", description = "port to connect listen on.")
        int port;

        static class MyHereService extends HereServiceGrpc.HereServiceImplBase {

            @Override
            public void here(Grpc.HereRequest request, StreamObserver<Grpc.HereResponse> responseObserver) {
                int rc = 0;
                String msg = "welcome to class!";
                System.out.println("Connection from " + REMOTE_ADDR.get());
                System.out.println(request.getName() + " " + request.getId());
                System.out.println(request.getCode());
                if (request.getId() < 10000000 || request.getId() > 90000000) {
                    rc = 1;
                    msg = "id looks wrong";
                } else if (request.getName() == null || request.getName().length() < 4) {
                    rc = 1;
                    msg = "name looks wrong";
                } else if (!request.getCode().contains("main(")) {
                    rc = 1;
                    msg = "code is missing public static main";

                }
                System.out.println("--------------");
                responseObserver.onNext(Grpc.HereResponse.newBuilder().setRc(rc).setMessage(msg).build());
                responseObserver.onCompleted();;
            }

            @Override
            public void hello(Grpc.HelloRequest request, StreamObserver<Grpc.HelloResponse> responseObserver) {
                responseObserver.onNext(Grpc.HelloResponse.newBuilder().setMessage("hello " + request.getName() + "!").build());
                responseObserver.onCompleted();
            }
        }

        static private Context.Key<SocketAddress> REMOTE_ADDR = Context.key("REMOTE_ADDR");
        @Override
        public Integer call() throws Exception {
            System.out.printf("listening on %d\n", port);
            var server = ServerBuilder.forPort(port).intercept(new ServerInterceptor() {
                @Override
                public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> sc, Metadata h,
                                                                             ServerCallHandler<ReqT, RespT> next) {
                    var remote = sc.getAttributes().get(TRANSPORT_ATTR_REMOTE_ADDR);
                    return Contexts.interceptCall(Context.current().withValue(REMOTE_ADDR, remote),
                            sc, h, next);
                }
            }).addService(new MyHereService()).build();
            server.start();
            server.awaitTermination();
            return 0;
        }
    }
    public static void main(String[] args) {
        System.exit(new CommandLine(new Cli()).execute(args));
    }
}