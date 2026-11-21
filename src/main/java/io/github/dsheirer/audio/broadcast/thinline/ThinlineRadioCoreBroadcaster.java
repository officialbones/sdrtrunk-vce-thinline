/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */
package io.github.dsheirer.audio.broadcast.thinline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.audio.broadcast.AbstractAudioBroadcaster;
import io.github.dsheirer.audio.broadcast.AudioRecording;
import io.github.dsheirer.audio.broadcast.BroadcastEvent;
import io.github.dsheirer.audio.broadcast.BroadcastState;
import io.github.dsheirer.audio.broadcast.CompletedCallBroadcastMetadata;
import io.github.dsheirer.audio.broadcast.CoreCallListener;
import io.github.dsheirer.audio.broadcast.rdioscanner.FormField;
import io.github.dsheirer.audio.broadcast.rdioscanner.RdioScannerBuilder;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.configuration.ConfigurationLongIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.util.ThreadPool;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ThinLine Core broadcaster: holds recordings and uploads only the copies ThinLine selects.
 */
public class ThinlineRadioCoreBroadcaster extends AbstractAudioBroadcaster<ThinlineRadioCoreConfiguration>
    implements CoreCallListener
{
    private static final Logger mLog = LoggerFactory.getLogger(ThinlineRadioCoreBroadcaster.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CALL_UPLOAD_PATH = "/api/call-upload";
    private static final Consumer<JsonNode> COMMANDS = ThinlineRadioCoreBroadcaster::dispatchCommand;
    private static final Map<String,ThinlineRadioCoreBroadcaster> BY_RECORDING = new ConcurrentHashMap<>();
    private static final Set<ThinlineRadioCoreBroadcaster> LIVE = ConcurrentHashMap.newKeySet();
    private static final int UPLOAD_ATTEMPTS = 2;
    private static final Duration UPLOAD_TIMEOUT = Duration.ofSeconds(6L);

    private final AliasModel mAliasModel;
    private final UserPreferences mUserPreferences;
    private final HttpClient mHttpClient = newHttpClient();
    private final Map<CallLegId,String> mStarted = new ConcurrentHashMap<>();
    private final Map<String,Held> mHeld = new ConcurrentHashMap<>();
    private final AtomicBoolean mRunning = new AtomicBoolean();
    private final AtomicBoolean mStopped = new AtomicBoolean();
    private final Runnable mOnReady = this::announce;
    private final Runnable mOnClosed = this::uploadHeld;
    private final ThinlineRadioEndpoint mEndpoint = new ThinlineRadioEndpoint(COMMANDS, mOnReady, mOnClosed);

    public ThinlineRadioCoreBroadcaster(ThinlineRadioCoreConfiguration configuration, AliasModel aliasModel,
                                        UserPreferences userPreferences)
    {
        super(configuration);
        mAliasModel = aliasModel;
        mUserPreferences = userPreferences;
    }

    @Override
    public void start()
    {
        if(mStopped.get())
        {
            return;
        }
        if(siteKey().isBlank())
        {
            mRunning.set(false);
            setBroadcastState(BroadcastState.CONFIGURATION_ERROR);
            return;
        }
        mRunning.set(true);
        LIVE.add(this);
        setBroadcastState(BroadcastState.CONNECTING);
        connect();
    }

    @Override
    public void stop()
    {
        if(!mStopped.compareAndSet(false, true))
        {
            return;
        }
        mRunning.set(false);
        LIVE.remove(this);
        List<String> pending = List.copyOf(mHeld.keySet());
        for(String recordingId : List.copyOf(mStarted.values()))
        {
            sendCancelled(recordingId);
        }
        mStarted.clear();
        sendBye();
        mEndpoint.close();
        setBroadcastState(BroadcastState.DISCONNECTED);
        if(!pending.isEmpty())
        {
            ThreadPool.CACHED.submit(() -> {
                for(String recordingId : pending)
                {
                    upload(recordingId);
                }
            });
        }
    }

    @Override
    public void dispose()
    {
        stop();
    }

    @Override
    public int getAudioQueueSize()
    {
        return mHeld.size();
    }

    @Override
    public void callStarted(CallLegId callLegId, AudioCallSnapshot snapshot, Collection<BroadcastChannel> destinations)
    {
        if(!mRunning.get() || callLegId == null || snapshot == null || mStarted.containsKey(callLegId))
        {
            return;
        }
        if(!destinedHere(destinations))
        {
            return;
        }

        String recordingId = UUID.randomUUID().toString();
        mStarted.put(callLegId, recordingId);
        BY_RECORDING.put(recordingId, this);
        connect();
        if(!mEndpoint.isReady())
        {
            return;
        }
        ObjectNode body = baseReport("call.started", recordingId, snapshot.identifierCollection(),
            snapshot.startTimestamp(), 0L);
        attachQuality(body, snapshot.voiceCallQuality());
        mEndpoint.send(body);
    }

    @Override
    public void callDropped(CallLegId callLegId)
    {
        String recordingId = mStarted.remove(callLegId);
        if(recordingId == null)
        {
            return;
        }
        BY_RECORDING.remove(recordingId);
        sendCancelled(recordingId);
    }

    @Override
    public void receive(AudioRecording audioRecording)
    {
        if(audioRecording == null)
        {
            return;
        }

        String recordingId = claimStartedRecordingId(audioRecording);
        if(recordingId == null)
        {
            recordingId = UUID.randomUUID().toString();
        }

        String talkgroup = talkgroup(audioRecording.getIdentifierCollection());
        String radioId = radioId(audioRecording.getIdentifierCollection());
        long frequency = frequency(audioRecording.getIdentifierCollection());
        String patches = patches(audioRecording.getIdentifierCollection());
        Held held = new Held(audioRecording, talkgroup, radioId, frequency, patches);
        mHeld.put(recordingId, held);
        BY_RECORDING.put(recordingId, this);
        connect();
        if(!mEndpoint.isReady())
        {
            return;
        }
        mEndpoint.send(readyReport(recordingId, held));
    }

    private String claimStartedRecordingId(AudioRecording audioRecording)
    {
        CompletedCallBroadcastMetadata metadata = audioRecording.getCompletedCallMetadata();
        if(metadata == null || !metadata.isAvailable() || metadata.snapshot() == null)
        {
            return null;
        }
        CallLegId callLegId = metadata.snapshot().callLegId();
        if(callLegId == null)
        {
            return null;
        }
        return mStarted.remove(callLegId);
    }

    private boolean destinedHere(Collection<BroadcastChannel> destinations)
    {
        if(destinations == null || destinations.isEmpty())
        {
            return false;
        }
        String configurationId = getBroadcastConfiguration().getConfigurationId();
        String name = getBroadcastConfiguration().getName();
        for(BroadcastChannel channel : destinations)
        {
            if(channel == null)
            {
                continue;
            }
            if(configurationId != null && configurationId.equals(channel.getConfigurationId()))
            {
                return true;
            }
            if(name != null && name.equals(channel.getChannelName()))
            {
                return true;
            }
        }
        return false;
    }

    private ObjectNode readyReport(String recordingId, Held held)
    {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("type", "call.ready");
        body.put("recordingId", recordingId);
        body.put("system", getBroadcastConfiguration().getSystemID());
        body.put("talkgroup", parseInt(held.talkgroup));
        body.put("radioId", parseInt(held.radioId));
        body.put("frequency", held.frequency);
        body.put("start", held.recording.getStartTime());
        body.put("duration", held.recording.getRecordingLength());
        body.set("patches", patchArray(held.patches));
        if(held.quality >= 0)
        {
            body.put("quality", held.quality);
            body.put("frames", held.frames);
        }
        return body;
    }

    private void connect()
    {
        mEndpoint.ensure(socketUrl(serverHost()), siteKey());
    }

    private void uploadHeld()
    {
        for(String recordingId : List.copyOf(mHeld.keySet()))
        {
            upload(recordingId);
        }
    }

    private void announce()
    {
        if(!mRunning.get())
        {
            return;
        }
        setBroadcastState(BroadcastState.CONNECTED);
        ObjectNode hello = MAPPER.createObjectNode();
        hello.put("type", "core.hello");
        hello.put("system", getBroadcastConfiguration().getSystemID());
        mEndpoint.send(hello);
        for(Map.Entry<String,Held> entry : List.copyOf(mHeld.entrySet()))
        {
            if(entry.getValue().stage == Stage.HELD)
            {
                mEndpoint.send(readyReport(entry.getKey(), entry.getValue()));
            }
        }
    }

    private void sendBye()
    {
        ObjectNode bye = MAPPER.createObjectNode();
        bye.put("type", "core.bye");
        bye.put("system", getBroadcastConfiguration().getSystemID());
        mEndpoint.send(bye);
    }

    private void sendCancelled(String recordingId)
    {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("type", "call.cancelled");
        body.put("recordingId", recordingId);
        body.put("system", getBroadcastConfiguration().getSystemID());
        mEndpoint.send(body);
    }

    private static void dispatchCommand(JsonNode command)
    {
        String name = command.path("command").asText("");
        if(!"uploadCall".equals(name) && !"discardCall".equals(name))
        {
            return;
        }
        String recordingId = command.path("recordingId").asText("");
        ThinlineRadioCoreBroadcaster broadcaster = BY_RECORDING.get(recordingId);
        if(broadcaster != null)
        {
            broadcaster.onCommand(command);
            return;
        }
        Iterator<ThinlineRadioCoreBroadcaster> iterator = LIVE.iterator();
        if(iterator.hasNext())
        {
            iterator.next().reply(command, false, "Recording is not held");
        }
    }

    private void reply(JsonNode command, boolean ok, String error)
    {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("type", "result");
        result.put("id", command.path("id").asText(""));
        result.put("ok", ok);
        result.put("error", error);
        mEndpoint.send(result);
    }

    private void onCommand(JsonNode command)
    {
        String name = command.path("command").asText("");
        String recordingId = command.path("recordingId").asText("");
        boolean ok = false;
        String error = "";
        if("uploadCall".equals(name))
        {
            ok = mHeld.containsKey(recordingId);
            if(ok)
            {
                ThreadPool.CACHED.submit(() -> upload(recordingId));
            }
            else
            {
                error = "Recording is not held";
            }
        }
        else if("discardCall".equals(name))
        {
            ok = discard(recordingId);
            if(!ok)
            {
                error = "Recording is not held";
            }
        }
        else
        {
            return;
        }
        reply(command, ok, error);
    }

    private boolean discard(String recordingId)
    {
        Held held = mHeld.remove(recordingId);
        BY_RECORDING.remove(recordingId);
        if(held == null)
        {
            return false;
        }
        held.stage = Stage.DONE;
        held.recording.removePendingReplay();
        return true;
    }

    private void upload(String recordingId)
    {
        Held held = mHeld.get(recordingId);
        if(held == null)
        {
            return;
        }
        synchronized(held)
        {
            if(held.stage != Stage.HELD)
            {
                return;
            }
            held.stage = Stage.UPLOADING;
        }

        byte[] audio = null;
        try
        {
            audio = Files.readAllBytes(held.recording.getPath());
        }
        catch(Exception e)
        {
            mLog.warn("Thinline Radio Core upload failed: {}", e.toString());
        }

        for(int attempt = 1; audio != null && attempt <= UPLOAD_ATTEMPTS; attempt++)
        {
            HttpClient client = attempt == 1 ? mHttpClient : newHttpClient();
            try
            {
                HttpResponse<String> response = client.send(uploadRequest(recordingId, held, audio),
                    HttpResponse.BodyHandlers.ofString());
                if(response.statusCode() == 200 && response.body() != null &&
                    response.body().contains("Call imported successfully."))
                {
                    incrementStreamedAudioCount();
                    broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_STREAMED_COUNT_CHANGE));
                    finish(recordingId, held);
                    return;
                }
                mLog.warn("Thinline Radio Core upload failed [{}] {}", response.statusCode(), response.body());
                if(response.statusCode() < 500)
                {
                    break;
                }
            }
            catch(InterruptedException e)
            {
                Thread.currentThread().interrupt();
                break;
            }
            catch(Exception e)
            {
                mLog.warn("Thinline Radio Core upload failed (attempt {} of {}): {}", attempt, UPLOAD_ATTEMPTS,
                    e.toString());
            }
            finally
            {
                if(client != mHttpClient)
                {
                    client.close();
                }
            }
        }

        incrementErrorAudioCount();
        broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_ERROR_COUNT_CHANGE));
        held.stage = Stage.HELD;
        sendUploadFailed(recordingId);
    }

    private HttpRequest uploadRequest(String recordingId, Held held, byte[] audio)
    {
        RdioScannerBuilder body = new RdioScannerBuilder();
        body.addPart(FormField.KEY, siteKey())
            .addPart(FormField.SYSTEM, getBroadcastConfiguration().getSystemID())
            .addPart(FormField.TALKGROUP_ID, held.talkgroup)
            .addPart(FormField.SOURCE, held.radioId)
            .addPart(FormField.FREQUENCY, held.frequency)
            .addPart(FormField.DATE_TIME, String.valueOf(held.recording.getStartTime()))
            .addPart(FormField.DURATION, String.valueOf(held.recording.getRecordingLength()))
            .addPart(FormField.RECORDING_ID, recordingId)
            .addPart(FormField.PATCHES, held.patches)
            .addAudioName(held.recording.getPath().getFileName().toString())
            .addFile(audio);
        return HttpRequest.newBuilder()
            .uri(URI.create(uploadUrl(serverHost())))
            .header("Content-Type", "multipart/form-data; boundary=" + body.getBoundary())
            .header("User-Agent", "sdrtrunk")
            .timeout(UPLOAD_TIMEOUT)
            .POST(body.build())
            .build();
    }

    private static HttpClient newHttpClient()
    {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    private void sendUploadFailed(String recordingId)
    {
        if(!mEndpoint.isReady())
        {
            return;
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("type", "call.uploadFailed");
        body.put("recordingId", recordingId);
        body.put("system", getBroadcastConfiguration().getSystemID());
        mEndpoint.send(body);
    }

    private void finish(String recordingId, Held held)
    {
        held.stage = Stage.DONE;
        mHeld.remove(recordingId);
        BY_RECORDING.remove(recordingId);
        held.recording.removePendingReplay();
    }

    private ObjectNode baseReport(String type, String recordingId, IdentifierCollection identifiers, long start,
                                  long duration)
    {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("type", type);
        body.put("recordingId", recordingId);
        body.put("system", getBroadcastConfiguration().getSystemID());
        body.put("talkgroup", parseInt(talkgroup(identifiers)));
        body.put("radioId", parseInt(radioId(identifiers)));
        body.put("frequency", frequency(identifiers));
        body.put("start", start);
        body.put("duration", duration);
        body.set("patches", patchArray(patches(identifiers)));
        return body;
    }

    private static void attachQuality(ObjectNode body, VoiceCallQuality quality)
    {
        if(quality == null || !quality.hasMeasurements())
        {
            return;
        }
        body.put("quality", (int)Math.round(quality.qualityPercent()));
        body.put("frames", quality.expectedFrameCount());
    }

    private ArrayNode patchArray(String patches)
    {
        ArrayNode array = MAPPER.createArrayNode();
        if(patches == null || patches.isBlank() || "[]".equals(patches))
        {
            return array;
        }
        String trimmed = patches.trim();
        if(trimmed.startsWith("["))
        {
            trimmed = trimmed.substring(1);
        }
        if(trimmed.endsWith("]"))
        {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        for(String part : trimmed.split(","))
        {
            int value = parseInt(part.trim());
            if(value > 0)
            {
                array.add(value);
            }
        }
        return array;
    }

    private String talkgroup(IdentifierCollection identifiers)
    {
        if(identifiers == null)
        {
            return "0";
        }
        Identifier identifier = identifiers.getToIdentifier();
        if(identifier == null)
        {
            return "0";
        }
        if(mAliasModel != null)
        {
            AliasList aliasList = mAliasModel.getAliasList(identifiers);
            if(aliasList != null)
            {
                List<Alias> aliases = aliasList.getAliases(identifier);
                Optional<Alias> streamAs = aliases.stream()
                    .filter(alias -> alias.getStreamTalkgroupAlias() != null)
                    .findFirst();
                if(streamAs.isPresent())
                {
                    return String.valueOf(streamAs.get().getStreamTalkgroupAlias().getValue());
                }
            }
        }
        if(identifier instanceof PatchGroupIdentifier patchGroupIdentifier)
        {
            return patchGroupIdentifier.getValue().getPatchGroup().getValue().toString();
        }
        if(identifier instanceof TalkgroupIdentifier talkgroupIdentifier)
        {
            return String.valueOf(talkgroupIdentifier.getValue());
        }
        if(identifier instanceof RadioIdentifier radioIdentifier)
        {
            return radioIdentifier.getValue().toString();
        }
        return "0";
    }

    private static String radioId(IdentifierCollection identifiers)
    {
        if(identifiers == null)
        {
            return "0";
        }
        for(Identifier identifier : identifiers.getIdentifiers(Role.FROM))
        {
            if(identifier instanceof RadioIdentifier radioIdentifier)
            {
                return radioIdentifier.getValue().toString();
            }
        }
        return "0";
    }

    private static long frequency(IdentifierCollection identifiers)
    {
        if(identifiers == null)
        {
            return 0L;
        }
        Identifier identifier = identifiers.getIdentifier(IdentifierClass.CONFIGURATION, Form.CHANNEL_FREQUENCY,
            Role.ANY);
        if(identifier instanceof ConfigurationLongIdentifier configurationLongIdentifier &&
            configurationLongIdentifier.getValue() != null)
        {
            return configurationLongIdentifier.getValue();
        }
        return 0L;
    }

    private static String patches(IdentifierCollection identifiers)
    {
        if(identifiers == null)
        {
            return "[]";
        }
        Identifier identifier = identifiers.getToIdentifier();
        if(!(identifier instanceof PatchGroupIdentifier patchGroupIdentifier))
        {
            return "[]";
        }
        PatchGroup patchGroup = patchGroupIdentifier.getValue();
        StringBuilder builder = new StringBuilder();
        builder.append("[").append(patchGroup.getPatchGroup().getValue());
        for(TalkgroupIdentifier talkgroupIdentifier : patchGroup.getPatchedTalkgroupIdentifiers())
        {
            builder.append(",").append(talkgroupIdentifier.getValue());
        }
        for(RadioIdentifier radioIdentifier : patchGroup.getPatchedRadioIdentifiers())
        {
            builder.append(",").append(radioIdentifier.getValue());
        }
        builder.append("]");
        return builder.toString();
    }

    private static int parseInt(String value)
    {
        try
        {
            return Integer.parseInt(value.trim());
        }
        catch(Exception e)
        {
            return 0;
        }
    }

    private String siteKey()
    {
        if(mUserPreferences == null)
        {
            return "";
        }
        String key = mUserPreferences.getThinlineRadioPreference().getApiKey();
        return key == null ? "" : key.trim();
    }

    private String serverHost()
    {
        String preference = mUserPreferences == null ? "" : mUserPreferences.getThinlineRadioPreference().getUrl();
        if(preference != null && !preference.isBlank())
        {
            return preference;
        }
        String host = getBroadcastConfiguration().getHost();
        return host == null ? "" : host;
    }

    private static String uploadUrl(String host)
    {
        return strip(host) + CALL_UPLOAD_PATH;
    }

    static String socketUrl(String host)
    {
        return ThinlineRadioSocket.socketUrl(host);
    }

    private static String strip(String host)
    {
        if(host == null)
        {
            return "";
        }
        String trimmed = host.trim();
        if(trimmed.endsWith(CALL_UPLOAD_PATH))
        {
            trimmed = trimmed.substring(0, trimmed.length() - CALL_UPLOAD_PATH.length());
        }
        while(trimmed.endsWith("/"))
        {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static final class Held
    {
        private final AudioRecording recording;
        private final String talkgroup;
        private final String radioId;
        private final long frequency;
        private final String patches;
        private final int quality;
        private final int frames;
        private volatile Stage stage = Stage.HELD;

        private Held(AudioRecording recording, String talkgroup, String radioId, long frequency, String patches)
        {
            this.recording = recording;
            this.talkgroup = talkgroup;
            this.radioId = radioId;
            this.frequency = frequency;
            this.patches = patches;
            VoiceCallQuality voice = recording.getCompletedCallMetadata() != null &&
                recording.getCompletedCallMetadata().isAvailable() &&
                recording.getCompletedCallMetadata().snapshot() != null ?
                recording.getCompletedCallMetadata().snapshot().voiceCallQuality() : null;
            if(voice != null && voice.hasMeasurements())
            {
                quality = (int)Math.round(voice.qualityPercent());
                frames = (int)Math.min(Integer.MAX_VALUE, voice.expectedFrameCount());
            }
            else
            {
                quality = -1;
                frames = 0;
            }
        }
    }

    private enum Stage
    {
        HELD,
        UPLOADING,
        DONE
    }
}
