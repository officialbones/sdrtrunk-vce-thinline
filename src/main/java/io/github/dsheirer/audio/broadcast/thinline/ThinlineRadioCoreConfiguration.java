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

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.audio.broadcast.BroadcastServerType;
import io.github.dsheirer.audio.broadcast.rdioscanner.RdioScannerConfiguration;

/**
 * Thinline Radio Core stream configuration.
 *
 * <p>Core destinations hold completed calls and upload only when ThinLine asks. The per-stream API key is unused;
 * the site-control key from Thinline preferences authenticates the websocket and Core uploads.</p>
 */
public class ThinlineRadioCoreConfiguration extends RdioScannerConfiguration
{
    public ThinlineRadioCoreConfiguration()
    {
        this(BroadcastFormat.MP3);
    }

    public ThinlineRadioCoreConfiguration(BroadcastFormat format)
    {
        super(format);
    }

    @JacksonXmlProperty(isAttribute = true, localName = "type", namespace = "http://www.w3.org/2001/XMLSchema-instance")
    @Override
    public BroadcastServerType getBroadcastServerType()
    {
        return BroadcastServerType.THINLINE_RADIO_CORE;
    }

    /**
     * Core destinations are valid when a host and system id are present. The site-control API key lives in
     * preferences, so an empty per-stream key is allowed.
     */
    @Override
    public boolean isValid()
    {
        return getSystemID() > 0 && getHost() != null && !getHost().isBlank();
    }

    @Override
    public BroadcastConfiguration copyOf()
    {
        ThinlineRadioCoreConfiguration copy = new ThinlineRadioCoreConfiguration();
        copy.setName(getName());
        copy.setHost(getHost());
        copy.setSystemID(getSystemID());
        copy.setApiKey(getApiKey());
        copy.setEnabled(isEnabled());
        copy.setMaximumRecordingAge(getMaximumRecordingAge());
        return copy;
    }
}
