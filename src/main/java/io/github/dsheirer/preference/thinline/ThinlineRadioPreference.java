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
package io.github.dsheirer.preference.thinline;

import io.github.dsheirer.preference.Preference;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.sample.Listener;
import java.util.Objects;
import java.util.prefs.Preferences;

/**
 * Site-control preferences for Thinline Radio Core and SDR Health.
 */
public class ThinlineRadioPreference extends Preference
{
    private static final String ENABLED = "thinline.health.enabled";
    private static final String URL = "thinline.health.url";
    private static final String API_KEY = "thinline.health.api.key";
    private static final String ALERT_TUNER = "thinline.health.alert.tuner";
    private static final String ALERT_CONTROL = "thinline.health.alert.control";
    private static final String ALERT_UPLOADER = "thinline.health.alert.uploader";
    private static final String ALERT_OVERFLOW = "thinline.health.alert.overflow";
    private static final String ALERT_OFFLINE = "thinline.health.alert.offline";

    private final Preferences mPreferences;

    public ThinlineRadioPreference(Listener<PreferenceType> updateListener)
    {
        this(updateListener, Preferences.userNodeForPackage(ThinlineRadioPreference.class));
    }

    ThinlineRadioPreference(Listener<PreferenceType> updateListener, Preferences preferences)
    {
        super(updateListener);
        mPreferences = Objects.requireNonNull(preferences);
    }

    @Override
    public PreferenceType getPreferenceType()
    {
        return PreferenceType.THINLINE_RADIO;
    }

    public boolean isEnabled()
    {
        return mPreferences.getBoolean(ENABLED, false);
    }

    public void setEnabled(boolean enabled)
    {
        mPreferences.putBoolean(ENABLED, enabled);
        notifyPreferenceUpdated();
    }

    public String getUrl()
    {
        return mPreferences.get(URL, "");
    }

    public void setUrl(String url)
    {
        mPreferences.put(URL, url == null ? "" : url.trim());
        notifyPreferenceUpdated();
    }

    public String getApiKey()
    {
        return mPreferences.get(API_KEY, "");
    }

    public void setApiKey(String apiKey)
    {
        mPreferences.put(API_KEY, apiKey == null ? "" : apiKey.trim());
        notifyPreferenceUpdated();
    }

    public boolean isAlertTuner()
    {
        return mPreferences.getBoolean(ALERT_TUNER, true);
    }

    public void setAlertTuner(boolean alert)
    {
        mPreferences.putBoolean(ALERT_TUNER, alert);
        notifyPreferenceUpdated();
    }

    public boolean isAlertControl()
    {
        return mPreferences.getBoolean(ALERT_CONTROL, true);
    }

    public void setAlertControl(boolean alert)
    {
        mPreferences.putBoolean(ALERT_CONTROL, alert);
        notifyPreferenceUpdated();
    }

    public boolean isAlertUploader()
    {
        return mPreferences.getBoolean(ALERT_UPLOADER, true);
    }

    public void setAlertUploader(boolean alert)
    {
        mPreferences.putBoolean(ALERT_UPLOADER, alert);
        notifyPreferenceUpdated();
    }

    public boolean isAlertOverflow()
    {
        return mPreferences.getBoolean(ALERT_OVERFLOW, true);
    }

    public void setAlertOverflow(boolean alert)
    {
        mPreferences.putBoolean(ALERT_OVERFLOW, alert);
        notifyPreferenceUpdated();
    }

    public boolean isAlertOffline()
    {
        return mPreferences.getBoolean(ALERT_OFFLINE, true);
    }

    public void setAlertOffline(boolean alert)
    {
        mPreferences.putBoolean(ALERT_OFFLINE, alert);
        notifyPreferenceUpdated();
    }
}
