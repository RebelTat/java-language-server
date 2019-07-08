package org.javacs.debug;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.*;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DebugAdapter {
    private static final Gson gson = new Gson();

    private static String readHeader(InputStream client) {
        var line = new StringBuilder();
        for (var next = read(client); true; next = read(client)) {
            if (next == '\r') {
                var last = read(client);
                assert last == '\n';
                break;
            }
            line.append(next);
        }
        return line.toString();
    }

    private static int parseHeader(String header) {
        var contentLength = "Content-Length: ";
        if (header.startsWith(contentLength)) {
            var tail = header.substring(contentLength.length());
            var length = Integer.parseInt(tail);
            return length;
        }
        return -1;
    }

    private static char read(InputStream client) {
        try {
            var c = client.read();
            if (c == -1) {
                LOG.warning("Stream from client has been closed, throwing kill exception...");
                throw new EndOfStream();
            }
            return (char) c;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String readLength(InputStream client, int byteLength) {
        // Eat whitespace
        // Have observed problems with extra \r\n sequences from VSCode
        var next = read(client);
        while (Character.isWhitespace(next)) {
            next = read(client);
        }
        // Append next
        var result = new StringBuilder();
        var i = 0;
        while (true) {
            result.append(next);
            i++;
            if (i == byteLength) break;
            next = read(client);
        }
        return result.toString();
    }

    static String nextToken(InputStream client) {
        var contentLength = -1;
        while (true) {
            var line = readHeader(client);
            // If header is empty, next line is the start of the message
            if (line.isEmpty()) return readLength(client, contentLength);
            // If header contains length, save it
            var maybeLength = parseHeader(line);
            if (maybeLength != -1) contentLength = maybeLength;
        }
    }

    static JsonObject parseMessage(String token) {
        return gson.fromJson(token, JsonObject.class);
    }

    static String toJson(Object message) {
        return gson.toJson(message);
    }

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private void writeClient(String messageText) {
        var messageBytes = messageText.getBytes(UTF_8);
        var headerText = String.format("Content-Length: %d\r\n\r\n", messageBytes.length);
        var headerBytes = headerText.getBytes(UTF_8);
        try {
            send.write(headerBytes);
            send.write(messageBytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void request(Request message) {
        var jsonText = toJson(message);
        writeClient(jsonText);
    }

    private void respond(Response message) {
        var jsonText = toJson(message);
        writeClient(jsonText);
    }

    private void event(Event event) {
        var jsonText = toJson(event);
        writeClient(jsonText);
    }

    private static final JsonObject END_OF_STREAM = new JsonObject();

    private final OutputStream send;
    private final InputStream receive;
    private final DebugClient client;
    private final DebugServer server;
    private ArrayBlockingQueue<JsonObject> pending = new ArrayBlockingQueue<>(10);
    private Map<Integer, CompletableFuture<RunInTerminalResponseBody>> runInTerminalResponse =
            new ConcurrentHashMap<>();

    class RealClient implements DebugClient {
        @Override
        public void initialized() {}

        @Override
        public void stopped(StoppedEventBody evt) {}

        @Override
        public void exited(ExitedEventBody evt) {}

        @Override
        public void output(OutputEventBody evt) {}

        @Override
        public RunInTerminalResponseBody runInTerminal(RunInTerminalRequest req) {
            var wait = new CompletableFuture<RunInTerminalResponseBody>();
            runInTerminalResponse.put(req.seq, wait);
            request(req);
            try {
                return wait.get();
            } catch (InterruptedException | ExecutionException e) {
                LOG.log(Level.SEVERE, e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }
    }

    class MessageReader implements Runnable {
        @Override
        public void run() {
            LOG.info("Placing incoming messages on queue...");

            while (true) {
                try {
                    var token = nextToken(receive);
                    var json = parseMessage(token);
                    var msg = gson.fromJson(json, ProtocolMessage.class);
                    switch (msg.type) {
                        case "request":
                        case "event":
                            // Place requests and events on a queue to be processed by the main thread
                            pending.put(json);
                            break;
                        case "response":
                            // Process responses on the reader thread (which will usually wake up the main thread via a
                            // CompletableFuture)
                            processResponse(json);
                            break;
                        default:
                            throw new RuntimeException("Unknown message type " + msg.type);
                    }
                } catch (EndOfStream __) {
                    if (kill()) return;
                } catch (Exception e) {
                    LOG.log(Level.SEVERE, e.getMessage(), e);
                }
            }
        }

        void processResponse(JsonObject json) {
            var cmd = gson.fromJson(json, Response.class).command;
            switch (cmd) {
                case "runInTerminal":
                    {
                        var resp = gson.fromJson(json, RunInTerminalResponse.class);
                        var wait = runInTerminalResponse.remove(resp.request_seq);
                        wait.complete(resp.body);
                        break;
                    }
                default:
                    throw new RuntimeException("Don't know what to do with response to command " + cmd);
            }
        }

        private boolean kill() {
            LOG.info("Read stream has been closed, putting kill message onto queue...");
            try {
                pending.put(END_OF_STREAM);
                return true;
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Failed to put kill message onto queue, will try again...", e);
                return false;
            }
        }
    }

    public DebugAdapter(Function<DebugClient, DebugServer> serverFactory, InputStream receive, OutputStream send) {
        this.receive = receive;
        this.send = send;
        this.client = new RealClient();
        this.server = serverFactory.apply(client);
    }

    public void run() {
        // Read messages and process cancellations on a separate thread
        var reader = new java.lang.Thread(new MessageReader(), "reader");
        reader.setDaemon(true);
        reader.start();

        // Process messages on main thread
        LOG.info("Reading messages from queue...");
        processMessages:
        while (true) {
            JsonObject json;
            try {
                // Take a break periodically
                json = pending.poll(200, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                LOG.log(Level.SEVERE, e.getMessage(), e);
                continue;
            }
            // If receive has been closed, exit
            if (json == END_OF_STREAM) {
                LOG.warning("Stream from client has been closed, exiting...");
                break processMessages;
            }
            // If poll(_) failed, loop again
            if (json == null) {
                // TODO do async work here?
                continue;
            }
            // Otherwise, process the new message
            var msg = gson.fromJson(json, ProtocolMessage.class);
            switch (msg.type) {
                case "request":
                    processRequest(json);
                    break;
                case "response":
                    throw new RuntimeException("Response should have been handled by reader thread");
                case "event":
                    processEvent(json);
                    break;
                default:
                    throw new RuntimeException("Unknown message type " + msg.type);
            }
        }
    }

    private void processRequest(JsonObject json) {
        var req = gson.fromJson(json, Request.class);
        try {
            switch (req.command) {
                case "initialize":
                    {
                        var resp = new InitializeResponse();
                        resp.type = "response";
                        resp.command = req.command;
                        resp.request_seq = req.seq;
                        resp.success = true;
                        resp.body = server.initialize(gson.fromJson(json, InitializeRequest.class).arguments);
                        respond(resp);
                        break;
                    }
                case "configurationDone":
                    {
                        server.configurationDone();
                        ack(req);
                        break;
                    }
                case "launch":
                    {
                        server.launch(gson.fromJson(json, LaunchRequest.class).arguments);
                        ack(req);
                        break;
                    }
                case "attach":
                    {
                        server.attach(gson.fromJson(json, AttachRequest.class).arguments);
                        ack(req);
                        break;
                    }
                case "disconnect":
                    {
                        server.disconnect(gson.fromJson(json, DisconnectRequest.class).arguments);
                        ack(req);
                        break;
                    }
                case "terminate":
                    {
                        server.terminate(gson.fromJson(json, TerminateRequest.class).arguments);
                        ack(req);
                        break;
                    }
                case "setBreakpoints":
                    {
                        server.setBreakpoints(gson.fromJson(json, SetBreakpointsRequest.class).arguments);
                        ack(req);
                        break;
                    }
                case "setFunctionBreakpoints":
                    {
                        server.setFunctionBreakpoints(
                                gson.fromJson(json, SetFunctionBreakpointsRequest.class).arguments);
                        ack(req);
                        break;
                    }
                case "setExceptionBreakpoints":
                    {
                        server.setExceptionBreakpoints(
                                gson.fromJson(json, SetExceptionBreakpointsRequest.class).arguments);
                        ack(req);
                        break;
                    }
                case "continue":
                    {
                        server.continue_(gson.fromJson(json, ContinueRequest.class).arguments);
                        ack(req);
                        break;
                    }
                case "next":
                    {
                        server.next(gson.fromJson(json, NextRequest.class).arguments);
                        ack(req);
                        break;
                    }
                case "stepIn":
                    {
                        server.stepIn(gson.fromJson(json, StepInRequest.class).arguments);
                        ack(req);
                        break;
                    }
                case "stepOut":
                    {
                        server.stepOut(gson.fromJson(json, StepOutRequest.class).arguments);
                        ack(req);
                        break;
                    }
                case "threads":
                    {
                        var resp = new ThreadsResponse();
                        resp.type = "response";
                        resp.command = req.command;
                        resp.request_seq = req.seq;
                        resp.success = true;
                        resp.body = server.threads();
                        respond(resp);
                        break;
                    }
                case "variables":
                    {
                        var resp = new VariablesResponse();
                        resp.type = "response";
                        resp.command = req.command;
                        resp.request_seq = req.seq;
                        resp.success = true;
                        resp.body = server.variables(gson.fromJson(json, VariablesRequest.class).arguments);
                        respond(resp);
                        break;
                    }
                case "evaluate":
                    {
                        var resp = new EvaluateResponse();
                        resp.type = "response";
                        resp.command = req.command;
                        resp.request_seq = req.seq;
                        resp.success = true;
                        resp.body = server.evaluate(gson.fromJson(json, EvaluateRequest.class).arguments);
                        respond(resp);
                        break;
                    }
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.getMessage(), e);
            var resp = new ErrorResponse();
            resp.type = "response";
            resp.command = req.command;
            resp.request_seq = req.seq;
            resp.success = false;
            resp.message = e.getMessage();
            respond(resp);
        }
    }

    private void ack(Request req) {
        var resp = new Response();
        resp.type = "response";
        resp.command = req.command;
        resp.request_seq = req.seq;
        resp.success = true;
        respond(resp);
    }

    private void processEvent(JsonObject json) {
        var evt = gson.fromJson(json, Event.class);
        switch (evt.event) {
        }
    }

    static class EndOfStream extends RuntimeException {}

    private static final Logger LOG = Logger.getLogger("main");
}