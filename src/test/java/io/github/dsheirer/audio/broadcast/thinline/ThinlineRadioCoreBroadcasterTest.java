/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.broadcast.thinline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.audio.broadcast.AudioRecording;
import io.github.dsheirer.audio.broadcast.BroadcastDeliveryEvidence;
import io.github.dsheirer.audio.broadcast.BroadcastEvent;
import io.github.dsheirer.audio.broadcast.BroadcastState;
import io.github.dsheirer.audio.broadcast.CompletedCallBroadcastMetadata;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkgroupIdentifier;
import io.github.dsheirer.preference.UserPreferences;
import java.util.Set;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThinlineRadioCoreBroadcasterTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final List<AutoCloseable> mCleanup = new ArrayList<>();

    @TempDir
    Path mTemporaryFolder;

    @AfterEach
    void cleanup() throws Exception
    {
        for(int index = mCleanup.size() - 1; index >= 0; index--)
        {
            mCleanup.get(index).close();
        }
        mCleanup.clear();
    }

    @Test
    void socketUrlMapsHttpHostsToSdrtrunkWebsocket()
    {
        assertEquals("ws://localhost:3000/api/sdrtrunk", ThinlineRadioSocket.socketUrl("http://localhost:3000"));
        assertEquals("wss://scanner.example/api/sdrtrunk",
            ThinlineRadioSocket.socketUrl("https://scanner.example/api/call-upload"));
        assertEquals(null, ThinlineRadioSocket.socketUrl("scanner.example"));
    }

    @Test
    void startWithoutSiteKeyReportsConfigurationError()
    {
        ThinlineRadioCoreConfiguration configuration = new ThinlineRadioCoreConfiguration();
        configuration.setName("CATCHALL");
        configuration.setHost("http://127.0.0.1:3000/api/call-upload");
        configuration.setSystemID(9);
        UserPreferences preferences = new UserPreferences();
        preferences.getThinlineRadioPreference().setApiKey("");
        preferences.getThinlineRadioPreference().setUrl("http://127.0.0.1:3000");
        ThinlineRadioCoreBroadcaster broadcaster =
            new ThinlineRadioCoreBroadcaster(configuration, new AliasModel(), preferences);
        broadcaster.start();
        assertEquals(BroadcastState.CONFIGURATION_ERROR, broadcaster.getBroadcastState());
        broadcaster.stop();
    }

    @Test
    void holdsCallUntilUploadCommandThenPostsMultipartWithSiteKey() throws Exception
    {
        CountDownLatch streamed = new CountDownLatch(1);
        MockSiteControl site = MockSiteControl.start();
        mCleanup.add(site);

        String siteKey = "site-control-" + UUID.randomUUID();
        UserPreferences preferences = new UserPreferences();
        preferences.getThinlineRadioPreference().setApiKey(siteKey);
        // ThinLine Core uses the site preference URL for both websocket and /api/call-upload.
        preferences.getThinlineRadioPreference().setUrl("http://127.0.0.1:" + site.port());

        ThinlineRadioCoreConfiguration configuration = new ThinlineRadioCoreConfiguration();
        configuration.setName("CATCHALL");
        configuration.setHost("http://127.0.0.1:" + site.port() + "/api/call-upload");
        configuration.setSystemID(9);
        configuration.setApiKey("");
        ThinlineRadioCoreBroadcaster broadcaster =
            new ThinlineRadioCoreBroadcaster(configuration, new AliasModel(), preferences);
        broadcaster.setListener(event -> {
            if(event.getEvent() == BroadcastEvent.Event.BROADCASTER_STREAMED_COUNT_CHANGE)
            {
                streamed.countDown();
            }
        });
        mCleanup.add(broadcaster::stop);

        broadcaster.start();
        assertTrue(site.awaitWelcome(5, TimeUnit.SECONDS), "site control never welcomed Core");
        assertTrue(awaitConnected(broadcaster, 5, TimeUnit.SECONDS));
        assertTrue(site.awaitMessage(node -> "core.hello".equals(node.path("type").asText()), 5, TimeUnit.SECONDS)
            .isPresent());

        IdentifierCollection identifiers = identifiers();
        BroadcastChannel destination = new BroadcastChannel(configuration.getName());
        AudioCallId callId = new AudioCallId(1L, 2L, 0);
        CallLegId callLegId = CallLegId.from(callId);
        long now = System.currentTimeMillis();
        AudioCallSnapshot snapshot = new AudioCallSnapshot(callId, null, null, identifiers, Set.of(destination),
            now, now, 1, 1, now, now, true, false, CallEncryptionState.CLEAR, false, null,
            VoiceCallQuality.EMPTY, callLegId, CallLegSource.UNKNOWN, null);
        broadcaster.callStarted(callLegId, snapshot, snapshot.broadcastChannels());
        JsonNode started = site.awaitMessage(node -> "call.started".equals(node.path("type").asText()), 5, TimeUnit.SECONDS)
            .orElseThrow();
        String recordingId = started.path("recordingId").asText();
        assertFalse(recordingId.isBlank());
        assertEquals(9, started.path("system").asInt());
        assertEquals(100, started.path("talkgroup").asInt());

        Path recordingPath = mTemporaryFolder.resolve("core_call.mp3");
        Files.writeString(recordingPath, "core-audio", StandardCharsets.UTF_8);
        AudioRecording recording = new AudioRecording(recordingPath, List.of(destination), identifiers,
            System.currentTimeMillis(), 1_250, BroadcastDeliveryEvidence.EMPTY,
            new CompletedCallBroadcastMetadata(snapshot, List.of()));
        recording.addPendingReplay();
        broadcaster.receive(recording);

        JsonNode ready = site.awaitMessage(node -> "call.ready".equals(node.path("type").asText()) &&
            recordingId.equals(node.path("recordingId").asText()), 5, TimeUnit.SECONDS).orElseThrow();
        assertEquals(1_250, ready.path("duration").asLong());

        site.sendCommand("uploadCall", recordingId);
        assertTrue(site.awaitUpload(5, TimeUnit.SECONDS), "Core upload was not received");
        assertTrue(streamed.await(5, TimeUnit.SECONDS), "Core upload did not finish");
        assertEquals(1, broadcaster.getStreamedAudioCount());
        String body = site.uploadBody();
        assertTrue(body.contains("name=\"key\""), body);
        assertTrue(body.contains(siteKey), body);
        assertTrue(body.contains("name=\"recordingId\""), body);
        assertTrue(body.contains(recordingId), body);
        assertTrue(body.contains("core-audio"), body);
    }

    @Test
    void discardCommandDropsHeldRecordingWithoutUpload() throws Exception
    {
        MockSiteControl site = MockSiteControl.start();
        mCleanup.add(site);

        String siteKey = "site-control-" + UUID.randomUUID();
        UserPreferences preferences = new UserPreferences();
        preferences.getThinlineRadioPreference().setApiKey(siteKey);
        preferences.getThinlineRadioPreference().setUrl("http://127.0.0.1:" + site.port());

        ThinlineRadioCoreConfiguration configuration = new ThinlineRadioCoreConfiguration();
        configuration.setName("SCANNER - MAIN - RANDOLPH");
        configuration.setHost("http://127.0.0.1:" + site.port() + "/api/call-upload");
        configuration.setSystemID(2);
        ThinlineRadioCoreBroadcaster broadcaster =
            new ThinlineRadioCoreBroadcaster(configuration, new AliasModel(), preferences);
        mCleanup.add(broadcaster::stop);
        broadcaster.start();
        assertTrue(site.awaitWelcome(5, TimeUnit.SECONDS));
        assertTrue(awaitConnected(broadcaster, 5, TimeUnit.SECONDS));

        Path recordingPath = mTemporaryFolder.resolve("discard.mp3");
        Files.writeString(recordingPath, "discard-me", StandardCharsets.UTF_8);
        AudioRecording recording = new AudioRecording(recordingPath, List.of(), identifiers(),
            System.currentTimeMillis(), 500);
        recording.addPendingReplay();
        broadcaster.receive(recording);
        JsonNode ready = site.awaitMessage(node -> "call.ready".equals(node.path("type").asText()), 5, TimeUnit.SECONDS)
            .orElseThrow();
        site.sendCommand("discardCall", ready.path("recordingId").asText());
        assertTrue(site.awaitMessage(node -> "result".equals(node.path("type").asText()) &&
            node.path("ok").asBoolean(), 5, TimeUnit.SECONDS).isPresent());
        assertEquals(0, broadcaster.getAudioQueueSize());
        assertFalse(site.awaitUpload(400, TimeUnit.MILLISECONDS));
    }

    private static boolean awaitConnected(ThinlineRadioCoreBroadcaster broadcaster, long timeout, TimeUnit unit)
        throws InterruptedException
    {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while(System.nanoTime() < deadline)
        {
            if(broadcaster.getBroadcastState() == BroadcastState.CONNECTED)
            {
                return true;
            }
            Thread.sleep(25L);
        }
        return broadcaster.getBroadcastState() == BroadcastState.CONNECTED;
    }

    private static IdentifierCollection identifiers()
    {
        return new IdentifierCollection(List.of(
            NXDNRadioIdentifier.createFrom(55),
            NXDNTalkgroupIdentifier.createTo(100),
            SystemConfigurationIdentifier.create("CORE"),
            FrequencyConfigurationIdentifier.create(451_000_000L)));
    }

    /**
     * Combined ThinLine mock: /api/sdrtrunk websocket plus /api/call-upload on one host:port,
     * matching production site preference URL behavior.
     */
    private static final class MockSiteControl implements AutoCloseable
    {
        private final ServerSocket mServer;
        private final Thread mThread;
        private final CopyOnWriteArrayList<JsonNode> mMessages = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<Socket> mClients = new CopyOnWriteArrayList<>();
        private final CountDownLatch mWelcome = new CountDownLatch(1);
        private final CountDownLatch mUpload = new CountDownLatch(1);
        private final AtomicReferenceHolder mUploadBody = new AtomicReferenceHolder();
        private volatile OutputStream mOut;
        private volatile boolean mClosed;

        private MockSiteControl(ServerSocket server)
        {
            mServer = server;
            mThread = new Thread(this::acceptLoop, "thinline-mock-site");
            mThread.setDaemon(true);
            mThread.start();
        }

        static MockSiteControl start() throws IOException
        {
            return new MockSiteControl(new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")));
        }

        int port()
        {
            return mServer.getLocalPort();
        }

        boolean awaitWelcome(long timeout, TimeUnit unit) throws InterruptedException
        {
            return mWelcome.await(timeout, unit);
        }

        boolean awaitUpload(long timeout, TimeUnit unit) throws InterruptedException
        {
            return mUpload.await(timeout, unit);
        }

        String uploadBody()
        {
            return mUploadBody.value;
        }

        Optional<JsonNode> awaitMessage(java.util.function.Predicate<JsonNode> match, long timeout, TimeUnit unit)
            throws InterruptedException
        {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while(System.nanoTime() < deadline)
            {
                for(JsonNode node : mMessages)
                {
                    if(match.test(node))
                    {
                        return Optional.of(node);
                    }
                }
                Thread.sleep(25L);
            }
            return mMessages.stream().filter(match).findFirst();
        }

        void sendCommand(String command, String recordingId) throws IOException
        {
            String payload = MAPPER.createObjectNode()
                .put("type", "command")
                .put("id", UUID.randomUUID().toString())
                .put("command", command)
                .put("recordingId", recordingId)
                .toString();
            writeFrame(payload);
        }

        private void acceptLoop()
        {
            while(!mClosed)
            {
                try
                {
                    Socket socket = mServer.accept();
                    mClients.add(socket);
                    Thread handler = new Thread(() -> handle(socket), "thinline-mock-conn");
                    handler.setDaemon(true);
                    handler.start();
                }
                catch(IOException ignored)
                {
                    if(mClosed)
                    {
                        return;
                    }
                }
            }
        }

        private void handle(Socket socket)
        {
            try
            {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                String request = readHttpHeaders(in);
                boolean websocket = request.toLowerCase().contains("upgrade: websocket");
                if(websocket)
                {
                    String key = request.lines()
                        .filter(line -> line.toLowerCase().startsWith("sec-websocket-key:"))
                        .map(line -> line.substring(line.indexOf(':') + 1).trim())
                        .findFirst()
                        .orElseThrow();
                    String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.UTF_8)));
                    String response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
                    out.write(response.getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    mOut = out;
                    while(!mClosed)
                    {
                        String text = readFrame(in);
                        if(text == null)
                        {
                            break;
                        }
                        if(text.isEmpty())
                        {
                            continue;
                        }
                        JsonNode node = MAPPER.readTree(text);
                        mMessages.add(node);
                        if("hello".equals(node.path("type").asText()))
                        {
                            writeFrame(MAPPER.createObjectNode().put("type", "welcome").toString());
                            mWelcome.countDown();
                        }
                    }
                    return;
                }

                if(request.startsWith("POST /api/call-upload"))
                {
                    int contentLength = request.lines()
                        .filter(line -> line.toLowerCase().startsWith("content-length:"))
                        .map(line -> Integer.parseInt(line.substring(line.indexOf(':') + 1).trim()))
                        .findFirst()
                        .orElse(0);
                    byte[] body = in.readNBytes(contentLength);
                    mUploadBody.value = new String(body, StandardCharsets.UTF_8);
                    byte[] ok = "Call imported successfully.".getBytes(StandardCharsets.UTF_8);
                    String response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: " +
                        ok.length + "\r\nConnection: close\r\n\r\n";
                    out.write(response.getBytes(StandardCharsets.US_ASCII));
                    out.write(ok);
                    out.flush();
                    mUpload.countDown();
                }
                else
                {
                    byte[] missing = "not found".getBytes(StandardCharsets.US_ASCII);
                    String response = "HTTP/1.1 404 Not Found\r\nContent-Length: " + missing.length +
                        "\r\nConnection: close\r\n\r\n";
                    out.write(response.getBytes(StandardCharsets.US_ASCII));
                    out.write(missing);
                    out.flush();
                }
            }
            catch(Exception ignored)
            {
                mWelcome.countDown();
            }
            finally
            {
                try
                {
                    socket.close();
                }
                catch(IOException ignored)
                {
                }
            }
        }

        private synchronized void writeFrame(String text) throws IOException
        {
            OutputStream out = mOut;
            if(out == null)
            {
                throw new IOException("websocket not ready");
            }
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x81);
            if(payload.length < 126)
            {
                frame.write(payload.length);
            }
            else
            {
                frame.write(126);
                frame.write((payload.length >> 8) & 0xFF);
                frame.write(payload.length & 0xFF);
            }
            frame.write(payload);
            out.write(frame.toByteArray());
            out.flush();
        }

        private static String readHttpHeaders(InputStream in) throws IOException
        {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int state = 0;
            while(state < 4)
            {
                int next = in.read();
                if(next < 0)
                {
                    break;
                }
                buffer.write(next);
                if((state == 0 || state == 2) && next == '\r')
                {
                    state++;
                }
                else if((state == 1 || state == 3) && next == '\n')
                {
                    state++;
                }
                else
                {
                    state = 0;
                }
            }
            return buffer.toString(StandardCharsets.US_ASCII);
        }

        private static String readFrame(InputStream in) throws IOException
        {
            int first = in.read();
            if(first < 0)
            {
                return null;
            }
            int second = in.read();
            if(second < 0)
            {
                return null;
            }
            boolean masked = (second & 0x80) != 0;
            int length = second & 0x7F;
            if(length == 126)
            {
                length = (in.read() << 8) | in.read();
            }
            else if(length == 127)
            {
                long longLength = 0;
                for(int i = 0; i < 8; i++)
                {
                    longLength = (longLength << 8) | in.read();
                }
                length = (int)longLength;
            }
            byte[] mask = new byte[4];
            if(masked && in.read(mask) != 4)
            {
                return null;
            }
            byte[] payload = in.readNBytes(length);
            if(masked)
            {
                for(int i = 0; i < payload.length; i++)
                {
                    payload[i] = (byte)(payload[i] ^ mask[i % 4]);
                }
            }
            int opcode = first & 0x0F;
            if(opcode == 0x8)
            {
                return null;
            }
            if(opcode == 0x9)
            {
                return "";
            }
            return new String(payload, StandardCharsets.UTF_8);
        }

        @Override
        public void close()
        {
            mClosed = true;
            try
            {
                mServer.close();
            }
            catch(IOException ignored)
            {
            }
            for(Socket client : mClients)
            {
                try
                {
                    client.close();
                }
                catch(IOException ignored)
                {
                }
            }
        }

        private static final class AtomicReferenceHolder
        {
            private volatile String value = "";
        }
    }
}
