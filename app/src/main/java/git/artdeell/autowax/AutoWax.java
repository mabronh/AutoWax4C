package git.artdeell.autowax;

import android.util.Log;

import git.artdeell.aw4c.CanvasMain;
import git.artdeell.aw4c.Locale;
import local.json.JSONArray;
import local.json.JSONException;
import local.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

public class AutoWax {
    private static String API_HOST = "live.radiance.thatgamecompany.com";
    private static String userAgent;

    public final Object sessionLock = new Object();
    String userid = null;
    String session = null;
    public static void initWithParameters(int version, boolean isBeta) {
        API_HOST = isBeta ? "beta.radiance.thatgamecompany.com" :  "live.radiance.thatgamecompany.com";
        userAgent = isBeta ? "Sky-Test-com.tgc.sky.android.test./0.15.1."+version+" (unknown; android 30.0.0; en)":"Sky-Live-com.tgc.sky.android/0.15.1."+ version+" (unknown; android 30.0.0; en)";
    }
    public void resetSession(String userid, String session) {
        this.userid = userid;
        this.session = session;
        synchronized (sessionLock) {sessionLock.notifyAll();}
    }
    public JSONObject genInitial() throws JSONException {
        JSONObject ret = new JSONObject();
        ret.put("user", userid);
        ret.put("session", session);
        return ret;
    }
    private static JSONObject _doPost(String sid, String uid, String path, String postData)  throws SkyProtocolException {
        try {
            HttpsURLConnection conn = (HttpsURLConnection) new URL("https://" + API_HOST + path).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("User-Agent", userAgent);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Content-Length", postData.getBytes(StandardCharsets.UTF_8).length + "");
            conn.setRequestProperty("Host", API_HOST);
            if(sid != null) conn.setRequestProperty("session",sid);
            if(uid != null) conn.setRequestProperty("user-id",uid);
            conn.setUseCaches(false);
            conn.setDoInput(true);
            conn.setDoOutput(true);
            conn.connect();
            try (OutputStream wr = conn.getOutputStream()) {
                wr.write(postData.getBytes(StandardCharsets.UTF_8));
            }
            if(conn.getResponseCode() == 401) {
                throw new LostSessionException("");
            }
            Log.i("ErrorCode",conn.getResponseCode()+"");
            try (InputStream rd = conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
                if (rd == null) return new JSONObject();
                StringBuilder ret = new StringBuilder();
                int cpt;
                byte[] buf = new byte[1024];
                while ((cpt = rd.read(buf)) != -1) {
                    ret.append(new String(buf, 0, cpt));
                }
                if (ret.toString().isEmpty()) return new JSONObject();
                return new JSONObject(ret.toString());
                //return ret;
            }
        } catch (IOException ex) {
            ex.printStackTrace();
            SkyProtocolException twr = new SkyProtocolException("An IOException was raised during request");
            twr.initCause(ex);
            throw twr;
        }
    }
    public JSONObject doPost(String path, JSONObject postData) throws SkyProtocolException{
        boolean doesRequireRerequest = true;
        JSONObject resp = null;
        while(doesRequireRerequest) {
            try {
                doesRequireRerequest = false;
                resp = _doPost(session, userid, path, postData.toString());
                if(resp.has("result") && resp.get("result").equals("timeout")) {
                    System.out.println("TGCDB timed out");
                    doesRequireRerequest = true;
                }
            }catch (LostSessionException e) {
                doesRequireRerequest = true;
                System.out.println("We lost session!");
                try {
                    CanvasMain.goReauthorize();
                    System.out.println("Reauthorization fired!");
                    synchronized (sessionLock) { sessionLock.wait(); }
                    if(postData.has("user")) postData.put("user",userid);
                    if(postData.has("session")) postData.put("session",session);
                }catch (InterruptedException _e) {throw new SkyProtocolException("Interrupted!");}
            }
        }
        System.out.println(resp);
        return resp;
    }
    private void forgeWithOutput(String source, String destination, JSONObject currency, int rate, int localeString) throws SkyProtocolException{
        int waxCount = currency.optInt(source, 0);
        int candleCount = currency.optInt(destination, 0);
        if(waxCount >= rate) {
            JSONObject forgeRq = genInitial();
            forgeRq.put("currency", source);
            forgeRq.put("forge_currency", destination);
            forgeRq.put("count", waxCount / rate);
            forgeRq.put("cost", rate);
            JSONObject resp = doPost("/account/buy_candle_wax", forgeRq);
            if (resp.optString("result", "").equals("ok")) {
                if(resp.has("currency")) {
                    candleCount = resp.getJSONObject("currency").getInt(destination) ;
                }
                CanvasMain.submitLogString(Locale.get(localeString, candleCount));
            } else {
                CanvasMain.submitLogString(Locale.get(Locale.C_CONVERSION_FAILED , resp.optString("result", "Unknown error")));
            }
        }else{
            CanvasMain.submitLogString(Locale.get(localeString, candleCount));
        }
    }
    public void doCandleRun() {
        CRArray.init();
        ThreadPoolExecutor tpe = new ThreadPoolExecutor(4,4,200, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        int maxProgress=0;
        CanvasMain.submitLogString(Locale.get(Locale.C_RUNNING));
        for(Object o : CRArray.LEVELS) {
            JSONObject level = (JSONObject) o;
            long levelId = level.getLong("level_id");
            JSONArray candles = level.getJSONArray("pickup_ids");
            JSONObject intermediaryBatch = null;
            for(int i = 0; i < candles.length(); i++) {
                if(i % 16 == 0) {
                    if(intermediaryBatch != null) {
                        intermediaryBatch.put("level_id", levelId);
                        tpe.execute(new CandleRunBatch(this,intermediaryBatch));
                        maxProgress++;
                    }
                    intermediaryBatch = genInitial();
                }
                intermediaryBatch.append("pickup_ids",candles.get(i));
            }
        }
        tpe.shutdown();
        try {
            while(!tpe.awaitTermination(50, TimeUnit.MILLISECONDS)) {
                //Log.log("MachineDispatch-Runner","Running for candles...");
                CanvasMain.submitProgressBar(((tpe.getActiveCount()+tpe.getQueue().size()*-1)+maxProgress),maxProgress);
            }
        } catch (InterruptedException ignored) {}
        try {
            JSONObject currency = doPost("/account/get_currency", genInitial());
            forgeWithOutput("wax","candles",currency, 150, Locale.C_CANDLE_PRINT_REGULAR);
            forgeWithOutput("season_wax","season_candle",currency, 12, Locale.C_CANDLE_PRINT_SEASON);
        }catch (Exception e) {
            CanvasMain.submitLogString(Locale.get(Locale.C_CONVERSION_FAILED, e.toString()));
        }
        CanvasMain.submitProgressBar(0, -1);
    }
    public void doQuests() {
        try {
            JSONArray quests = doPost("/account/get_season_quests", genInitial()).getJSONArray("season_quests");
            int questsLength = quests.length();

            for (int i = 0; i < questsLength; i++) {
                JSONObject quest = quests.getJSONObject(i);
                if (!quest.getBoolean("activated")) {
                    JSONObject questrq = genInitial();
                    questrq.put("quest_id", quest.getString("daily_quest_def_id"));
                    JSONObject questrsp = doPost("/account/activate_season_quest", questrq);
                    if(questrsp == null) continue;
                    JSONArray questUpdates = questrsp.getJSONArray("season_quest_activated");
                    for (int j = 0; j < questUpdates.length(); j++) {
                        JSONObject _quest = questUpdates.getJSONObject(j);
                        if (quest.getString("daily_quest_def_id").equals(_quest.getString("quest_id"))) {
                            quest.put("activated", true);
                            quest.put("start_value", _quest.getDouble("start_value"));
                            CanvasMain.submitLogString(Locale.get(Locale.Q_DATA_REFRESHED, _quest.getString("quest_id")));
                            //appendlnToLog("Quest data refreshed: " + _quest.getString("quest_id"));
                        }
                    }
                }
                CanvasMain.submitProgressBar(i+1, questsLength*2);
            }
            CanvasMain.submitLogString(Locale.get(Locale.Q_ACTIVATED));
            for (int i = 0; i < questsLength; i++) {
                JSONObject quest = quests.getJSONObject(i);
                double result = upstatAndClaim(quest,0);
                if(result > 0)
                    upstatAndClaim(quest,result);
                CanvasMain.submitProgressBar(i+questsLength+1, questsLength*2);
            }
        }catch (Exception e) {
            CanvasMain.submitLogString(Locale.get(Locale.G_EXCEPTION, e.toString()));
        }
        CanvasMain.submitProgressBar(0, -1);
    }
    private double upstatAndClaim(JSONObject quest, double source) throws SkyProtocolException {
        if (quest.has("stat_type")) {
            double initialStat = source == 0? quest.getDouble("start_value"):source;
            double statMod = quest.has("stat_delta") ? quest.getDouble("stat_delta") : 30;
            JSONObject statChangeRq = genInitial();
            JSONObject stat = new JSONObject();
            stat.put("type", quest.getString("stat_type"));
            stat.put("value", (int) (initialStat + statMod));
            JSONArray statArray = new JSONArray();
            statArray.put(stat);
            statChangeRq.put("achievement_stats", statArray);
            if (doPost( "/account/set_achievement_stats", statChangeRq) != null)
                CanvasMain.submitLogString(Locale.get(Locale.Q_STAT_SET, quest.get("stat_type")));
            JSONObject questClaimRq = genInitial();
            questClaimRq.put("quest_id", quest.getString("daily_quest_def_id"));
            JSONObject obj = doPost( "/account/claim_season_quest_reward", questClaimRq);
            String claimResult = obj.has("season_quest_claim_result")?obj.getString("season_quest_claim_result"):null;
            if ("ok".equals(claimResult) || "already".equals(claimResult)) {
                if(obj.has("currency")) {
                    JSONObject currency = obj.getJSONObject("currency");
                    CanvasMain.submitLogString(Locale.get(Locale.Q_CURRENCY,claimResult,currency.has("season_candle")?currency.getInt("season_candle"):0,currency.has("candles")?currency.getInt("candles"):0));
                }else{
                    CanvasMain.submitLogString(Locale.get(Locale.Q_NO_CURRENCY));
                }
                return 0;
            } else {
                CanvasMain.submitLogString(Locale.get(Locale.Q_DENIED));
                return initialStat + statMod;
            }
        }else{
            return -1;
        }
    }
    public void runGift() {
        try {
            JSONObject friendRq = genInitial();
            friendRq.put("max", 1024);
            friendRq.put("sort_ver", 1);
            JSONArray friendList = doPost("/account/get_friend_statues",friendRq).optJSONArray("set_friend_statues");
            if(friendList == null) {
                CanvasMain.submitLogString(Locale.get(Locale.F_FRIEND_QUERY_FAILED));
                return;
            }
            JSONObject gifts = doPost("/account/get_pending_messages", genInitial());
            ArrayList<Gift> alreadySent = new ArrayList<>();
            if(gifts.has("set_sent_messages")) {
                for(Object o : gifts.getJSONArray("set_sent_messages")) {
                    JSONObject sent = (JSONObject) o;
                    alreadySent.add(new Gift(sent.getString("to_id"), sent.getString("type"), null));
                }
            }

            //appendlnToLog(friendList.toString());
            ThreadPoolExecutor tpe = new ThreadPoolExecutor(4,4,200, TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>());
            for (Object o : friendList) {
                JSONObject friend = (JSONObject) o;
                Gift cobj = new Gift(friend.getString("friend_id"), "gift_heart_wax", friend.has("nickname") ? friend.getString("nickname") : "<unknown>");
                if(!alreadySent.contains(cobj))
                    tpe.execute(new GiftTask(cobj, this));
            }
            tpe.shutdown();
            tpe.awaitTermination(Long.MAX_VALUE,TimeUnit.DAYS);
        }catch(Exception e) {
            CanvasMain.submitLogString(Locale.get(Locale.G_EXCEPTION, e.toString()));
        }
    }
    public void collectGifts() {
        try {
            JSONObject gifts = doPost("/account/get_pending_messages", genInitial());
            if(gifts.has("set_recvd_messages")) {
                JSONArray giftsArray = gifts.getJSONArray("set_recvd_messages");
                System.out.println(giftsArray);
                ThreadPoolExecutor tpe = new ThreadPoolExecutor(4,4,200, TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>());
                for(Object o : giftsArray){
                    JSONObject gift = (JSONObject) o;
                    tpe.execute(new CollectGiftTask(gift.getLong("msg_id"), gift.getString("type"), this));
                }
                tpe.shutdown();
                boolean shutup_android_studio = tpe.awaitTermination(Long.MAX_VALUE,TimeUnit.DAYS);
                //tpe.execute(new CollectGiftTask());
            }else CanvasMain.submitLogString(Locale.get(Locale.G_C_CANTREAD));
        }catch (SkyProtocolException | InterruptedException e) {
            CanvasMain.submitLogString(Locale.get(Locale.G_EXCEPTION, e.toString()));
        }
    }
}
