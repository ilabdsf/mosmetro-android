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
import android.content.Intent;

import pw.thedrhax.mosmetro.R;
import pw.thedrhax.util.Logger;
import pw.thedrhax.util.WifiUtils;

public class Chooser {
    private Context context;
    private Logger logger;
    private WifiUtils wifi;

    public Chooser(Context context, Logger logger) {
        this.logger = logger;
        this.context = context;
        wifi = new WifiUtils(context);
    }

    public Authenticator choose (String SSID) {
        if (SSID == null) return choose(wifi.getSSID());

        logger.log(String.format(context.getString(R.string.chooser_searching), SSID));

        // Trying to match one of Authenticators for this SSID
        Class<? extends Authenticator> result_class = null;
        for (Class<? extends Authenticator> network : Authenticator.SUPPORTED_NETWORKS) {
            try {
                String class_ssid = (String) network.getField("SSID").get(network);
                if ((SSID.equalsIgnoreCase(class_ssid))) {
                    result_class = network; break;
                }
            } catch (Exception ignored) {}
        }

        if (result_class == null) {
            logger.log(String.format(context.getString(R.string.error),
                    context.getString(R.string.chooser_not_supported)
            ));
            return null;
        }

        // Make instance of matched Authenticator
        Authenticator result;
        try {
            result = result_class
                    .getConstructor(Context.class)
                    .newInstance(context);
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }

        return result;
    }

    public Authenticator choose (Intent intent) {
        return choose(wifi.getSSID(intent));
    }
}
