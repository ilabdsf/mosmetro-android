/**
 * Wi-Fi в метро (pw.thedrhax.mosmetro, Moscow Wi-Fi autologin)
 * Copyright © 2015 Dmitry Karikh <the.dr.hax@gmail.com>
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package pw.thedrhax.mosmetro.authenticator;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import pw.thedrhax.mosmetro.R;
import pw.thedrhax.mosmetro.authenticator.networks.Air_WiFi_Free;
import pw.thedrhax.mosmetro.authenticator.networks.MT_FREE;
import pw.thedrhax.mosmetro.authenticator.networks.MosGorTrans;
import pw.thedrhax.mosmetro.updater.URLs;
import pw.thedrhax.mosmetro.authenticator.networks.AURA;
import pw.thedrhax.mosmetro.authenticator.networks.MosMetro;
import pw.thedrhax.mosmetro.httpclient.CachedRetriever;
import pw.thedrhax.mosmetro.httpclient.Client;
import pw.thedrhax.mosmetro.httpclient.clients.OkHttp;
import pw.thedrhax.mosmetro.updater.NewsChecker;
import pw.thedrhax.util.AndroidHacks;
import pw.thedrhax.util.Logger;
import pw.thedrhax.util.Util;
import pw.thedrhax.util.Version;

import java.util.HashMap;
import java.util.Map;

public abstract class Authenticator {
    public static final Class<? extends Authenticator>[] SUPPORTED_NETWORKS =
            new Class[] {
                    MosMetro.class, AURA.class, MT_FREE.class,
                    MosGorTrans.class, Air_WiFi_Free.class
            };

    // Result state
    public static enum RESULT {
        CONNECTED, ALREADY_CONNECTED, // Success
        NOT_REGISTERED, ERROR, // Fail
        CAPTCHA, // Needs user's interaction == Stopped
        INTERRUPTED, UNSUPPORTED // Stopped
    }

    // Network check state
    public static enum CHECK {
        CONNECTED, WRONG_NETWORK, NOT_CONNECTED
    }

	protected Logger logger;
    protected Client client;
    protected boolean stopped;

    // Device info
    protected Context context;
    protected SharedPreferences settings;
    protected int pref_retry_count;

    public Authenticator (Context context) {
        logger = new Logger();
        client = new OkHttp();

        this.context = context;
        this.settings = PreferenceManager.getDefaultSharedPreferences(context);
        this.pref_retry_count = Util.getIntPreference(settings, "pref_retry_count", 3);
    }

    public abstract String getSSID();

    public RESULT start() {
        stopped = false;

        AndroidHacks.bindToWiFi(context);

        logger.log(String.format(
                context.getString(R.string.version), new Version(context).getFormattedVersion()
        ));
        RESULT result = connect();

        if (stopped) return result;

        if (result == RESULT.CONNECTED || result == RESULT.ALREADY_CONNECTED)
            submit_info(result);

        return result;
    }

    public void stop() {
        stopped = true;
    }

    /*
     * Logging
     */

    public void setLogger(Logger logger) {
        this.logger = logger;
    }

    /*
     * Connection sequence
     */

    public CHECK isConnected() {
        if (!settings.getBoolean("pref_internet_check_strict", false))
            return CHECK.CONNECTED;

        Client client = new OkHttp();
        try {
            client.get("http://google.ru/generate_204", null);
        } catch (Exception ex) {
            if (client.getResponseCode() == 204)
                return CHECK.CONNECTED;
        }

        return CHECK.NOT_CONNECTED;
    }

    protected abstract RESULT connect();

    /*
     * Progress reporting
     */

    protected ProgressListener progressListener = new ProgressListener() {
        @Override
        public void onProgressUpdate(int progress) {

        }
    };

    public void setProgressListener (ProgressListener listener) {
        progressListener = listener;
    }

    public interface ProgressListener {
        void onProgressUpdate(int progress);
    }

    /*
     * System info
     */

    private void submit_info (RESULT result) {
        String STATISTICS_URL = new CachedRetriever(context)
                .get(URLs.API_URL_SOURCE, URLs.API_URL_DEFAULT) + URLs.API_REL_STATISTICS;

        Map<String,String> params = new HashMap<String, String>();
        params.put("version", new Version(context).getFormattedVersion());
        params.put("success", result == RESULT.CONNECTED ? "true" : "false");
        params.put("ssid", getSSID());

        try {
            new OkHttp().post(STATISTICS_URL, params);
        } catch (Exception ignored) {}

        if (settings.getBoolean("pref_notify_news", true))
            new NewsChecker(context).check();
    }
}