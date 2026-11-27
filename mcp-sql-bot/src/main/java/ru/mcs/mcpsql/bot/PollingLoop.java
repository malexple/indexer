package ru.mcs.mcpsql.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Long polling on its own non-daemon thread, which also keeps the JVM alive. */
@Component
public class PollingLoop implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(PollingLoop.class);

    private final TelegramApi api;
    private final BotHandler handler;
    private final BotProperties properties;
    private volatile boolean running;
    private Thread thread;

    public PollingLoop(TelegramApi api, BotHandler handler, BotProperties properties) {
        this.api = api;
        this.handler = handler;
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (properties.token().isBlank()) {
            throw new IllegalStateException("BOT_TOKEN is not set");
        }
        if (properties.internalKey().isBlank()) {
            throw new IllegalStateException("BOT_INTERNAL_KEY is not set");
        }
        running = true;
        thread = new Thread(this::loop, "telegram-poller");
        thread.start();
        LOG.info("bot started, service at {}", properties.serviceUrl());
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        long offset = 0;
        while (running) {
            try {
                Updates batch = api.getUpdates(offset, properties.pollSeconds());
                offset = batch.nextOffset();
                for (Update update : batch.items()) {
                    try {
                        handler.handle(update);
                    } catch (RuntimeException e) {
                        LOG.warn("update {} failed: {}", update.updateId(), e.toString());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (!running) {
                    return;
                }
                LOG.warn("polling failed, retrying in 5 s: {}", e.getMessage());
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
