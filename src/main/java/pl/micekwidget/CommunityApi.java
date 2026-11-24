package pl.micekwidget;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Connection;
import org.jsoup.Jsoup;

final class CommunityApi {
    private static final String BASE = "https://micekapp.pl/api/";
    private final SharedPreferences cookies;

    CommunityApi(Context context) {
        cookies = context.getSharedPreferences("communitySession", Context.MODE_PRIVATE);
    }

    JSONObject object(String path) throws IOException, JSONException {
        return new JSONObject(request(path, Connection.Method.GET, null));
    }

    JSONArray array(String path) throws IOException, JSONException {
        return new JSONArray(request(path, Connection.Method.GET, null));
    }

    void post(String path, JSONObject data) throws IOException {
        request(path, Connection.Method.POST, data);
    }

    void delete(String path) throws IOException {
        request(path, Connection.Method.DELETE, null);
    }

    private synchronized String request(String path, Connection.Method method, JSONObject data) throws IOException {
        Connection connection = Jsoup.connect(BASE + path).method(method).timeout(15000)
                .ignoreContentType(true).ignoreHttpErrors(true).followRedirects(false)
                .maxBodySize(2000000).header("Accept", "application/json");
        for (Map.Entry<String, ?> entry : cookies.getAll().entrySet()) {
            if (entry.getValue() instanceof String) connection.cookie(entry.getKey(), (String) entry.getValue());
        }
        if (data != null) connection.header("Content-Type", "application/json").requestBody(data.toString());
        Connection.Response response = connection.execute();
        SharedPreferences.Editor edit = cookies.edit();
        for (Map.Entry<String, String> cookie : response.cookies().entrySet()) {
            if (cookie.getValue().isEmpty()) edit.remove(cookie.getKey());
            else edit.putString(cookie.getKey(), cookie.getValue());
        }
        edit.apply();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = "";
            try { message = LivePlan.text(new JSONObject(response.body()), "message"); }
            catch (JSONException ignored) {}
            throw new IOException(message.isEmpty() ? "Serwis zwrócił błąd " + response.statusCode() : message);
        }
        return response.body();
    }
}
