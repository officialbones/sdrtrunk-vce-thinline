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
import io.github.dsheirer.audio.broadcast.AbstractAudioBroadcaster;
import io.github.dsheirer.audio.broadcast.BroadcastModel;
import io.github.dsheirer.audio.broadcast.BroadcastServerType;
import io.github.dsheirer.audio.broadcast.BroadcastState;
import io.github.dsheirer.audio.broadcast.ConfiguredBroadcast;
import io.github.dsheirer.audio.broadcast.rdioscanner.RdioScannerConfiguration;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.thinline.ThinlineRadioPreference;
import io.github.dsheirer.util.ThreadPool;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal ThinLine SDR Health reporter over the shared site-control websocket.
 *
 * <p>Remote desktop/spectrum/playlist control from the ThinLine admin UI is intentionally out of scope for VCE.</p>
 */
public class ThinlineRadioHealthReporter
{
    private static final Logger mLog = LoggerFactory.getLogger(ThinlineRadioHealthReporter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long STARTED_AT = System.currentTimeMillis();

    private final UserPreferences mUserPreferences;
    private final BroadcastModel mBroadcastModel;
    private final ThinlineRadioEndpoint mSocket = new ThinlineRadioEndpoint(this::onCommand, null);
    private final AtomicBoolean mSiteReporting = new AtomicBoolean();
    private ScheduledFuture<?> mFuture;

    public ThinlineRadioHealthReporter(UserPreferences userPreferences, BroadcastModel broadcastModel)
    {
        mUserPreferences = userPreferences;
        mBroadcastModel = broadcastModel;
    }

    public synchronized void start()
    {
        if(mFuture == null)
        {
            mFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(this::tick, 5L, 15L, TimeUnit.SECONDS);
        }
    }

    public synchronized void stop()
    {
        if(mFuture != null)
        {
            mFuture.cancel(false);
            mFuture = null;
        }
        if(mSiteReporting.getAndSet(false))
        {
            postSite(false);
        }
        mSocket.close();
    }

    private void tick()
    {
        try
        {
            ThinlineRadioPreference preference = mUserPreferences.getThinlineRadioPreference();
            boolean siteActive = preference.isEnabled() && !preference.getUrl().isBlank() &&
                !preference.getApiKey().isBlank();
            if(siteActive)
            {
                String socketUrl = ThinlineRadioSocket.socketUrl(preference.getUrl());
                if(socketUrl != null)
                {
                    mSocket.ensure(socketUrl, preference.getApiKey());
                }
                if(mSocket.isReady())
                {
                    postSite(true);
                    mSiteReporting.set(true);
                }
            }
            else
            {
                if(mSiteReporting.getAndSet(false))
                {
                    postSite(false);
                }
                mSocket.close();
            }
        }
        catch(RuntimeException exception)
        {
            mLog.debug("Thinline health tick failed", exception);
        }
    }

    private void postSite(boolean online)
    {
        if(!mSocket.isReady() && online)
        {
            return;
        }
        ThinlineRadioPreference preference = mUserPreferences.getThinlineRadioPreference();
        ObjectNode body = MAPPER.createObjectNode();
        body.put("type", "health");
        body.put("role", "health");
        body.put("key", preference.getApiKey());
        body.put("online", online);
        body.put("uptimeMs", System.currentTimeMillis() - STARTED_AT);
        body.put("application", "sdrtrunk-vce");
        body.set("alerts", alertFlags(preference));
        body.set("streams", streamSummary());
        mSocket.trySend(body);
    }

    private ObjectNode alertFlags(ThinlineRadioPreference preference)
    {
        ObjectNode alerts = MAPPER.createObjectNode();
        alerts.put("tuner", preference.isAlertTuner());
        alerts.put("control", preference.isAlertControl());
        alerts.put("uploader", preference.isAlertUploader());
        alerts.put("overflow", preference.isAlertOverflow());
        alerts.put("offline", preference.isAlertOffline());
        return alerts;
    }

    private ArrayNode streamSummary()
    {
        ArrayNode streams = MAPPER.createArrayNode();
        List<ConfiguredBroadcast> configured = mBroadcastModel.getConfiguredBroadcasts();
        for(ConfiguredBroadcast item : configured)
        {
            if(item.getBroadcastServerType() != BroadcastServerType.THINLINE_RADIO &&
                item.getBroadcastServerType() != BroadcastServerType.THINLINE_RADIO_CORE &&
                item.getBroadcastServerType() != BroadcastServerType.RDIOSCANNER_CALL)
            {
                continue;
            }
            ObjectNode stream = MAPPER.createObjectNode();
            stream.put("name", item.getBroadcastConfiguration().getName());
            stream.put("type", item.getBroadcastServerType().name());
            stream.put("enabled", item.getBroadcastConfiguration().isEnabled());
            if(item.getBroadcastConfiguration() instanceof RdioScannerConfiguration rdio)
            {
                stream.put("system", rdio.getSystemID());
            }
            AbstractAudioBroadcaster<?> broadcaster = mBroadcastModel.getBroadcaster(
                item.getBroadcastConfiguration().getConfigurationId());
            BroadcastState state = broadcaster != null ? broadcaster.getBroadcastState() : BroadcastState.READY;
            stream.put("state", state.name());
            streams.add(stream);
        }
        return streams;
    }

    private void onCommand(JsonNode command)
    {
        // Remote control commands are deferred for VCE; acknowledge unsupported commands politely.
        String name = command.path("command").asText("");
        if(name.isBlank() || "uploadCall".equals(name) || "discardCall".equals(name))
        {
            return;
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.put("type", "result");
        result.put("id", command.path("id").asText(""));
        result.put("ok", false);
        result.put("error", "Remote control of " + name + " is not available in SDRTrunk VCE");
        mSocket.send(result);
    }
}
