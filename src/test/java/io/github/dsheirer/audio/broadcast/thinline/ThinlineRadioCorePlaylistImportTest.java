/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.broadcast.thinline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.broadcast.BroadcastServerType;
import io.github.dsheirer.audio.broadcast.StreamingConfigurationCodec;
import io.github.dsheirer.database.configuration.ConfigurationDatabaseStore;
import io.github.dsheirer.database.importer.LegacyXmlConfigurationImporter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThinlineRadioCorePlaylistImportTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void importsRedactedThinlineRadioCoreStreamsFromLegacyPlaylist() throws Exception
    {
        Path xml = mTemporaryFolder.resolve("thinline-core-redacted.xml");
        Files.writeString(xml, """
            <playlist version="4">
              <stream type="ThinlineRadioCoreConfiguration" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="THINLINE_RADIO_CORE" system_id="9" api_key="" name="CATCHALL" enabled="true" host="http://localhost:3000/api/call-upload" port="80" delay="0" maximum_recording_age="600000">
                <format>MP3</format>
              </stream>
              <stream type="ThinlineRadioCoreConfiguration" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="THINLINE_RADIO_CORE" system_id="1" api_key="" name="SCANNER - MAIN - DELAWARE" enabled="true" host="http://localhost:3000/api/call-upload" port="80" delay="0" maximum_recording_age="600000">
                <format>MP3</format>
              </stream>
              <stream type="ThinlineRadioConfiguration" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="THINLINE_RADIO" system_id="2" api_key="redacted-upload-key" name="Thinline Radio - RANDOLPH" enabled="false" host="https://scanner.example/api/call-upload" port="80" delay="0" maximum_recording_age="600000">
                <format>MP3</format>
              </stream>
            </playlist>
            """);

        Path database = mTemporaryFolder.resolve("thinline-core.sqlite");
        LegacyXmlConfigurationImporter.importPlaylist(xml, database);

        var streams = new ConfigurationDatabaseStore(database).load().broadcastConfigurations();
        assertEquals(3, streams.size());

        ThinlineRadioCoreConfiguration catchall = assertInstanceOf(ThinlineRadioCoreConfiguration.class,
            streams.stream().filter(stream -> "CATCHALL".equals(stream.getName())).findFirst().orElseThrow());
        assertEquals(BroadcastServerType.THINLINE_RADIO_CORE, catchall.getBroadcastServerType());
        assertEquals(9, catchall.getSystemID());
        assertTrue(catchall.getApiKey() == null || catchall.getApiKey().isBlank());
        assertTrue(catchall.isEnabled());
        assertTrue(catchall.isValid());

        ThinlineRadioCoreConfiguration delaware = assertInstanceOf(ThinlineRadioCoreConfiguration.class,
            streams.stream().filter(stream -> "SCANNER - MAIN - DELAWARE".equals(stream.getName())).findFirst()
                .orElseThrow());
        assertEquals(1, delaware.getSystemID());

        ThinlineRadioConfiguration legacy = assertInstanceOf(ThinlineRadioConfiguration.class,
            streams.stream().filter(stream -> "Thinline Radio - RANDOLPH".equals(stream.getName())).findFirst()
                .orElseThrow());
        assertEquals(BroadcastServerType.THINLINE_RADIO, legacy.getBroadcastServerType());
        assertEquals("redacted-upload-key", legacy.getApiKey());
    }

    @Test
    void streamingAdminCanEnableCoreWithoutPerStreamApiKey()
    {
        var template = StreamingConfigurationCodec.template("THINLINE_RADIO_CORE");
        var enabled = StreamingConfigurationCodec.apply(template, Map.of(
            "name", "CATCHALL",
            "host", "http://localhost:3000/api/call-upload",
            "system_id", 9,
            "enabled", true));
        assertTrue(enabled.isEnabled());
        assertTrue(enabled.isValid());
        assertTrue(StreamingConfigurationCodec.view(enabled).configuredCredentials().isEmpty());
        assertTrue(StreamingConfigurationCodec.providers().stream()
            .anyMatch(provider -> "THINLINE_RADIO_CORE".equals(provider.id())));
        assertTrue(StreamingConfigurationCodec.providers().stream()
            .anyMatch(provider -> "THINLINE_RADIO".equals(provider.id())));
    }
}
