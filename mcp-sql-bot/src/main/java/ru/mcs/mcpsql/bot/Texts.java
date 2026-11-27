package ru.mcs.mcpsql.bot;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** All user-visible texts (Telegram HTML). Everything that comes from outside goes through {@link #esc}. */
final class Texts {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    static final String START = "<b>mcp-sql</b>: доступ вашего ИИ-ассистента к индексу вашего кода.\n\n"
            + "Как это работает:\n"
            + "1. Нажмите «Получить ключи» и выберите срок.\n"
            + "2. Загрузите индекс (Indexer, команда push) своим ключом записи.\n"
            + "3. Дайте ИИ-ассистенту ключ чтения и адрес сервера.\n\n"
            + "По истечении срока ключи и ваши базы на сервере удаляются автоматически. "
            + "Ключи можно выдать заново, а если они попали не в те руки, их можно в любой момент отозвать "
            + "вместе с базами кнопкой «Удалить всё».";

    static final String HELP = "<b>Справка</b>\n\n"
            + "Ключ записи только для вас: им загружают базу, патчи и удаляют базы. Он же читает.\n"
            + "Ключ чтения отдайте ИИ-ассистенту: он умеет только читать.\n\n"
            + "Новая пара ключей отзывает прежнюю и продлевает срок жизни ваших баз.\n"
            + "Лимиты (загрузки, объём, запросы, число баз) смотрите в «Мои данные».\n\n"
            + "Команды: /keys, /me, /stats, /reset, /help.";

    static final String CHOOSE_TTL = "На какой срок выдать ключи?\nПрежняя пара ключей, если она есть, будет отозвана.";

    static final String CONFIRM_RESET = "<b>Удалить всё?</b>\nКлючи будут отозваны, а все ваши базы на сервере удалены. "
            + "Это нельзя отменить.";

    static final String RESET_CANCELLED = "Отменено, ничего не удалено.";

    static final String UNKNOWN = "Не понял. Выберите действие кнопкой или отправьте /help.";

    private Texts() {
    }

    static String esc(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String time(String iso) {
        try {
            return TIME.format(Instant.parse(iso));
        } catch (RuntimeException e) {
            return esc(iso);
        }
    }

    static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / 1048576.0);
    }

    static String keys(ServiceClient.IssuedKeys keys, String publicUrl) {
        StringBuilder b = new StringBuilder();
        b.append("<b>Ключи на ").append(esc(keys.ttl())).append("</b>, действуют до ")
                .append(time(keys.expiresAt())).append(".\n\n");
        b.append("Ключ записи (только для вас: загрузка, патчи, удаление):\n<code>")
                .append(esc(keys.writeKey())).append("</code>\n\n");
        b.append("Ключ чтения (для ИИ-ассистента):\n<code>").append(esc(keys.readKey())).append("</code>\n\n");
        if (publicUrl != null && !publicUrl.isBlank()) {
            String url = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
            b.append("Адрес сервера: <code>").append(esc(url)).append("</code>\n");
            b.append("MCP-адрес базы: <code>").append(esc(url)).append("/mcp/ИМЯ_БАЗЫ</code> ")
                    .append("(ключ чтения передаётся заголовком Authorization: Bearer).\n\n");
        }
        b.append("Ключи показаны один раз, прежняя пара отозвана. Не публикуйте ключ записи.");
        return b.toString();
    }

    static String issueError(ServiceException e) {
        return switch (e.code()) {
            case "KEY_ISSUE_LIMIT" -> "Лимит выдач ключей на сегодня исчерпан. Если ключи скомпрометированы, нажмите "
                    + "«Удалить всё»: отзыв ключей доступен всегда и возвращает выдачи.";
            case "CAPACITY" -> "Сервер сейчас заполнен: достигнут лимит одновременных пользователей. "
                    + "Попробуйте позже.";
            case "BANNED" -> "Доступ для вашего аккаунта закрыт.";
            default -> e.status() == 0 || e.status() >= 500
                    ? "Сервис временно недоступен, попробуйте чуть позже."
                    : "Не удалось выдать ключи: " + esc(e.getMessage());
        };
    }

    static String me(ServiceClient.Me me) {
        if (!me.registered() || !me.active()) {
            return "Активных ключей нет, баз на сервере тоже. Нажмите «Получить ключи».";
        }
        ServiceClient.Usage u = me.usage();
        ServiceClient.Limits l = me.limits();
        StringBuilder b = new StringBuilder();
        b.append("<b>Ключи</b> на ").append(esc(me.ttl())).append(", действуют до ").append(time(me.expiresAt()))
                .append(".\n\n<b>Базы на сервере</b> (").append(me.workspaces().size()).append(" из ")
                .append(l.maxWorkspaces()).append(", до ").append(mb(l.maxDbBytes())).append(" МБ каждая):\n");
        if (me.workspaces().isEmpty()) {
            b.append("пока ничего не загружено\n");
        }
        for (ServiceClient.WorkspaceView w : me.workspaces()) {
            String projects = w.projects().stream().map(Texts::esc).collect(Collectors.joining(", "));
            b.append("• <code>").append(esc(w.name())).append("</code>: ").append(projects.isEmpty() ? "без проектов" : projects)
                    .append(", ").append(mb(w.bytes())).append(" МБ, seq ").append(w.seq()).append('\n');
        }
        b.append("\n<b>Сегодня (UTC)</b>\n");
        b.append("Загрузки баз: ").append(u.uploads()).append(" из ").append(l.uploadsPerDay()).append('\n');
        b.append("Объём записи: ").append(mb(u.writeBytes())).append(" из ").append(mb(l.writeBytesPerDay()))
                .append(" МБ\n");
        b.append("Патчи: ").append(u.patches()).append(" (не чаще раза в ").append(l.patchIntervalSeconds())
                .append(" с)\n");
        b.append("Запросы MCP: ").append(u.requests()).append(" из ").append(l.requestsPerDay()).append('\n');
        b.append("Выдачи ключей: ").append(u.keyIssues()).append(" из ").append(l.keyIssuesPerDay());
        return b.toString();
    }

    static String stats(ServiceClient.Stats s) {
        return "<b>Сервер</b>\nЗарегистрировано: " + s.registered() + "\nСейчас с ключами: " + s.activeUsers()
                + "\nАктивные пары: 5 часов — " + s.pairs5h() + ", сутки — " + s.pairs1d() + ", неделя — "
                + s.pairs7d();
    }

    static String resetDone(ServiceClient.ResetResult r) {
        return r.reset()
                ? "Готово: ключи отозваны, ваши базы на сервере удалены (файлов: " + r.deletedFiles() + ")."
                : "У вас нет ключей и данных на сервере.";
    }

    static String unavailable() {
        return "Сервис временно недоступен, попробуйте чуть позже.";
    }

    static List<String> ttls() {
        return List.of("5h", "1d", "7d");
    }
}
