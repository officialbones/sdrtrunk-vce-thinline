/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.dsheirer.audio.broadcast.thinline.ThinlineRadioSocket;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.thinline.ThinlineRadioPreference;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Browser API for ThinLine site-control preferences used by Core streaming. */
public final class ThinlinePreferencesHttpController
{
    public static final String PATH = "/api/v1/admin/thinline-preferences";
    public static final String TEST_PATH = "/api/v1/admin/thinline-preferences/test";
    private static final Logger mLog = LoggerFactory.getLogger(ThinlinePreferencesHttpController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final UserPreferences mPreferences;

    public ThinlinePreferencesHttpController(UserPreferences preferences)
    {
        mPreferences = Objects.requireNonNull(preferences);
    }

    public HttpHandler settingsHandler()
    {
        return exchange -> {
            try
            {
                String method = exchange.getRequestMethod();
                if("GET".equalsIgnoreCase(method))
                {
                    send(exchange, 200, snapshot());
                    return;
                }
                if("PUT".equalsIgnoreCase(method) || "POST".equalsIgnoreCase(method))
                {
                    @SuppressWarnings("unchecked")
                    Map<String,Object> body = MAPPER.readValue(exchange.getRequestBody().readAllBytes(), Map.class);
                    apply(body);
                    send(exchange, 200, snapshot());
                    return;
                }
                exchange.sendResponseHeaders(405, -1);
            }
            catch(IllegalArgumentException exception)
            {
                sendError(exchange, 400, exception.getMessage());
            }
            catch(Exception exception)
            {
                mLog.warn("Thinline preferences request failed", exception);
                sendError(exchange, 500, "Unable to update Thinline preferences");
            }
            finally
            {
                exchange.close();
            }
        };
    }

    public HttpHandler testHandler()
    {
        return exchange -> {
            try
            {
                if(!"POST".equalsIgnoreCase(exchange.getRequestMethod()))
                {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                ThinlineRadioPreference preference = mPreferences.getThinlineRadioPreference();
                String error = ThinlineRadioSocket.testConnection(preference.getUrl(), preference.getApiKey());
                Map<String,Object> result = new LinkedHashMap<>();
                result.put("ok", error == null);
                result.put("error", error);
                send(exchange, 200, result);
            }
            catch(Exception exception)
            {
                mLog.warn("Thinline connection test failed", exception);
                sendError(exchange, 500, "Unable to test Thinline connection");
            }
            finally
            {
                exchange.close();
            }
        };
    }

    private Map<String,Object> snapshot()
    {
        ThinlineRadioPreference preference = mPreferences.getThinlineRadioPreference();
        Map<String,Object> settings = new LinkedHashMap<>();
        settings.put("enabled", preference.isEnabled());
        settings.put("url", preference.getUrl());
        settings.put("api_key_configured", preference.getApiKey() != null && !preference.getApiKey().isBlank());
        settings.put("alert_tuner", preference.isAlertTuner());
        settings.put("alert_control", preference.isAlertControl());
        settings.put("alert_uploader", preference.isAlertUploader());
        settings.put("alert_overflow", preference.isAlertOverflow());
        settings.put("alert_offline", preference.isAlertOffline());
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("settings", settings);
        return body;
    }

    private void apply(Map<String,Object> body)
    {
        if(body == null)
        {
            throw new IllegalArgumentException("Settings are required");
        }
        @SuppressWarnings("unchecked")
        Map<String,Object> settings = body.containsKey("settings") && body.get("settings") instanceof Map<?,?> ?
            (Map<String,Object>)body.get("settings") : body;
        ThinlineRadioPreference preference = mPreferences.getThinlineRadioPreference();
        if(settings.containsKey("enabled"))
        {
            preference.setEnabled(requireBoolean(settings.get("enabled"), "enabled"));
        }
        if(settings.containsKey("url"))
        {
            preference.setUrl(requireString(settings.get("url"), "url"));
        }
        if(settings.containsKey("api_key") && settings.get("api_key") instanceof String text)
        {
            preference.setApiKey(text);
        }
        if(settings.containsKey("alert_tuner"))
        {
            preference.setAlertTuner(requireBoolean(settings.get("alert_tuner"), "alert_tuner"));
        }
        if(settings.containsKey("alert_control"))
        {
            preference.setAlertControl(requireBoolean(settings.get("alert_control"), "alert_control"));
        }
        if(settings.containsKey("alert_uploader"))
        {
            preference.setAlertUploader(requireBoolean(settings.get("alert_uploader"), "alert_uploader"));
        }
        if(settings.containsKey("alert_overflow"))
        {
            preference.setAlertOverflow(requireBoolean(settings.get("alert_overflow"), "alert_overflow"));
        }
        if(settings.containsKey("alert_offline"))
        {
            preference.setAlertOffline(requireBoolean(settings.get("alert_offline"), "alert_offline"));
        }
    }

    private static boolean requireBoolean(Object value, String field)
    {
        if(!(value instanceof Boolean flag))
        {
            throw new IllegalArgumentException(field + " must be true or false");
        }
        return flag;
    }

    private static String requireString(Object value, String field)
    {
        if(!(value instanceof String text) || text.indexOf('\0') >= 0)
        {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return text.trim();
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException
    {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException
    {
        byte[] bytes = ("{\"error\":\"" + message.replace("\"", "'") + "\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
