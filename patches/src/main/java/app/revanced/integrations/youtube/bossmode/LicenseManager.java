package app.revanced.integrations.youtube.bossmode;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * LO Boss Mode - License Manager
 * 
 * Ez az osztály kerül beinjektálásra a YouTube APK-ba a ReVanced patcher által.
 * Kezeli az előfizetési kulcs tárolását, validálását, és a prémium funkciók
 * engedélyezését/letiltását.
 * 
 * Működés:
 * 1. App induláskor meghívódik a `checkLicense()` metódus
 * 2. Lekéri az Android Hardware ID-t (ANDROID_ID)
 * 3. Ha van mentett kulcs, csendben validálja a szerveren
 * 4. Ha nincs kulcs, feldobja az aktivációs dialógust
 * 5. A szerver válasza alapján engedélyezi vagy letiltja a funkciókat
 */
public class LicenseManager {

    private static final String TAG = "BossMode";
    private static final String PREFS_NAME = "bossmode_prefs";
    private static final String KEY_LICENSE = "license_key";
    private static final String KEY_IS_VALID = "is_valid";
    private static final String KEY_EXPIRES_AT = "expires_at";

    // ============================================================
    // A TE SZERVERED URL-JE (CSERÉLD KI A RENDER.COM CÍMRE!)
    // ============================================================
    private static final String SERVER_URL = "https://patcher-server.onrender.com";

    private static boolean isPremiumActive = false;
    private static Context appContext = null;

    /**
     * Ez a fő belépési pont, amit a patch meghív a YouTube indításakor.
     * 
     * @param activity A YouTube MainActivity instance-a.
     */
    public static void checkLicense(Activity activity) {
        appContext = activity.getApplicationContext();
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String savedKey = prefs.getString(KEY_LICENSE, null);

        if (savedKey == null || savedKey.isEmpty()) {
            // Nincs mentett kulcs -> Mutassuk az aktivációs ablakot
            showActivationDialog(activity);
        } else {
            // Van mentett kulcs -> Csendben ellenőrizzük a háttérben
            silentCheck(activity);
        }
    }

    /**
     * Ez a metódus adja vissza, hogy a prémium funkciók aktívak-e.
     * A ReVanced patch-ek ezt fogják hívni, mielőtt bármit csinálnának.
     * 
     * Pl. az AdBlock patch:
     *   if (!LicenseManager.isPremium()) { return; } // ne blokkolja a reklámot
     */
    public static boolean isPremium() {
        if (appContext == null) return false;

        // Fallback: ha a hálózati ellenőrzés még nem futott le,
        // használjuk a helyben tárolt értéket
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean localValid = prefs.getBoolean(KEY_IS_VALID, false);

        // Ellenőrizzük a lejárati dátumot is
        long expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0);
        if (expiresAt > 0 && System.currentTimeMillis() > expiresAt) {
            // Lejárt!
            prefs.edit().putBoolean(KEY_IS_VALID, false).apply();
            return false;
        }

        return isPremiumActive || localValid;
    }

    /**
     * Lekéri az egyedi Android ID-t (Hardware ID).
     * Ez az a szám, ami az adott eszközt azonosítja.
     */
    @SuppressLint("HardwareIds")
    private static String getHardwareId(Context context) {
        return Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ANDROID_ID
        );
    }

    /**
     * Felugró ablak, ahol a felhasználó beírhatja a licenszkulcsot.
     */
    private static void showActivationDialog(Activity activity) {
        activity.runOnUiThread(() -> {
            // Programmatikusan hozzuk létre a dialógust (nincs szükség XML-re)
            EditText input = new EditText(activity);
            input.setHint("LO-XXXXXXXX-XXXXXXXX");
            input.setPadding(50, 30, 50, 30);
            input.setTextSize(16);

            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setTitle("🔑 Boss Mode Aktiválás");
            builder.setMessage("Írd be az előfizetői kulcsodat a prémium funkciók feloldásához.\n\nKulcs nélkül a reklámok és korlátozások aktívak maradnak.");
            builder.setView(input);
            builder.setCancelable(true); // Bezárható, de akkor maradnak a reklámok

            builder.setPositiveButton("Aktiválás", (dialog, which) -> {
                String key = input.getText().toString().trim();
                if (!key.isEmpty()) {
                    activateKey(activity, key);
                } else {
                    Toast.makeText(activity, "Üres kulcs! Adj meg egy érvényes kulcsot.", Toast.LENGTH_SHORT).show();
                }
            });

            builder.setNegativeButton("Később", (dialog, which) -> {
                // Ha nem adnak meg kulcsot, a funkciók inaktívak maradnak
                isPremiumActive = false;
                dialog.dismiss();
            });

            builder.show();
        });
    }

    /**
     * Aktiválja a kulcsot a szerveren és hozzáköti az eszközhöz.
     */
    private static void activateKey(Activity activity, String key) {
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... voids) {
                try {
                    String hardwareId = getHardwareId(activity);
                    JSONObject body = new JSONObject();
                    body.put("key", key);
                    body.put("hardwareId", hardwareId);

                    URL url = new URL(SERVER_URL + "/api/activate");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);

                    OutputStream os = conn.getOutputStream();
                    os.write(body.toString().getBytes("UTF-8"));
                    os.flush();
                    os.close();

                    int responseCode = conn.getResponseCode();
                    BufferedReader br;
                    if (responseCode >= 200 && responseCode < 300) {
                        br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    } else {
                        br = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                    }

                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line);
                    }
                    br.close();
                    conn.disconnect();

                    return response.toString();

                } catch (Exception e) {
                    Log.e(TAG, "Activation error: " + e.getMessage());
                    return "{\"error\":\"Hálózati hiba. Ellenőrizd az internet kapcsolatot!\"}";
                }
            }

            @Override
            protected void onPostExecute(String result) {
                try {
                    JSONObject json = new JSONObject(result);

                    if (json.has("success") && json.getBoolean("success")) {
                        // SIKER! Mentjük a kulcsot és az állapotot
                        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        SharedPreferences.Editor editor = prefs.edit();
                        editor.putString(KEY_LICENSE, key);
                        editor.putBoolean(KEY_IS_VALID, true);

                        if (json.has("expiresAt")) {
                            // ISO 8601 dátumot UTC milliszekundumra konvertáljuk (egyszerűsített)
                            String expiresStr = json.getString("expiresAt");
                            // Tároljuk string-ként is, de számítsuk ki a timestamp-et
                            try {
                                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                                java.util.Date date = sdf.parse(expiresStr);
                                if (date != null) {
                                    editor.putLong(KEY_EXPIRES_AT, date.getTime());
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Date parse error: " + e.getMessage());
                            }
                        }

                        editor.apply();
                        isPremiumActive = true;

                        Toast.makeText(activity, "✅ Boss Mode AKTÍV! Élvezd a prémium funkciókat!", Toast.LENGTH_LONG).show();

                    } else {
                        // HIBA
                        String errorMsg = json.optString("error", "Ismeretlen hiba történt.");
                        isPremiumActive = false;
                        Toast.makeText(activity, "❌ " + errorMsg, Toast.LENGTH_LONG).show();
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Response parse error: " + e.getMessage());
                    Toast.makeText(activity, "❌ Hiba a szerver válaszában.", Toast.LENGTH_SHORT).show();
                }
            }
        }.execute();
    }

    /**
     * Csendben, a háttérben ellenőrzi a meglévő licenszt.
     * Nem zavar dialógussal - ha lejárt, egyszerűen kikapcsolja a prémiumot.
     */
    private static void silentCheck(Activity activity) {
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... voids) {
                try {
                    String hardwareId = getHardwareId(activity);
                    JSONObject body = new JSONObject();
                    body.put("hardwareId", hardwareId);

                    URL url = new URL(SERVER_URL + "/api/check");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);

                    OutputStream os = conn.getOutputStream();
                    os.write(body.toString().getBytes("UTF-8"));
                    os.flush();
                    os.close();

                    int responseCode = conn.getResponseCode();
                    if (responseCode >= 200 && responseCode < 300) {
                        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) {
                            response.append(line);
                        }
                        br.close();
                        conn.disconnect();

                        JSONObject json = new JSONObject(response.toString());
                        return json.optBoolean("valid", false);
                    }

                    conn.disconnect();
                    return false;

                } catch (Exception e) {
                    Log.e(TAG, "Silent check error: " + e.getMessage());
                    // Ha nem éri el a szervert, használjuk a helyi értéket
                    SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    return prefs.getBoolean(KEY_IS_VALID, false);
                }
            }

            @Override
            protected void onPostExecute(Boolean isValid) {
                isPremiumActive = isValid;
                SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().putBoolean(KEY_IS_VALID, isValid).apply();

                if (!isValid) {
                    Log.w(TAG, "License is not valid or expired. Premium features disabled.");
                } else {
                    Log.i(TAG, "License validated. Boss Mode is ON.");
                }
            }
        }.execute();
    }

    /**
     * Törli a helyben tárolt licensz adatokat.
     * Hasznos, ha a felhasználó ki akar lépni az előfizetésből.
     */
    public static void clearLicense(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
        isPremiumActive = false;
    }
}
