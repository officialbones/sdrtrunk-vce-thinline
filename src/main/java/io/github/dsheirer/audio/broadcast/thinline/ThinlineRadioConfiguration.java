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
 * Call upload configuration for Thinline Radio.
 *
 * <p>Thinline Radio accepts the same completed-call upload used by Rdio Scanner, including the system id, API key,
 * and /api/call-upload path.</p>
 */
public class ThinlineRadioConfiguration extends RdioScannerConfiguration
{
    public ThinlineRadioConfiguration()
    {
        this(BroadcastFormat.MP3);
    }

    public ThinlineRadioConfiguration(BroadcastFormat format)
    {
        super(format);
    }

    @JacksonXmlProperty(isAttribute = true, localName = "type", namespace = "http://www.w3.org/2001/XMLSchema-instance")
    @Override
    public BroadcastServerType getBroadcastServerType()
    {
        return BroadcastServerType.THINLINE_RADIO;
    }

    @Override
    public BroadcastConfiguration copyOf()
    {
        ThinlineRadioConfiguration copy = new ThinlineRadioConfiguration();
        copy.setName(getName());
        copy.setHost(getHost());
        copy.setSystemID(getSystemID());
        copy.setApiKey(getApiKey());
        copy.setEnabled(isEnabled());
        copy.setMaximumRecordingAge(getMaximumRecordingAge());
        return copy;
    }
}
