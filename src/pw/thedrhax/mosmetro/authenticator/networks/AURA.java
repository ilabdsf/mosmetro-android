package pw.thedrhax.mosmetro.authenticator.networks;

import android.content.Context;

public class AURA extends MosMetro {
	public static String SSID = "AURA";
	
	public AURA (Context context) {
		super(context);
	}
	
	@Override
	public String getSSID() {
		return "AURA";
	}
}