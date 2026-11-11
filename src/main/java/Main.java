import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

public class Main {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
    private static final Map<Long, Session> sessions = new HashMap<>();
    private static JsonNode content;
    private static String token;

    private static class Session {
        int question;
        int score;
        boolean quiz;
        String exercise;
    }

    public static void main(String[] args) throws Exception {
        token = System.getenv("BOT_TOKEN");
        if (token == null || token.isBlank()) {
            System.out.println("BotFather токенін енгізіп, Enter бас:");
            token = new Scanner(System.in).nextLine().trim();
        }
        if (!token.matches("\\d+:[\\w-]+")) {
            System.out.println("Токен пішімі қате. BotFather берген токенді көшір.");
            return;
        }
        try (var stream = Main.class.getResourceAsStream("/content.json")) {
            if (stream == null) throw new IllegalStateException("content.json табылмады");
            content = JSON.readTree(stream);
        }
        JsonNode me = api("getMe", Map.of());
        if (!api("getWebhookInfo", Map.of()).path("url").asText().isEmpty()) {
            api("deleteWebhook", Map.of("drop_pending_updates", false));
        }
        System.out.println("Бот іске қосылды: @" + me.path("username").asText());
        long offset = 0;
        long pendingId = -1;
        Map<String, Object> pending = null;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                JsonNode updates = api("getUpdates", Map.of(
                        "offset", offset, "timeout", 25, "allowed_updates", List.of("message")));
                for (JsonNode update : updates) {
                    long id = update.path("update_id").asLong();
                    JsonNode message = update.path("message");
                    if (message.path("chat").path("type").asText().equals("private")) {
                        long chat = message.path("chat").path("id").asLong();
                        if (pendingId != id) {
                            pending = answer(chat, message.path("text").asText(""));
                            pending.put("chat_id", chat);
                            pendingId = id;
                        }
                        try {
                            api("sendMessage", pending);
                        } catch (ApiException e) {
                            if (e.code != 400 && e.code != 403) throw e;
                            System.out.println("Хабарлама жіберілмеді. Код: " + e.code);
                        }
                    }
                    offset = id + 1;
                    pendingId = -1;
                    pending = null;
                }
            } catch (ApiException e) {
                if (e.code == 401 || e.code == 404 || e.code == 409) {
                    System.out.println("Бот тоқтады. Код: " + e.code
                            + ". Токенді тексер және осы боттың басқа іске қосылған нұсқасын тоқтат.");
                    return;
                }
                Thread.sleep(Math.max(3, e.retryAfter) * 1000L);
            } catch (java.io.IOException e) {
                System.out.println("Байланыс үзілді. Қайта қосылуда...");
                Thread.sleep(3000);
            }
        }
    }

    private static JsonNode api(String method, Map<String, ?> body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + token + "/" + method))
                .timeout(Duration.ofSeconds(40))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode data = JSON.readTree(response.body());
        if (!data.path("ok").asBoolean()) {
            throw new ApiException(data.path("error_code").asInt(response.statusCode()),
                    data.path("parameters").path("retry_after").asInt(3));
        }
        return data.path("result");
    }

    private static class ApiException extends Exception {
        final int code;
        final int retryAfter;
        ApiException(int code, int retryAfter) {
            super("Telegram қатесі: " + code);
            this.code = code;
            this.retryAfter = retryAfter;
        }
    }

    private static Map<String, Object> reply(String text, Object keyboard) {
        var result = new HashMap<String, Object>();
        result.put("text", text);
        result.put("reply_markup", Map.of("keyboard", keyboard, "resize_keyboard", true));
        return result;
    }

    private static Session newSession(long chat) {
        if (sessions.size() >= 1000) sessions.remove(sessions.keySet().iterator().next());
        Session session = new Session();
        sessions.put(chat, session);
        return session;
    }

    private static Map<String, Object> quiz(Session session, String feedback) {
        JsonNode question = content.path("questions").get(session.question);
        StringBuilder text = new StringBuilder(feedback)
                .append(session.question + 1).append("/5 — ").append(question.get(0).asText());
        for (int i = 0; i < 3; i++) {
            text.append("\n").append((char) ('A' + i)).append(". ").append(question.get(1).get(i).asText());
        }
        return reply(text.toString(), List.of(List.of("A", "B", "C"), List.of("🏠 Мәзір")));
    }

    static Map<String, Object> answer(long chat, String incoming) {
        String text = incoming.trim();
        String command = text.split("\\s+", 2)[0].replaceFirst("@\\w+$", "").toLowerCase(Locale.ROOT);
        Object menu = content.path("menu");
        Session session = sessions.get(chat);
        if (List.of("/start", "/menu", "🏠 Мәзір").contains(text) || command.equals("/start")) {
            sessions.remove(chat);
            return reply("Бағдарлама көмекшісі\n\nБағдарламалауды қазақша үйрен. Төмендегі бөлімді таңда.", menu);
        }
        if (text.equals("📚 Тақырыптар") || command.equals("/topics")) {
            sessions.remove(chat);
            return reply("Тақырыпты таңда:", content.path("topics"));
        }
        if (text.equals("🧩 Тапсырмалар") || command.equals("/practice")) {
            sessions.remove(chat);
            return reply("JavaScript тапсырмасын таңда:",
                    List.of(List.of("1️⃣ Цикл", "2️⃣ Массив", "3️⃣ Шарт"), List.of("🏠 Мәзір")));
        }
        if (text.equals("🎯 Тест") || command.equals("/quiz")) {
            session = newSession(chat);
            session.quiz = true;
            return quiz(session, "");
        }
        if (text.equals("ℹ️ Көмек") || command.equals("/help")) {
            sessions.remove(chat);
            return reply("/topics — тақырыптар\n/practice — тапсырмалар\n/quiz — тест\n/errors — қателер\n/menu — мәзір\n\nМысалы, «Java деген не?» деп жаз.", menu);
        }
        if (text.equals("🛠 Қателер") || command.equals("/errors")) {
            sessions.remove(chat);
            return reply("Қате атауын жібер: NullPointerException, SyntaxError, ReferenceError, IndexError.", menu);
        }
        if (content.path("exercises").has(text)) {
            session = newSession(chat);
            session.exercise = text;
            return reply(content.path("exercises").path(text).get(0).asText(),
                    List.of(List.of("💡 Кеңес", "✅ Шешімі"), List.of("🧩 Тапсырмалар", "🏠 Мәзір")));
        }
        if (session != null && session.quiz) {
            if (!text.matches("(?i)[ABC]")) return quiz(session, "A, B немесе C таңда.\n");
            JsonNode question = content.path("questions").get(session.question);
            int correct = question.get(2).asInt();
            boolean passed = text.toUpperCase(Locale.ROOT).charAt(0) - 'A' == correct;
            if (passed) session.score++;
            String feedback = (passed ? "✅ Дұрыс. " : "Дұрыс жауап — " + (char) ('A' + correct) + ". ")
                    + question.get(3).asText() + "\n\n";
            session.question++;
            if (session.question < content.path("questions").size()) return quiz(session, feedback);
            sessions.remove(chat);
            return reply(feedback + "Нәтиже — " + session.score + "/5", menu);
        }
        if (session != null && session.exercise != null && List.of("💡 Кеңес", "✅ Шешімі").contains(text)) {
            return reply(content.path("exercises").path(session.exercise).get(text.equals("💡 Кеңес") ? 1 : 2).asText(),
                    List.of(List.of("💡 Кеңес", "✅ Шешімі"), List.of("🧩 Тапсырмалар", "🏠 Мәзір")));
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("nullpointerexception")) return reply("Объект null болуы мүмкін. Қате көрсеткен жолдағы объектінің қай жерде жасалғанын тексер.", menu);
        if (lower.contains("syntaxerror")) return reply("Жақшалар мен тырнақшаларды тексер. Қате көрсеткен жолдың алдындағы жолды да қара.", menu);
        if (lower.contains("referenceerror") || lower.contains("is not defined")) return reply("Айнымалының атауын және қай ауқымда жарияланғанын тексер.", menu);
        if (lower.contains("indexerror") || lower.contains("indexoutofbounds")) return reply("Индекс массив шегінен шыққан. Ұзындығы 3 массивтің индекстері 0, 1, 2.", menu);
        if (lower.contains("no main manifest")) return reply("JAR манифестінде Main-Class жоқ. Maven жинақтау конфигурациясын тексер.", menu);
        Set<String> words = new HashSet<>(Arrays.asList(lower.split("[^\\p{L}\\p{N}]+")));
        JsonNode selected = null;
        for (JsonNode lesson : content.path("lessons")) {
            for (String key : lesson.get(0).asText().split(" ")) {
                if (words.contains(key)) selected = lesson;
            }
        }
        if (selected != null) return reply(selected.get(1).asText() + "\n\n"
                + selected.get(2).asText() + "\n\n" + selected.get(3).asText(), content.path("topics"));
        return reply("Дайын жауап табылмады. «📚 Тақырыптар» бөлімінен тақырып таңда.", menu);
    }
}
